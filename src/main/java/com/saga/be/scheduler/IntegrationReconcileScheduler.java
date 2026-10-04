package com.saga.be.scheduler;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.sync.GitHubCommitSyncService;
import com.saga.be.service.sync.ProjectManualSyncService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Background catch-up so nobody has to press "Đồng bộ": webhooks bring changes in real time, and
 * this re-syncs every ACTIVE Jira site and every project with an ACTIVE GitHub repository whose last
 * data sync is older than {@code interval}, picking up whatever a webhook missed (server restart,
 * deploy, provider outage).
 *
 * <ul>
 *   <li>Right after the app starts, every target is due once, so a deploy that changes how data
 *       is stored re-syncs everyone without anyone pressing the button.
 *   <li>Each tick queues at most {@code batchPerTick} jobs per provider, oldest first, and only when
 *       that provider's sync queue is empty, so a person pressing "Đồng bộ" never waits behind a
 *       long backlog of automatic jobs.
 *   <li>GitHub is synced INCREMENTAL (new commits only); a FULL walk (also refreshing which branch
 *       holds which commit) runs at night when a repository's last full walk is older than
 *       {@code githubFullAfter}.
 * </ul>
 */
@Component
@Profile("!test")
public class IntegrationReconcileScheduler {

	private static final Logger log = LoggerFactory.getLogger(IntegrationReconcileScheduler.class);
	private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");

	record Settings(
			boolean enabled,
			Duration interval,
			int batchPerTick,
			boolean resyncOnStartup,
			Duration githubFullAfter,
			int fullWindowStartHour,
			int fullWindowEndHour) {}

	private record Due(UUID targetId, LocalDateTime lastStartedAt) {}

	private final JiraIntegrationRepository jiraIntegrations;
	private final GitRepoRepository repos;
	private final SyncJobLogRepository syncJobs;
	private final ProjectManualSyncService sync;
	private final IntegrationProperties properties;
	private final Executor jiraExecutor;
	private final Executor githubExecutor;
	private final Clock clock;
	private final Settings settings;
	private final LocalDateTime appStartedAt;

	@Autowired
	public IntegrationReconcileScheduler(
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository repos,
			SyncJobLogRepository syncJobs,
			ProjectManualSyncService sync,
			IntegrationProperties properties,
			@Qualifier("integrationSyncExecutor") Executor jiraExecutor,
			@Qualifier("githubSyncExecutor") Executor githubExecutor,
			@Value("${saga.sync.reconcile.enabled:true}") boolean enabled,
			@Value("${saga.sync.reconcile.interval:4h}") Duration interval,
			@Value("${saga.sync.reconcile.batch-per-tick:2}") int batchPerTick,
			@Value("${saga.sync.reconcile.resync-on-startup:true}") boolean resyncOnStartup,
			@Value("${saga.sync.reconcile.github-full-after:24h}") Duration githubFullAfter,
			@Value("${saga.sync.reconcile.full-window-start-hour:1}") int fullWindowStartHour,
			@Value("${saga.sync.reconcile.full-window-end-hour:5}") int fullWindowEndHour) {
		this(jiraIntegrations, repos, syncJobs, sync, properties, jiraExecutor, githubExecutor, Clock.systemDefaultZone(),
				new Settings(enabled, interval, batchPerTick, resyncOnStartup, githubFullAfter, fullWindowStartHour,
						fullWindowEndHour));
	}

	IntegrationReconcileScheduler(
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository repos,
			SyncJobLogRepository syncJobs,
			ProjectManualSyncService sync,
			IntegrationProperties properties,
			Executor jiraExecutor,
			Executor githubExecutor,
			Clock clock,
			Settings settings) {
		this.jiraIntegrations = jiraIntegrations;
		this.repos = repos;
		this.syncJobs = syncJobs;
		this.sync = sync;
		this.properties = properties;
		this.jiraExecutor = jiraExecutor;
		this.githubExecutor = githubExecutor;
		this.clock = clock;
		this.settings = settings;
		this.appStartedAt = LocalDateTime.now(clock);
	}

	@Scheduled(
			fixedDelayString = "${saga.sync.reconcile.tick:2m}",
			initialDelayString = "${saga.sync.reconcile.initial-delay:2m}")
	public void tick() {
		if (!settings.enabled()) {
			return;
		}
		try {
			reconcileJira();
		} catch (RuntimeException ex) {
			log.warn("jira reconcile tick failed type={}", ex.getClass().getSimpleName());
		}
		try {
			reconcileGithub();
		} catch (RuntimeException ex) {
			log.warn("github reconcile tick failed type={}", ex.getClass().getSimpleName());
		}
	}

	void reconcileJira() {
		if (!properties.getJira().isEnabled() || !queueIsEmpty(jiraExecutor)) {
			return;
		}
		List<Due> due = new ArrayList<>();
		for (JiraIntegration source : jiraIntegrations.findByConnectionStatus(IntegrationStatus.ACTIVE)) {
			LocalDateTime last = lastDataSync("JIRA", source.getId());
			if (isDue(last)) {
				due.add(new Due(source.getId(), last));
			}
		}
		enqueueOldestFirst("jira", due, id -> sync.enqueueJiraIntegration(id));
	}

	void reconcileGithub() {
		if (!properties.getGithub().isEnabled() || !queueIsEmpty(githubExecutor)) {
			return;
		}
		List<Due> due = new ArrayList<>();
		for (UUID projectId : repos.findProjectIdsByConnectionStatus(IntegrationStatus.ACTIVE)) {
			LocalDateTime last = lastDataSync("GITHUB", projectId);
			if (isDue(last)) {
				due.add(new Due(projectId, last));
			}
		}
		enqueueOldestFirst("github", due, id -> sync.enqueueGithubProject(id, githubMode(id)));
	}

	/** FULL only inside the night window and when some ACTIVE repository's last full walk is old. */
	GitHubCommitSyncService.Mode githubMode(UUID projectId) {
		int hour = clock.instant().atZone(VIETNAM).getHour();
		if (hour < settings.fullWindowStartHour() || hour >= settings.fullWindowEndHour()) {
			return GitHubCommitSyncService.Mode.INCREMENTAL;
		}
		LocalDateTime fullBefore = LocalDateTime.now(clock).minus(settings.githubFullAfter());
		for (GitRepo repo : repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)) {
			if (repo.getBranchMembershipSyncedAt() == null || repo.getBranchMembershipSyncedAt().isBefore(fullBefore)) {
				return GitHubCommitSyncService.Mode.FULL;
			}
		}
		return GitHubCommitSyncService.Mode.INCREMENTAL;
	}

	/** Never synced, last sync older than the interval, or (once) older than this app start. */
	boolean isDue(LocalDateTime lastStartedAt) {
		if (lastStartedAt == null) {
			return true;
		}
		if (settings.resyncOnStartup() && lastStartedAt.isBefore(appStartedAt)) {
			return true;
		}
		return lastStartedAt.isBefore(LocalDateTime.now(clock).minus(settings.interval()));
	}

	private void enqueueOldestFirst(String provider, List<Due> due, Function<UUID, String> enqueue) {
		due.sort(Comparator.comparing(Due::lastStartedAt, Comparator.nullsFirst(Comparator.naturalOrder())));
		int queued = 0;
		for (Due target : due) {
			if (queued >= settings.batchPerTick()) {
				break;
			}
			String state;
			try {
				state = enqueue.apply(target.targetId());
			} catch (RejectedExecutionException ex) {
				log.info("{} reconcile stopped: sync queue full", provider);
				return;
			}
			// Skipped targets (no credential, already running) do not use up this tick's batch.
			if (ProjectSyncEnqueueResponse.QUEUED.equals(state)) {
				queued++;
				log.info("{} reconcile queued targetId={} lastSyncStartedAt={}", provider, target.targetId(),
						target.lastStartedAt());
			}
		}
	}

	private LocalDateTime lastDataSync(String system, UUID targetId) {
		return syncJobs.findFirstByTargetSystemAndTargetIdAndJobTypeOrderByStartedAtDesc(system, targetId, SyncJobType.INITIAL)
				.map(SyncJobLog::getStartedAt)
				.orElse(null);
	}

	private static boolean queueIsEmpty(Executor executor) {
		return !(executor instanceof ThreadPoolTaskExecutor pool) || pool.getQueueSize() == 0;
	}
}

package com.saga.be.scheduler;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.service.projection.JiraAutoFailoverService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Watches the Jira sources of projects that have a second, standby source, and starts the
 * automatic failover once a source is dead. Jira sync is event-driven, so without this probe a
 * dead site would go unnoticed.
 *
 * <p>Dead is deliberately strict, so a network blip or a provider outage never creates issues: only
 * "this site is gone" answers count (401 / 403 / 404, revoked or unrefreshable token), timeouts and
 * 5xx are ignored, and a source must keep failing that way for at least {@code dead-after} and
 * {@code min-failed-probes} probes. The target must answer in the same round, so when every source
 * of a project fails together (e.g. an Atlassian outage) nothing moves. One success clears the
 * streak. Streaks live in memory: a restart only delays a failover.
 */
@Component
@Profile("!test")
public class JiraSourceHealthMonitor {

	enum Health { HEALTHY, DEAD, UNKNOWN }

	record Streak(LocalDateTime firstFailedAt, int failedProbes) {}

	private static final Logger log = LoggerFactory.getLogger(JiraSourceHealthMonitor.class);
	private static final Set<IntegrationErrorCode> DEAD_CODES = Set.of(
			IntegrationErrorCode.INTEGRATION_REVOKED,
			IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED,
			IntegrationErrorCode.JIRA_UNAUTHORIZED,
			IntegrationErrorCode.INTEGRATION_FORBIDDEN);

	private final JiraIntegrationRepository integrations;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jira;
	private final JiraAutoFailoverService failover;
	private final Clock clock;
	private final boolean enabled;
	private final Duration deadAfter;
	private final int minFailedProbes;
	private final Map<UUID, Streak> streaks = new ConcurrentHashMap<>();

	@Autowired
	public JiraSourceHealthMonitor(
			JiraIntegrationRepository integrations,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jira,
			JiraAutoFailoverService failover,
			@Value("${saga.jira.auto-failover.enabled:true}") boolean enabled,
			@Value("${saga.jira.auto-failover.dead-after:1h}") Duration deadAfter,
			@Value("${saga.jira.auto-failover.min-failed-probes:3}") int minFailedProbes) {
		this(integrations, tokens, jira, failover, Clock.systemDefaultZone(), enabled, deadAfter, minFailedProbes);
	}

	JiraSourceHealthMonitor(
			JiraIntegrationRepository integrations,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jira,
			JiraAutoFailoverService failover,
			Clock clock,
			boolean enabled,
			Duration deadAfter,
			int minFailedProbes) {
		this.integrations = integrations;
		this.tokens = tokens;
		this.jira = jira;
		this.failover = failover;
		this.clock = clock;
		this.enabled = enabled;
		this.deadAfter = deadAfter;
		this.minFailedProbes = Math.max(1, minFailedProbes);
	}

	@Scheduled(fixedDelayString = "${saga.jira.auto-failover.probe-interval:10m}", initialDelayString = "PT3M")
	public void probe() {
		if (!enabled) {
			return;
		}
		Map<UUID, List<JiraIntegration>> byProject = new LinkedHashMap<>();
		for (JiraIntegration source : integrations.findByConnectionStatus(IntegrationStatus.ACTIVE)) {
			if (source.getProject() != null && !blank(source.getCloudId()) && !blank(source.getJiraProjectId())) {
				byProject.computeIfAbsent(source.getProject().getId(), id -> new ArrayList<>()).add(source);
			}
		}
		for (List<JiraIntegration> sources : byProject.values()) {
			if (sources.size() >= 2) {
				probeProject(sources);
			}
		}
	}

	/** One round for one project with at least two active sources. */
	void probeProject(List<JiraIntegration> sources) {
		LocalDateTime now = LocalDateTime.now(clock);
		Map<UUID, Health> health = new LinkedHashMap<>();
		for (JiraIntegration source : sources) {
			health.put(source.getId(), check(source));
		}
		for (JiraIntegration source : sources) {
			Health state = health.get(source.getId());
			if (state == Health.HEALTHY) {
				streaks.remove(source.getId());
				continue;
			}
			if (state != Health.DEAD) {
				continue; // unknown failures neither start, extend nor clear a streak
			}
			Streak streak = streaks.merge(source.getId(), new Streak(now, 1),
					(old, fresh) -> new Streak(old.firstFailedAt(), old.failedProbes() + 1));
			if (streak.failedProbes() < minFailedProbes || Duration.between(streak.firstFailedAt(), now).compareTo(deadAfter) < 0) {
				continue;
			}
			List<JiraIntegration> healthy = sources.stream()
					.filter(other -> !other.getId().equals(source.getId()) && health.get(other.getId()) == Health.HEALTHY)
					.toList();
			if (healthy.size() != 1) {
				log.warn("jira source dead but no single healthy target sourceId={} healthyTargets={}", source.getId(), healthy.size());
				continue;
			}
			JiraAutoFailoverService.Result result = failover.failover(source.getId(), healthy.getFirst().getId(), streak.firstFailedAt());
			log.info("jira auto failover sourceId={} targetId={} outcome={} reason={} tasks={}", source.getId(),
					healthy.getFirst().getId(), result.outcome(), result.reason(), result.movedTasks());
			if (result.outcome() != JiraAutoFailoverService.Outcome.SKIPPED) {
				streaks.remove(source.getId());
			}
			// one failover per project per round; the rest is re-evaluated next round
			return;
		}
	}

	/** A cheap authenticated read of the source's Jira project. */
	Health check(JiraIntegration source) {
		try {
			jira.listProjectIssueTypes(tokens.accessToken(source), source.getCloudId(), source.getJiraProjectId());
			return Health.HEALTHY;
		} catch (IntegrationException ex) {
			int status = ex.getStatus() == null ? 0 : ex.getStatus().value();
			boolean gone = status == 401 || status == 403 || status == 404 || DEAD_CODES.contains(ex.getCode());
			return gone ? Health.DEAD : Health.UNKNOWN;
		} catch (RuntimeException ex) {
			return Health.UNKNOWN;
		}
	}

	Map<UUID, Streak> streaks() {
		return streaks;
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}

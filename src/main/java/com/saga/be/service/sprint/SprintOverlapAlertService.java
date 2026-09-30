package com.saga.be.service.sprint;

import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.WarningCategory;
import com.saga.be.entity.enums.WarningSeverity;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.warning.BusinessWarning;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.repository.BusinessWarningRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.mail.EmailOutboxService;
import com.saga.be.service.notification.NotificationService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Detects sprints of one project that already overlap in time -- typically a sprint started
 * directly in Jira, which SAGA's own create/patch guard cannot stop -- and warns the team Leader(s)
 * and the course lecturer once per overlapping pair, by in-app notification AND email.
 *
 * <p>Runs after every SPRINTS_CHANGED (Jira sync, sprint webhooks, SAGA sprint commands) and after a
 * board sprint sync. Only pairs where both sprints actually ran (active/closed) are alerted: those
 * are the ones that already skew per-sprint scoring. Each pair is recorded as a
 * {@link BusinessWarning} with a pair event key; the project row is locked first so two concurrent
 * syncs cannot both alert the same pair. Alerting never breaks the caller: failures are logged.
 */
@Service
@Profile("!test")
public class SprintOverlapAlertService {

	static final String WARNING_TYPE = "SPRINT_PERIOD_OVERLAP";
	static final String EMAIL_TYPE = "SPRINT_PERIOD_OVERLAP";
	static final String EVENT_KEY_PREFIX = "sprint-period-overlap:";

	private static final Logger log = LoggerFactory.getLogger(SprintOverlapAlertService.class);

	private final ProjectRepository projects;
	private final SprintRepository sprints;
	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final BusinessWarningRepository warnings;
	private final NotificationService notifications;
	private final EmailOutboxService emails;
	private final EmailTemplateService templates;
	private final TransactionTemplate freshTransaction;
	private final Executor executor;
	private final Clock clock;

	@Autowired
	public SprintOverlapAlertService(
			ProjectRepository projects,
			SprintRepository sprints,
			TeamRepository teams,
			TeamMemberRepository members,
			BusinessWarningRepository warnings,
			NotificationService notifications,
			EmailOutboxService emails,
			EmailTemplateService templates,
			PlatformTransactionManager transactionManager,
			@Qualifier("sprintOverlapExecutor") Executor executor) {
		this(
				projects,
				sprints,
				teams,
				members,
				warnings,
				notifications,
				emails,
				templates,
				transactionManager,
				executor,
				Clock.systemUTC());
	}

	SprintOverlapAlertService(
			ProjectRepository projects,
			SprintRepository sprints,
			TeamRepository teams,
			TeamMemberRepository members,
			BusinessWarningRepository warnings,
			NotificationService notifications,
			EmailOutboxService emails,
			EmailTemplateService templates,
			PlatformTransactionManager transactionManager,
			Executor executor,
			Clock clock) {
		this.projects = projects;
		this.sprints = sprints;
		this.teams = teams;
		this.members = members;
		this.warnings = warnings;
		this.notifications = notifications;
		this.emails = emails;
		this.templates = templates;
		// Checks normally run on their own executor thread; REQUIRES_NEW also keeps a direct call from
		// inside another transaction (or its afterCommit callback) from joining that transaction.
		this.freshTransaction = new TransactionTemplate(transactionManager);
		this.freshTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.executor = executor;
		this.clock = clock;
	}

	@EventListener
	public void onProjectEvent(ProjectRealtimeEvent event) {
		if (event != null && event.type() == ProjectRealtimeEventType.SPRINTS_CHANGED && event.projectId() != null) {
			checkProjectAsync(event.projectId());
		}
	}

	/**
	 * Queues a check off the caller's thread. SPRINTS_CHANGED arrives from afterCommit callbacks that
	 * still hold their JDBC connection; checking inline would ask the small pool for a second one.
	 * A rejected run is only logged: the check is idempotent and runs again on the next change.
	 */
	public void checkProjectAsync(UUID projectId) {
		if (projectId == null) {
			return;
		}
		try {
			executor.execute(() -> checkProjectSafely(projectId));
		} catch (RejectedExecutionException ex) {
			log.warn("sprint overlap check skipped (queue full) projectId={}", projectId);
		}
	}

	/** Never throws: an alerting problem must not fail the sync/webhook/request that triggered it. */
	public void checkProjectSafely(UUID projectId) {
		try {
			checkProject(projectId);
		} catch (RuntimeException ex) {
			log.warn("sprint overlap check failed projectId={} error={}", projectId, ex.toString());
		}
	}

	/** Returns how many newly detected overlapping pairs were alerted. */
	public int checkProject(UUID projectId) {
		if (projectId == null) {
			return 0;
		}
		Integer raised = freshTransaction.execute(status -> {
			Project project = projects.lockById(projectId).orElse(null);
			if (project == null) {
				return 0;
			}
			LocalDate today = LocalDate.now(clock);
			List<SprintPeriods.Overlap> overlaps =
					SprintPeriods.overlaps(sprints.findActiveFetchedByProject_Id(projectId), today, true);
			if (overlaps.isEmpty()) {
				return 0;
			}
			List<UserAccount> recipients = null;
			int count = 0;
			for (SprintPeriods.Overlap overlap : overlaps) {
				String eventKey = EVENT_KEY_PREFIX + overlap.pairKey();
				if (warnings.findByEventKey(eventKey).isPresent()) {
					continue;
				}
				if (recipients == null) {
					recipients = recipients(project);
				}
				raise(project, overlap, eventKey, recipients);
				count++;
			}
			return count;
		});
		return raised == null ? 0 : raised;
	}

	private void raise(
			Project project, SprintPeriods.Overlap overlap, String eventKey, List<UserAccount> recipients) {
		String first = describe(overlap.first());
		String second = describe(overlap.second());
		String projectName = StringUtils.hasText(project.getName()) ? project.getName() : "your project";
		String summary = truncate(
				"Sprints overlap in " + projectName + ": " + first + " and " + second
						+ ". Contribution and peer review are scored per sprint, so sprints must run one after another"
						+ " (even across Jira sites). Adjust the dates or close one sprint in Jira.",
				1000);

		BusinessWarning warning = new BusinessWarning();
		warning.setWarningType(WARNING_TYPE);
		warning.setCategory(WarningCategory.ASSESSMENT);
		warning.setEventKey(eventKey);
		warning.setSeverity(WarningSeverity.HIGH);
		warning.setProject(project);
		warning.setCourse(project.getCourse());
		warning.setTeam(teams.findByProject_Id(project.getId()).orElse(null));
		warning.setSprint(overlap.second());
		warning.setEvidenceSummary(summary);
		warnings.save(warning);

		Course course = project.getCourse();
		String classCode = course == null || course.getAcademicClass() == null ? null : course.getAcademicClass().getClassCode();
		for (UserAccount recipient : recipients) {
			notifications.createNotification(
					recipient.getId(), NotificationType.WARNING, "Sprints overlap", summary, null, eventKey);
			if (StringUtils.hasText(recipient.getEmail())) {
				emails.enqueue(new EmailEnqueueRequest(
						recipient.getEmail(),
						recipient.getId(),
						EMAIL_TYPE,
						EmailTemplateService.SPRINT_PERIOD_OVERLAP,
						templates.sprintPeriodOverlapPayload(
								recipient.getFullName(), recipient.getEmail(), projectName, classCode, first, second),
						null));
			}
		}
		log.info(
				"sprint overlap alerted projectId={} sprints={} recipients={}",
				project.getId(),
				overlap.pairKey(),
				recipients.size());
	}

	/** ACTIVE team Leader(s) of the project's team plus the course lecturer, each once. */
	private List<UserAccount> recipients(Project project) {
		Map<UUID, UserAccount> byId = new LinkedHashMap<>();
		Team team = teams.findByProject_Id(project.getId()).orElse(null);
		if (team != null) {
			for (TeamMember member : members.findFetchedByTeam_Id(team.getId())) {
				if (member.getRoleInTeam() != RoleInTeam.LEADER
						|| member.getCourseEnrollment() == null
						|| member.getCourseEnrollment().getEnrollmentStatus() != EnrollmentStatus.ACTIVE
						|| member.getCourseEnrollment().getStudentProfile() == null) {
					continue;
				}
				UserAccount leader = member.getCourseEnrollment().getStudentProfile().getUserAccount();
				if (leader != null && leader.getId() != null) {
					byId.putIfAbsent(leader.getId(), leader);
				}
			}
		}
		Course course = project.getCourse();
		if (course != null && course.getInstructor() != null) {
			UserAccount lecturer = course.getInstructor().getUserAccount();
			if (lecturer != null && lecturer.getId() != null) {
				byId.putIfAbsent(lecturer.getId(), lecturer);
			}
		}
		return new ArrayList<>(byId.values());
	}

	/** e.g. {@code "SAGA Sprint 5" — site-a (2026-09-20 → 2026-10-03, active)}. */
	static String describe(Sprint sprint) {
		JiraIntegration source = sprint.getJiraIntegration();
		String site = source == null ? null : StringUtils.hasText(source.getSiteName()) ? source.getSiteName() : source.getProjectKey();
		LocalDateTime end = SprintPeriods.isClosed(sprint.getState()) && sprint.getCompleteDate() != null
				? sprint.getCompleteDate()
				: sprint.getEndDate();
		String name = StringUtils.hasText(sprint.getName()) ? sprint.getName() : "Unnamed sprint";
		return "\"" + name + "\""
				+ (StringUtils.hasText(site) ? " — " + site : "")
				+ " (" + date(sprint.getStartDate()) + " → " + date(end) + ", " + (sprint.getState() == null ? "?" : sprint.getState()) + ")";
	}

	private static String date(LocalDateTime value) {
		return value == null ? "?" : value.toLocalDate().toString();
	}

	private static String truncate(String value, int max) {
		return value.length() <= max ? value : value.substring(0, max - 1) + "…";
	}
}

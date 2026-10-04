package com.saga.be.service.projection;

import com.saga.be.dto.integration.failover.JiraFailoverExecuteRequest;
import com.saga.be.dto.integration.failover.JiraFailoverExecuteResponse;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.notification.NotificationService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Automatic Jira failover: when the health monitor finds a project's Jira source dead while another
 * source of the same project is healthy, this moves the source's unfinished tasks to the healthy
 * source's backlog through the very same failover the team leader runs by hand (same snapshot,
 * claims, worker and soft revoke), acting as that team leader. Done tasks, subtasks and epics stay
 * where they are. The team is told what moved and that the leader must plan and assign the new
 * issues -- the failover does not carry assignees.
 */
@Service
@Profile("!test")
public class JiraAutoFailoverService {

	/** Bounded per run; the source is revoked afterwards, so any rest is moved by hand. */
	static final int MAX_TASKS = 200;
	private static final Logger log = LoggerFactory.getLogger(JiraAutoFailoverService.class);
	private static final DateTimeFormatter AT = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");

	public enum Outcome { MOVED, REVOKED_ONLY, SKIPPED, FAILED }

	public record Result(Outcome outcome, String reason, int movedTasks) {}

	private final JiraIntegrationRepository integrations;
	private final TaskRepository tasks;
	private final JiraTaskFailoverItemRepository failoverItems;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jira;
	private final JiraFailoverExecutionService execution;
	private final ProjectIntegrationService integrationCommands;
	private final NotificationService notifications;

	public JiraAutoFailoverService(
			JiraIntegrationRepository integrations,
			TaskRepository tasks,
			JiraTaskFailoverItemRepository failoverItems,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jira,
			JiraFailoverExecutionService execution,
			ProjectIntegrationService integrationCommands,
			NotificationService notifications) {
		this.integrations = integrations;
		this.tasks = tasks;
		this.failoverItems = failoverItems;
		this.teams = teams;
		this.members = members;
		this.tokens = tokens;
		this.jira = jira;
		this.execution = execution;
		this.integrationCommands = integrationCommands;
		this.notifications = notifications;
	}

	/**
	 * Moves {@code sourceId}'s unfinished tasks to {@code targetId}'s backlog. Both must still be
	 * ACTIVE sources of the same project; {@code deadSince} is when the source started failing.
	 */
	public Result failover(UUID sourceId, UUID targetId, LocalDateTime deadSince) {
		JiraIntegration source = integrations.findById(sourceId).orElse(null);
		JiraIntegration target = integrations.findById(targetId).orElse(null);
		if (source == null || target == null || source.getProject() == null || target.getProject() == null
				|| !source.getProject().getId().equals(target.getProject().getId())) {
			return skipped("SOURCE_OR_TARGET_MISSING");
		}
		if (source.getConnectionStatus() != IntegrationStatus.ACTIVE || target.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			return skipped("NOT_ACTIVE");
		}
		UUID projectId = source.getProject().getId();
		List<TeamMember> team = teamMembers(projectId);
		UUID leaderUserId = leaderUserId(team).orElse(null);
		if (leaderUserId == null) {
			log.warn("jira auto failover skipped projectId={} sourceId={} reason=NO_TEAM_LEADER", projectId, sourceId);
			return skipped("NO_TEAM_LEADER");
		}
		List<Task> candidates = candidates(tasks.findActiveFetchedByProjectAndJiraIntegration(projectId, sourceId));
		try {
			if (candidates.isEmpty()) {
				integrationCommands.disconnectJiraSource(leaderUserId, projectId, sourceId);
				notifyTeam(team, sourceId, "Site Jira " + label(source) + " đã được ngắt kết nối",
						"Site Jira " + label(source) + " không phản hồi từ " + deadSince.format(AT)
								+ ". SAGA đã ngắt kết nối site này; không có task chưa xong nào cần chuyển.", "revoked");
				return new Result(Outcome.REVOKED_ONLY, null, 0);
			}
			String issueTypeId = standardIssueType(jira.listProjectIssueTypes(
					tokens.accessToken(target), target.getCloudId(), target.getJiraProjectId())).orElse(null);
			if (issueTypeId == null) {
				return failed(team, source, target, "TARGET_ISSUE_TYPE_MISSING");
			}
			List<UUID> ids = candidates.stream().limit(MAX_TASKS).map(Task::getId).toList();
			JiraFailoverExecuteResponse response = execution.execute(leaderUserId, projectId, sourceId,
					new JiraFailoverExecuteRequest(targetId, null, issueTypeId, true, ids));
			String rest = candidates.size() > MAX_TASKS
					? " Còn " + (candidates.size() - MAX_TASKS) + " task chưa chuyển, trưởng nhóm hãy chuyển tiếp bằng tay."
					: "";
			notifyTeam(team, sourceId, "Đã tự chuyển task sang site Jira " + label(target),
					"Site Jira " + label(source) + " không phản hồi từ " + deadSince.format(AT) + ". SAGA đã ngắt kết nối site này và tạo "
							+ ids.size() + " task chưa xong vào backlog của site " + label(target)
							+ ". Trưởng nhóm hãy kéo các task vào sprint và giao người thực hiện." + rest,
					"moved:" + response.runId());
			log.info("jira auto failover started projectId={} sourceId={} targetId={} runId={} tasks={}",
					projectId, sourceId, targetId, response.runId(), ids.size());
			return new Result(Outcome.MOVED, null, ids.size());
		} catch (RuntimeException ex) {
			log.warn("jira auto failover failed projectId={} sourceId={} type={}", projectId, sourceId, ex.getClass().getSimpleName());
			return failed(team, source, target, ex.getClass().getSimpleName());
		}
	}

	/**
	 * Unfinished standard tasks not already moved or being moved: the same set a leader would pick in
	 * the failover preview, minus subtasks (Jira cannot create them standalone) and epics.
	 */
	List<Task> candidates(List<Task> sourceTasks) {
		List<Task> out = new ArrayList<>();
		for (Task task : sourceTasks) {
			if (task.getDeletedAt() != null
					|| task.getStatus() == TaskStatus.DONE
					|| JiraFailoverPreviewService.isSubtask(task)
					|| "SUBTASK".equals(task.getIssueTypeLevel())
					|| "EPIC".equals(task.getIssueTypeLevel())
					|| "ABOVE_EPIC".equals(task.getIssueTypeLevel())
					|| failoverItems.existsOutboundClaim(task.getId())
					|| failoverItems.existsSuccessfullySuperseded(task.getId())) {
				continue;
			}
			out.add(task);
		}
		out.sort(Comparator.comparing(Task::getDueDate, Comparator.nullsLast(Comparator.naturalOrder())));
		return out;
	}

	/** The target project's ordinary work type: "Task" when present, else any standard-level type. */
	static Optional<String> standardIssueType(List<IssueTypeOption> types) {
		List<IssueTypeOption> standard = types.stream()
				.filter(type -> !type.subtask() && (type.hierarchyLevel() == null || type.hierarchyLevel() == 0))
				.toList();
		return standard.stream()
				.filter(type -> type.name() != null && type.name().trim().toLowerCase(Locale.ROOT).equals("task"))
				.findFirst()
				.or(() -> standard.stream().filter(type -> type.hierarchyLevel() != null).findFirst())
				.or(() -> standard.stream().findFirst())
				.map(IssueTypeOption::id);
	}

	private Result failed(List<TeamMember> team, JiraIntegration source, JiraIntegration target, String reason) {
		leaderUserId(team).ifPresent(leader -> notify(leader, "Không tự chuyển được task khỏi site Jira " + label(source),
				"Site Jira " + label(source) + " không phản hồi nhưng SAGA chưa tự chuyển được task sang site " + label(target)
						+ ". Trưởng nhóm hãy dùng chức năng chuyển nguồn Jira để chuyển bằng tay.",
				"jira-auto-failover:" + source.getId() + ":failed:" + reason));
		return new Result(Outcome.FAILED, reason, 0);
	}

	private static Result skipped(String reason) {
		return new Result(Outcome.SKIPPED, reason, 0);
	}

	private List<TeamMember> teamMembers(UUID projectId) {
		return teams.findByProject_Id(projectId)
				.map(team -> members.findFetchedByTeam_Id(team.getId()).stream()
						.filter(member -> member.getCourseEnrollment() != null
								&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
								&& member.getCourseEnrollment().getStudentProfile() != null
								&& member.getCourseEnrollment().getStudentProfile().getUserAccount() != null)
						.toList())
				.orElse(List.of());
	}

	private static Optional<UUID> leaderUserId(List<TeamMember> team) {
		return team.stream()
				.filter(member -> member.getRoleInTeam() == RoleInTeam.LEADER)
				.map(member -> member.getCourseEnrollment().getStudentProfile().getUserAccount().getId())
				.findFirst();
	}

	private void notifyTeam(List<TeamMember> team, UUID sourceId, String title, String message, String step) {
		for (TeamMember member : team) {
			UUID userId = member.getCourseEnrollment().getStudentProfile().getUserAccount().getId();
			notify(userId, title, message, "jira-auto-failover:" + sourceId + ":" + step + ":" + userId);
		}
	}

	private void notify(UUID userId, String title, String message, String eventKey) {
		try {
			notifications.createNotification(userId, NotificationType.INTEGRATION, title, message, null, eventKey);
		} catch (RuntimeException ex) {
			log.warn("jira auto failover notification failed type={}", ex.getClass().getSimpleName());
		}
	}

	private static String label(JiraIntegration integration) {
		if (integration.getProjectKey() != null && !integration.getProjectKey().isBlank()) {
			String site = integration.getSiteName() == null || integration.getSiteName().isBlank() ? "" : " (" + integration.getSiteName() + ")";
			return integration.getProjectKey() + site;
		}
		return integration.getName() == null ? "Jira" : integration.getName();
	}
}

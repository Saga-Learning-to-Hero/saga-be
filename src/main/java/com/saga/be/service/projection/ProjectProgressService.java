package com.saga.be.service.projection;

import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.project.ProjectMemberProgressResponse;
import com.saga.be.dto.project.ProjectProgressResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.sync.ProjectManualSyncService;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only factual aggregation of a project's own Task/Sprint/Commit/evidence projections —
 * never a live Jira/GitHub call, never a grading/contribution formula. Authorization uses
 * {@link ProjectDataAuthorization#requireLecturerOrTeamLeader}: ADMIN denied (same policy as
 * {@code requireReader}), LECTURER only if assigned to the owning course, STUDENT only if the
 * ACTIVE Team Leader of this project — deliberately narrower than {@code requireReader} (which
 * also allows an ordinary ACTIVE MEMBER for the raw Task/Commit/Sprint lists), since the
 * aggregated analytics view is a distinct, more sensitive product surface that ordinary members
 * must not see. Raw Task/Commit reads elsewhere are untouched by this distinction.
 */
@Service
@Profile("!test")
public class ProjectProgressService {

	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final SprintRepository sprints;
	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final TaskWorkSessionRepository workSessions;
	private final ContributionConfirmationRepository confirmations;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final ProjectDataAuthorization authorization;
	private final ProjectIntegrationService integrations;
	private final ProjectManualSyncService manualSync;

	public ProjectProgressService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			SprintRepository sprints,
			TeamRepository teams,
			TeamMemberRepository members,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			ProjectDataAuthorization authorization,
			ProjectIntegrationService integrations,
			ProjectManualSyncService manualSync) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.sprints = sprints;
		this.teams = teams;
		this.members = members;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
		this.files = files;
		this.webLinks = webLinks;
		this.authorization = authorization;
		this.integrations = integrations;
		this.manualSync = manualSync;
	}

	@Transactional(readOnly = true)
	public ProjectProgressResponse getProjectProgress(UUID userId, UUID projectId) {
		authorization.requireLecturerOrTeamLeader(userId, projectId);
		Team team = requireTeam(projectId);

		List<Object[]> taskRows = tasks.countGroupedByAssigneeAndStatus(projectId);
		ProjectProgressResponse.TaskSummary taskSummary = summarizeTasks(taskRows);
		Map<UUID, long[]> tasksByStudent = tasksByStudent(taskRows);

		ProjectProgressResponse.SprintSummary currentSprint = currentSprint(projectId);

		long commitTotal = commits.countByRepo_Project_Id(projectId);
		long commitLinked = links.countDistinctLinkedCommitsByProject_Id(projectId);
		LocalDateTime lastCommitAt = commits.findMaxCommittedAtByProject_Id(projectId);
		ProjectProgressResponse.CommitSummary commitSummary =
				new ProjectProgressResponse.CommitSummary(commitTotal, commitLinked, lastCommitAt);

		ProjectProgressResponse.EvidenceSummary evidenceSummary = new ProjectProgressResponse.EvidenceSummary(
				workSessions.countByProject_Id(projectId),
				files.countByTask_Project_Id(projectId),
				webLinks.countByTask_Project_Id(projectId),
				confirmations.countByProject_Id(projectId));

		Map<UUID, Object[]> commitsByStudent = commitCountsByStudent(projectId);
		Map<UUID, Object[]> linkedByStudent = linkedCommitCountsByStudent(projectId);
		List<ProjectProgressResponse.MemberSummary> memberProgress =
				activeMembers(team).stream()
						.map(member -> toMemberSummary(member, tasksByStudent, commitsByStudent, linkedByStudent))
						.toList();

		ProjectProgressResponse.SyncSummary sync = syncSummary(userId, projectId);

		LocalDateTime lastTaskActivity = tasks.findMaxUpdatedAtByProject_Id(projectId);
		LocalDateTime lastActivityAt = latest(lastTaskActivity, lastCommitAt);

		return new ProjectProgressResponse(
				projectId,
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				taskSummary,
				currentSprint,
				commitSummary,
				evidenceSummary,
				memberProgress,
				sync,
				lastActivityAt);
	}

	@Transactional(readOnly = true)
	public ProjectMemberProgressResponse getMemberProgress(UUID userId, UUID projectId, UUID studentId) {
		authorization.requireLecturerOrTeamLeader(userId, projectId);
		Team team = requireTeam(projectId);
		TeamMember member = activeMembers(team).stream()
				.filter(row -> row.getCourseEnrollment().getStudentProfile().getId().equals(studentId))
				.findFirst()
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Student is not an ACTIVE member of this project's team."));
		UUID userAccountId =
				member.getCourseEnrollment().getStudentProfile().getUserAccount().getId();

		List<Task> assigned = tasks.findActiveFetchedByProject_IdAndAssigneeStudent_Id(projectId, studentId);
		long completed = assigned.stream().filter(t -> t.getStatus() == TaskStatus.DONE).count();
		List<ProjectMemberProgressResponse.AssignedTask> assignedTasks = assigned.stream()
				.map(t -> new ProjectMemberProgressResponse.AssignedTask(
						t.getId(),
						t.getExternalKey(),
						t.getTitle(),
						t.getStatus() == null ? null : t.getStatus().name(),
						t.getExternalUpdatedAt()))
				.toList();
		ProjectProgressResponse.TaskAttribution taskSummary =
				new ProjectProgressResponse.TaskAttribution(assigned.size(), completed, assigned.size() - completed);

		Map<UUID, Object[]> commitsByStudent = commitCountsByStudent(projectId);
		Map<UUID, Object[]> linkedByStudent = linkedCommitCountsByStudent(projectId);
		ProjectProgressResponse.CommitAttribution commitSummary =
				toCommitAttribution(studentId, commitsByStudent, linkedByStudent);

		ProjectMemberProgressResponse.EvidenceAttribution evidenceSummary =
				new ProjectMemberProgressResponse.EvidenceAttribution(
						workSessions.countByProject_IdAndUser_Id(projectId, userAccountId),
						files.countByTask_Project_IdAndCreatedBy_Id(projectId, userAccountId),
						webLinks.countByTask_Project_IdAndCreatedBy_Id(projectId, userAccountId),
						confirmations.countByProject_IdAndUser_Id(projectId, userAccountId));

		return new ProjectMemberProgressResponse(
				studentId,
				userAccountId,
				fullName(member),
				member.getCourseEnrollment().getStudentProfile().getStudentCode(),
				member.getRoleInTeam() == null ? null : member.getRoleInTeam().name(),
				taskSummary,
				assignedTasks,
				commitSummary,
				evidenceSummary);
	}

	private Team requireTeam(UUID projectId) {
		return teams.findByProject_Id(projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Team was not found for this project."));
	}

	private List<TeamMember> activeMembers(Team team) {
		return members.findFetchedByTeam_Id(team.getId()).stream()
				.filter(member -> member.getCourseEnrollment() != null
						&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE)
				.toList();
	}

	private static ProjectProgressResponse.TaskSummary summarizeTasks(List<Object[]> rows) {
		long total = 0;
		long todo = 0;
		long inProgress = 0;
		long inReview = 0;
		long done = 0;
		long blocked = 0;
		for (Object[] row : rows) {
			TaskStatus status = (TaskStatus) row[1];
			long count = (Long) row[2];
			total += count;
			if (status == null) {
				continue;
			}
			switch (status) {
				case TODO -> todo += count;
				case IN_PROGRESS -> inProgress += count;
				case IN_REVIEW -> inReview += count;
				case DONE -> done += count;
				case BLOCKED -> blocked += count;
			}
		}
		Double completionPercent = total == 0 ? null : (done * 100.0) / total;
		return new ProjectProgressResponse.TaskSummary(total, todo, inProgress, inReview, done, blocked, completionPercent);
	}

	/** studentId -> {assignedCount, completedCount}, excluding unassigned (studentId == null) rows. */
	private static Map<UUID, long[]> tasksByStudent(List<Object[]> rows) {
		Map<UUID, long[]> out = new HashMap<>();
		for (Object[] row : rows) {
			UUID studentId = (UUID) row[0];
			if (studentId == null) {
				continue;
			}
			TaskStatus status = (TaskStatus) row[1];
			long count = (Long) row[2];
			long[] bucket = out.computeIfAbsent(studentId, id -> new long[2]);
			bucket[0] += count;
			if (status == TaskStatus.DONE) {
				bucket[1] += count;
			}
		}
		return out;
	}

	private Map<UUID, Object[]> commitCountsByStudent(UUID projectId) {
		Map<UUID, Object[]> out = new HashMap<>();
		for (Object[] row : commits.countAndMaxCommittedAtGroupedByAuthorStudent(projectId)) {
			out.put((UUID) row[0], row);
		}
		return out;
	}

	private Map<UUID, Object[]> linkedCommitCountsByStudent(UUID projectId) {
		Map<UUID, Object[]> out = new HashMap<>();
		for (Object[] row : links.countLinkedCommitsAndTasksGroupedByAuthorStudent(projectId)) {
			out.put((UUID) row[0], row);
		}
		return out;
	}

	private ProjectProgressResponse.MemberSummary toMemberSummary(
			TeamMember member,
			Map<UUID, long[]> tasksByStudent,
			Map<UUID, Object[]> commitsByStudent,
			Map<UUID, Object[]> linkedByStudent) {
		UUID studentId = member.getCourseEnrollment().getStudentProfile().getId();
		long[] taskCounts = tasksByStudent.getOrDefault(studentId, new long[2]);
		ProjectProgressResponse.TaskAttribution taskSummary =
				new ProjectProgressResponse.TaskAttribution(taskCounts[0], taskCounts[1], taskCounts[0] - taskCounts[1]);
		ProjectProgressResponse.CommitAttribution commitSummary =
				toCommitAttribution(studentId, commitsByStudent, linkedByStudent);
		return new ProjectProgressResponse.MemberSummary(
				studentId,
				member.getCourseEnrollment().getStudentProfile().getUserAccount().getId(),
				fullName(member),
				member.getCourseEnrollment().getStudentProfile().getStudentCode(),
				member.getRoleInTeam() == null ? null : member.getRoleInTeam().name(),
				taskSummary,
				commitSummary);
	}

	private static ProjectProgressResponse.CommitAttribution toCommitAttribution(
			UUID studentId, Map<UUID, Object[]> commitsByStudent, Map<UUID, Object[]> linkedByStudent) {
		Object[] commitRow = commitsByStudent.get(studentId);
		long total = commitRow == null ? 0 : (Long) commitRow[1];
		LocalDateTime lastCommitAt = commitRow == null ? null : (LocalDateTime) commitRow[2];
		Object[] linkedRow = linkedByStudent.get(studentId);
		long linked = linkedRow == null ? 0 : (Long) linkedRow[1];
		long tasksWithLinkedCommits = linkedRow == null ? 0 : (Long) linkedRow[2];
		return new ProjectProgressResponse.CommitAttribution(total, linked, tasksWithLinkedCommits, lastCommitAt);
	}

	private static String fullName(TeamMember member) {
		return member.getCourseEnrollment().getStudentProfile().getUserAccount().getFullName();
	}

	/**
	 * The sprint whose raw Jira {@code state} is {@code "active"}. If more than one row matches
	 * (a provider data inconsistency this codebase has no existing rule for), the most recently
	 * started one is used deterministically — the same ordering {@code findActiveByProject_Id}
	 * already applies — rather than picking arbitrarily.
	 *
	 * <p><b>Last-known projected data, not a live Jira read:</b> this reads only the local
	 * {@code sprint} projection, never Jira. If the project's Jira integration is later
	 * {@code REVOKED}, any sprint row last synced with {@code state="active"} stays exactly as it
	 * was and is still returned here — {@code sync.jiraStatus} in the same response is the signal
	 * that this sprint (and the rest of the Jira-sourced data in this response) is stale/last-known
	 * rather than currently live. Callers must not treat a non-null {@code currentSprint} as proof
	 * Jira is currently connected.
	 */
	private ProjectProgressResponse.SprintSummary currentSprint(UUID projectId) {
		Sprint active = sprints.findActiveByProject_Id(projectId).stream()
				.filter(s -> s.getState() != null && s.getState().equalsIgnoreCase("active"))
				.findFirst()
				.orElse(null);
		if (active == null) {
			return null;
		}
		long totalTasks = tasks.countByProject_IdAndSprint_IdAndDeletedAtIsNull(projectId, active.getId());
		long completedTasks = tasks.countByProject_IdAndSprint_IdAndStatusAndDeletedAtIsNull(
				projectId, active.getId(), TaskStatus.DONE);
		return new ProjectProgressResponse.SprintSummary(
				active.getId(),
				active.getExternalSprintId(),
				active.getName(),
				active.getState(),
				active.getStartDate(),
				active.getEndDate(),
				totalTasks,
				completedTasks);
	}

	/** DB-projected connection/sync status only — no live Jira/GitHub HTTP call on this read path. */
	private ProjectProgressResponse.SyncSummary syncSummary(UUID userId, UUID projectId) {
		ProjectIntegrationsResponse integrationStatus = integrations.summary(userId, projectId);
		String jiraStatus = integrationStatus.jira() == null ? null : integrationStatus.jira().status();
		String githubStatus = integrationStatus.github() == null ? null : integrationStatus.github().status();
		String jiraSyncStatus = null;
		LocalDateTime jiraLastSyncAt = null;
		String githubSyncStatus = null;
		LocalDateTime githubLastSyncAt = null;
		for (ProjectSyncStatusResponse row : manualSync.latestStatus(userId, projectId)) {
			if ("JIRA".equals(row.provider())) {
				jiraSyncStatus = row.status();
				jiraLastSyncAt = row.completedAt() != null ? row.completedAt() : row.startedAt();
			} else if ("GITHUB".equals(row.provider())) {
				githubSyncStatus = row.status();
				githubLastSyncAt = row.completedAt() != null ? row.completedAt() : row.startedAt();
			}
		}
		return new ProjectProgressResponse.SyncSummary(
				jiraStatus, githubStatus, jiraSyncStatus, jiraLastSyncAt, githubSyncStatus, githubLastSyncAt);
	}

	private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isAfter(b) ? a : b;
	}
}

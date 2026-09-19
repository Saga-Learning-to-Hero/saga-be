package com.saga.be.service.student;

import com.saga.be.dto.student.dashboard.StudentDashboardActiveTaskResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardCommitMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardCourseResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardGithubIntegrationResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardIntegrationsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardJiraIntegrationResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardRecentCommitResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardSprintResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardStudentResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardTaskMetricsResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardTeamResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardWeeklyCommitResponse;
import com.saga.be.config.DashboardProperties;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentDashboardAnomalyCandidateRow;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier;
import com.saga.be.service.contribution.TaskLabelParser;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase A+B1+B2 student personal dashboard. Course-scoped identity/team/project/integrations/current
 * sprint plus personal task/commit metrics, previews, and last-3 ISO weekly commits. Ordinary MEMBER
 * is allowed; this path does not reuse lecturer/leader project-data gates or contribution evaluation.
 */
@Service
@Profile("!test")
public class StudentDashboardService {

	static final int ACTIVE_TASK_PREVIEW_LIMIT = 10;
	static final int RECENT_COMMIT_LIMIT = 5;
	static final int WEEKLY_COMMIT_WEEKS = 3;

	private final CourseEnrollmentRepository enrollments;
	private final TeamMemberRepository members;
	private final JiraIntegrationRepository jiraIntegrations;
	private final GitRepoRepository repos;
	private final SprintRepository sprints;
	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository commitLinks;
	private final Clock clock;

	public StudentDashboardService(
			CourseEnrollmentRepository enrollments,
			TeamMemberRepository members,
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository repos,
			SprintRepository sprints,
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository commitLinks,
			DashboardProperties dashboard) {
		this(enrollments, members, jiraIntegrations, repos, sprints, tasks, commits, commitLinks, dashboard.clock());
	}

	StudentDashboardService(
			CourseEnrollmentRepository enrollments,
			TeamMemberRepository members,
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository repos,
			SprintRepository sprints,
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository commitLinks,
			Clock clock) {
		this.enrollments = enrollments;
		this.members = members;
		this.jiraIntegrations = jiraIntegrations;
		this.repos = repos;
		this.sprints = sprints;
		this.tasks = tasks;
		this.commits = commits;
		this.commitLinks = commitLinks;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public StudentDashboardResponse get(UUID userId, UUID courseId) {
		CourseEnrollment enrollment =
				enrollments.findFetchedActiveByUserAndCourse(userId, courseId).orElse(null);
		if (enrollment == null) {
			throw forbidden();
		}
		StudentProfile profile = enrollment.getStudentProfile();
		UserAccount account = profile.getUserAccount();
		Course course = enrollment.getCourse();
		TeamMember membership = members.findFetchedByCourseEnrollment_Id(enrollment.getId()).orElse(null);
		Team team = membership == null ? null : membership.getTeam();

		StudentDashboardStudentResponse student = new StudentDashboardStudentResponse(
				profile.getId(),
				account.getId(),
				profile.getStudentCode(),
				account.getFullName(),
				account.getAvatarUrl(),
				membership == null || membership.getRoleInTeam() == null
						? null
						: membership.getRoleInTeam().name());
		StudentDashboardCourseResponse courseDto = toCourse(course);

		if (team == null) {
			return new StudentDashboardResponse(
					student, courseDto, null, null, null, null, List.of(), List.of(), List.of());
		}

		Project project = team.getProject();
		StudentDashboardTeamResponse teamDto = new StudentDashboardTeamResponse(
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				project == null ? null : project.getId(),
				project == null ? null : project.getName(),
				members.countActiveByTeam_Id(team.getId()));
		if (project == null) {
			return new StudentDashboardResponse(
					student, courseDto, teamDto, null, null, null, List.of(), List.of(), List.of());
		}

		UUID projectId = project.getId();
		UUID studentId = profile.getId();
		return new StudentDashboardResponse(
				student,
				courseDto,
				teamDto,
				new StudentDashboardIntegrationsResponse(jiraSummary(projectId), githubSummary(projectId)),
				currentSprint(projectId),
				new StudentDashboardMetricsResponse(taskMetrics(projectId, studentId), commitMetrics(projectId, studentId)),
				activeTasks(projectId, studentId),
				recentCommits(projectId, studentId),
				weeklyCommits(projectId, studentId));
	}

	private static StudentDashboardCourseResponse toCourse(Course course) {
		Subject subject = course.getSubject();
		Semester semester = course.getSemester();
		return new StudentDashboardCourseResponse(
				course.getId(),
				course.getCourseCode(),
				subject == null ? null : subject.getSubjectCode(),
				subject == null ? null : subject.getName(),
				semester == null ? null : semester.getCode());
	}

	private StudentDashboardJiraIntegrationResponse jiraSummary(UUID projectId) {
		JiraIntegration row = jiraIntegrations.findByProject_Id(projectId).orElse(null);
		if (row == null) {
			return new StudentDashboardJiraIntegrationResponse(false, null, null, null);
		}
		boolean connected = row.getConnectionStatus() == IntegrationStatus.ACTIVE;
		String status = row.getConnectionStatus() == null ? null : row.getConnectionStatus().name();
		return new StudentDashboardJiraIntegrationResponse(
				connected, row.getProjectKey(), status, row.getLastSuccessfulSyncAt());
	}

	/**
	 * Live GitHub = at least one ACTIVE repo. {@code status} is a dashboard projection string, not
	 * the persistence enum: ACTIVE if any live repo exists; otherwise the single shared non-ACTIVE
	 * status; {@code MIXED} when non-ACTIVE statuses differ; null when no rows.
	 */
	private StudentDashboardGithubIntegrationResponse githubSummary(UUID projectId) {
		long activeCount = 0;
		boolean anyRow = false;
		LocalDateTime lastSyncedAt = null;
		Set<IntegrationStatus> nonActive = new LinkedHashSet<>();
		for (Object[] row : repos.countAndMaxLastSyncedAtGroupedByStatus(projectId)) {
			IntegrationStatus status = (IntegrationStatus) row[0];
			long count = ((Number) row[1]).longValue();
			if (count <= 0) {
				continue;
			}
			anyRow = true;
			if (status == IntegrationStatus.ACTIVE) {
				activeCount = count;
				lastSyncedAt = (LocalDateTime) row[2];
			} else {
				nonActive.add(status);
			}
		}
		boolean connected = activeCount > 0;
		return new StudentDashboardGithubIntegrationResponse(
				connected, activeCount, githubAggregateStatus(connected, anyRow, nonActive), lastSyncedAt);
	}

	private static String githubAggregateStatus(
			boolean connected, boolean anyRow, Set<IntegrationStatus> nonActive) {
		if (connected) {
			return IntegrationStatus.ACTIVE.name();
		}
		if (!anyRow) {
			return null;
		}
		if (nonActive.size() == 1) {
			IntegrationStatus only = nonActive.iterator().next();
			return only == null ? "MIXED" : only.name();
		}
		return "MIXED";
	}

	/**
	 * Same selector as {@code ProjectProgressService#currentSprint}: non-deleted rows whose raw
	 * Jira {@code state} is {@code "active"} ignore-case; {@code findActiveByProject_Id} order
	 * (coalesce(startDate, createdAt) DESC) is preserved, first match wins.
	 */
	private StudentDashboardSprintResponse currentSprint(UUID projectId) {
		Sprint active = sprints.findActiveByProject_Id(projectId).stream()
				.filter(sprint -> sprint.getState() != null && sprint.getState().equalsIgnoreCase("active"))
				.findFirst()
				.orElse(null);
		if (active == null) {
			return null;
		}
		long total = 0;
		long completed = 0;
		for (Object[] row : tasks.countGroupedByStatusForProjectAndSprint(projectId, active.getId())) {
			TaskStatus status = (TaskStatus) row[0];
			long count = ((Number) row[1]).longValue();
			total += count;
			if (status == TaskStatus.DONE) {
				completed += count;
			}
		}
		Double completionPercent = total == 0 ? null : (completed * 100.0) / total;
		return new StudentDashboardSprintResponse(
				active.getId(),
				active.getExternalSprintId(),
				active.getName(),
				active.getState(),
				active.getStartDate(),
				active.getEndDate(),
				total,
				completed,
				completionPercent);
	}

	private StudentDashboardTaskMetricsResponse taskMetrics(UUID projectId, UUID studentId) {
		long todo = 0;
		long inProgress = 0;
		long inReview = 0;
		long done = 0;
		long blocked = 0;
		long totalStoryPoints = 0;
		long completedStoryPoints = 0;
		for (Object[] row : tasks.countStatusAndStoryPointsForAssignee(projectId, studentId)) {
			TaskStatus status = (TaskStatus) row[0];
			long count = ((Number) row[1]).longValue();
			long points = ((Number) row[2]).longValue();
			totalStoryPoints += points;
			if (status == TaskStatus.TODO) {
				todo += count;
			} else if (status == TaskStatus.IN_PROGRESS) {
				inProgress += count;
			} else if (status == TaskStatus.IN_REVIEW) {
				inReview += count;
			} else if (status == TaskStatus.DONE) {
				done += count;
				completedStoryPoints += points;
			} else if (status == TaskStatus.BLOCKED) {
				blocked += count;
			}
		}
		long totalAssigned = todo + inProgress + inReview + done + blocked;
		Double completionPercent = totalAssigned == 0 ? null : (done * 100.0) / totalAssigned;
		return new StudentDashboardTaskMetricsResponse(
				totalAssigned,
				todo,
				inProgress,
				inReview,
				done,
				blocked,
				completionPercent,
				totalStoryPoints,
				completedStoryPoints);
	}

	private StudentDashboardCommitMetricsResponse commitMetrics(UUID projectId, UUID studentId) {
		List<Object[]> rows = commits.countAndMaxCommittedAtByProjectAndAuthor(projectId, studentId);
		Object[] row = rows == null || rows.isEmpty() ? null : rows.getFirst();
		long total = row == null || row[0] == null ? 0L : ((Number) row[0]).longValue();
		LocalDateTime lastAt = row == null ? null : (LocalDateTime) row[1];
		long linked = total == 0 ? 0L : commitLinks.countDistinctLinkedAuthoredV23(projectId, studentId);
		long unlinked = total - linked;
		Double traceability = total == 0 ? null : (linked * 100.0) / total;
		return new StudentDashboardCommitMetricsResponse(total, linked, unlinked, traceability, lastAt);
	}

	private List<StudentDashboardActiveTaskResponse> activeTasks(UUID projectId, UUID studentId) {
		List<AttentionRow> anomalies = tasks.findDoneWithoutV23EvidenceCandidates(projectId, studentId).stream()
				.filter(row -> isCodeOrTest(row.labelsJson()))
				.map(StudentDashboardService::toAnomalyRow)
				.toList();
		List<AttentionRow> open = tasks
				.findAttentionNonDoneByProjectAndAssignee(
						projectId, studentId, PageRequest.of(0, ACTIVE_TASK_PREVIEW_LIMIT))
				.stream()
				.map(StudentDashboardService::toOpenRow)
				.toList();
		Map<UUID, AttentionRow> unique = new LinkedHashMap<>();
		for (AttentionRow row : anomalies) {
			unique.put(row.id(), row);
		}
		for (AttentionRow row : open) {
			unique.putIfAbsent(row.id(), row);
		}
		List<AttentionRow> preview = unique.values().stream()
				.sorted(ATTENTION_ORDER)
				.limit(ACTIVE_TASK_PREVIEW_LIMIT)
				.toList();
		if (preview.isEmpty()) {
			return List.of();
		}
		Map<UUID, long[]> counts = linkCounts(preview.stream().map(AttentionRow::id).toList());
		List<StudentDashboardActiveTaskResponse> rows = new ArrayList<>(preview.size());
		for (AttentionRow row : preview) {
			long[] pair = counts.getOrDefault(row.id(), new long[] {0L, 0L});
			rows.add(new StudentDashboardActiveTaskResponse(
					row.id(),
					row.externalKey(),
					row.title(),
					row.status() == null ? null : row.status().name(),
					row.priority() == null ? null : row.priority().name(),
					row.storyPoints(),
					row.dueDate(),
					pair[0],
					pair[1],
					row.anomaly()));
		}
		return rows;
	}

	private Map<UUID, long[]> linkCounts(List<UUID> taskIds) {
		Map<UUID, long[]> counts = new HashMap<>();
		for (Object[] row : commitLinks.countRawAndV23LinksByTaskIds(taskIds)) {
			UUID taskId = (UUID) row[0];
			long raw = row[1] == null ? 0L : ((Number) row[1]).longValue();
			long v23 = row[2] == null ? 0L : ((Number) row[2]).longValue();
			counts.put(taskId, new long[] {raw, v23});
		}
		return counts;
	}

	private List<StudentDashboardRecentCommitResponse> recentCommits(UUID projectId, UUID studentId) {
		List<GitCommit> rows = commits.findRecentAuthoredV23ByProject(
				projectId, studentId, PageRequest.of(0, RECENT_COMMIT_LIMIT));
		if (rows.isEmpty()) {
			return List.of();
		}
		Map<UUID, List<String>> keysByCommit = new LinkedHashMap<>();
		for (GitCommit commit : rows) {
			keysByCommit.put(commit.getId(), new ArrayList<>());
		}
		Set<String> seen = new LinkedHashSet<>();
		for (Object[] row : commitLinks.findExternalKeysByCommitIds(keysByCommit.keySet())) {
			UUID commitId = (UUID) row[0];
			String key = (String) row[1];
			if (key == null || key.isBlank()) {
				continue;
			}
			String dedupe = commitId + "\0" + key;
			if (!seen.add(dedupe)) {
				continue;
			}
			keysByCommit.computeIfAbsent(commitId, ignored -> new ArrayList<>()).add(key);
		}
		List<StudentDashboardRecentCommitResponse> out = new ArrayList<>(rows.size());
		for (GitCommit commit : rows) {
			String sha = commit.getShaHash();
			String repoName = commit.getRepo() == null ? null : commit.getRepo().getFullName();
			out.add(new StudentDashboardRecentCommitResponse(
					sha,
					shortSha(sha),
					commit.getMessage(),
					repoName,
					commit.getCommittedAt(),
					List.copyOf(keysByCommit.getOrDefault(commit.getId(), List.of()))));
		}
		return out;
	}

	/**
	 * Last 3 ISO weeks by stored {@code committedAt} wall-clock only. Offsets were stripped at
	 * persist time, so this is not Instant-normalized.
	 */
	private List<StudentDashboardWeeklyCommitResponse> weeklyCommits(UUID projectId, UUID studentId) {
		LocalDate today = LocalDate.now(clock);
		LocalDate currentWeekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
		LocalDateTime week0Start = currentWeekStart.minusWeeks(2).atStartOfDay();
		LocalDateTime week1Start = currentWeekStart.minusWeeks(1).atStartOfDay();
		LocalDateTime week2Start = currentWeekStart.atStartOfDay();
		LocalDateTime week3End = currentWeekStart.plusWeeks(1).atStartOfDay();
		long[] counts = new long[WEEKLY_COMMIT_WEEKS];
		for (Object[] row : commits.findWeeklyCommittedAtByProjectAndAuthor(projectId, studentId, week0Start, week3End)) {
			LocalDateTime at = row == null || row.length < 2 ? null : (LocalDateTime) row[1];
			if (at == null) {
				continue;
			}
			if (!at.isBefore(week0Start) && at.isBefore(week1Start)) {
				counts[0]++;
			} else if (!at.isBefore(week1Start) && at.isBefore(week2Start)) {
				counts[1]++;
			} else if (!at.isBefore(week2Start) && at.isBefore(week3End)) {
				counts[2]++;
			}
		}
		return List.of(
				point(currentWeekStart.minusWeeks(2), counts[0]),
				point(currentWeekStart.minusWeeks(1), counts[1]),
				point(currentWeekStart, counts[2]));
	}

	private static StudentDashboardWeeklyCommitResponse point(LocalDate monday, long commits) {
		return new StudentDashboardWeeklyCommitResponse(monday, monday.plusDays(6), commits);
	}

	private static boolean isCodeOrTest(String labelsJson) {
		ContributionCriterion criterion = ReservedContributionMarkerClassifier.toCriterion(
				ReservedContributionMarkerClassifier.classify(TaskLabelParser.parse(labelsJson)));
		return criterion == ContributionCriterion.CODE || criterion == ContributionCriterion.TEST;
	}

	private static AttentionRow toAnomalyRow(StudentDashboardAnomalyCandidateRow row) {
		return new AttentionRow(
				row.id(),
				row.externalKey(),
				row.title(),
				row.status(),
				row.priority(),
				row.storyPoint(),
				row.dueDate(),
				true);
	}

	private static AttentionRow toOpenRow(Task task) {
		return new AttentionRow(
				task.getId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus(),
				task.getPriority(),
				task.getStoryPoint(),
				task.getDueDate(),
				false);
	}

	private static final Comparator<AttentionRow> ATTENTION_ORDER =
			Comparator.comparing((AttentionRow row) -> row.anomaly() ? 0 : 1)
					.thenComparing((AttentionRow row) -> row.dueDate() == null ? 1 : 0)
					.thenComparing(AttentionRow::dueDate, Comparator.nullsLast(Comparator.naturalOrder()))
					.thenComparing(row -> -priorityRank(row.priority()))
					.thenComparing(AttentionRow::id);

	private record AttentionRow(
			UUID id,
			String externalKey,
			String title,
			TaskStatus status,
			Priority priority,
			Integer storyPoints,
			LocalDateTime dueDate,
			boolean anomaly) {}

	static int priorityRank(Priority priority) {
		if (priority == null) {
			return 0;
		}
		return switch (priority) {
			case HIGHEST -> 5;
			case HIGH -> 4;
			case MEDIUM -> 3;
			case LOW -> 2;
			case LOWEST -> 1;
		};
	}

	static String shortSha(String sha) {
		if (sha == null) {
			return null;
		}
		return sha.length() >= 7 ? sha.substring(0, 7) : sha;
	}

	private static AcademicException forbidden() {
		return new AcademicException(
				AcademicErrorCode.STUDENT_COURSE_FORBIDDEN,
				HttpStatus.FORBIDDEN,
				"Student is not ACTIVE in this course.");
	}
}

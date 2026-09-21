package com.saga.be.service.lecturer;

import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Activity;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.ActivityDay;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Configuration;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.CurrentSprint;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.PeerReview;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.PreviousSprintComparison;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Progress;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Summary;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Sync;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.TaskStatusTotals;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.TeamCard;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.Traceability;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.ContributionConfigMode;
import com.saga.be.entity.enums.DashboardScope;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.LecturerDashboardRiskLevel;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.ProjectGroupWeightConfigRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.lecturer.dashboard.LecturerDashboardRiskEngine;
import com.saga.be.service.lecturer.dashboard.LecturerDashboardRiskEngine.Signals;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-request lecturer course dashboard. Bounded query count. Does not read
 * {@code contribution_override} and does not call contribution evaluation.
 */
@Service
@Profile("!test")
public class LecturerCourseDashboardService {

	private static final int MAX_SERIES_DAYS = 90;

	private final LecturerCourseAuthorization authorization;
	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final CourseEnrollmentRepository enrollments;
	private final SprintRepository sprints;
	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final PeerReviewRepository peerReviews;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final JiraIntegrationRepository jiraIntegrations;
	private final GitRepoRepository gitRepos;
	private final SyncJobLogRepository syncJobs;
	private final ProjectGroupWeightConfigRepository groupWeights;
	private final Clock clock;

	@Autowired
	public LecturerCourseDashboardService(
			LecturerCourseAuthorization authorization,
			TeamRepository teams,
			TeamMemberRepository members,
			CourseEnrollmentRepository enrollments,
			SprintRepository sprints,
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			PeerReviewRepository peerReviews,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository gitRepos,
			SyncJobLogRepository syncJobs,
			ProjectGroupWeightConfigRepository groupWeights) {
		this(
				authorization,
				teams,
				members,
				enrollments,
				sprints,
				tasks,
				commits,
				links,
				peerReviews,
				files,
				webLinks,
				jiraIntegrations,
				gitRepos,
				syncJobs,
				groupWeights,
				Clock.systemUTC());
	}

	LecturerCourseDashboardService(
			LecturerCourseAuthorization authorization,
			TeamRepository teams,
			TeamMemberRepository members,
			CourseEnrollmentRepository enrollments,
			SprintRepository sprints,
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			PeerReviewRepository peerReviews,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			JiraIntegrationRepository jiraIntegrations,
			GitRepoRepository gitRepos,
			SyncJobLogRepository syncJobs,
			ProjectGroupWeightConfigRepository groupWeights,
			Clock clock) {
		this.authorization = authorization;
		this.teams = teams;
		this.members = members;
		this.enrollments = enrollments;
		this.sprints = sprints;
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.peerReviews = peerReviews;
		this.files = files;
		this.webLinks = webLinks;
		this.jiraIntegrations = jiraIntegrations;
		this.gitRepos = gitRepos;
		this.syncJobs = syncJobs;
		this.groupWeights = groupWeights;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public LecturerCourseDashboardResponse getDashboard(UserAccount actor, UUID courseId, String scopeRaw) {
		requireNotAdmin(actor);
		DashboardScope scope = parseScope(scopeRaw);
		Course course = authorization.requireCourse(actor, courseId);
		Instant nowInstant = Instant.now(clock);
		LocalDateTime now = LocalDateTime.ofInstant(nowInstant, ZoneOffset.UTC);

		List<Team> teamRows = teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId);
		List<TeamMember> memberRows = members.findFetchedByCourse_Id(courseId);
		List<CourseEnrollment> activeEnrollments =
				enrollments.findByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE);

		Map<UUID, List<TeamMember>> membersByTeam = new HashMap<>();
		Set<UUID> assignedEnrollmentIds = new HashSet<>();
		for (TeamMember member : memberRows) {
			if (member.getCourseEnrollment() == null
					|| member.getCourseEnrollment().getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
				continue;
			}
			membersByTeam.computeIfAbsent(member.getTeam().getId(), id -> new ArrayList<>()).add(member);
			assignedEnrollmentIds.add(member.getCourseEnrollment().getId());
		}
		long unassigned = activeEnrollments.stream()
				.filter(row -> !assignedEnrollmentIds.contains(row.getId()))
				.count();

		List<UUID> projectIds = teamRows.stream()
				.map(Team::getProject)
				.filter(Objects::nonNull)
				.map(Project::getId)
				.toList();
		List<Sprint> sprintRows = projectIds.isEmpty() ? List.of() : sprints.findActiveByProjectIdIn(projectIds);
		Map<UUID, List<Sprint>> sprintsByProject = new HashMap<>();
		for (Sprint sprint : sprintRows) {
			UUID projectId = sprint.getJiraIntegration().getProject().getId();
			sprintsByProject.computeIfAbsent(projectId, id -> new ArrayList<>()).add(sprint);
		}
		Map<UUID, Sprint> currentByProject = new HashMap<>();
		for (Map.Entry<UUID, List<Sprint>> entry : sprintsByProject.entrySet()) {
			Sprint current = pickCurrent(entry.getValue());
			if (current != null) {
				currentByProject.put(entry.getKey(), current);
			}
		}

		Set<UUID> sprintIds = new LinkedHashSet<>();
		for (Sprint sprint : currentByProject.values()) {
			sprintIds.add(sprint.getId());
		}
		for (List<Sprint> list : sprintsByProject.values()) {
			Sprint previous = pickPrevious(list, pickCurrent(list));
			if (previous != null) {
				sprintIds.add(previous.getId());
			}
		}

		Map<UUID, long[]> tasksBySprint = taskBuckets(sprintIds);
		Map<UUID, Long> overdueBySprint = overdueBuckets(sprintIds, now);
		Map<UUID, Set<UUID>> doneTasksBySprint = doneTaskIds(sprintIds);
		Map<UUID, Set<UUID>> doneWithCommitBySprint = new HashMap<>();
		Map<UUID, Set<UUID>> linkedCommitsBySprint = new HashMap<>();
		if (!sprintIds.isEmpty()) {
			for (Object[] row : links.findLinkedCommitAndTaskIdsBySprintIds(sprintIds)) {
				UUID sprintId = (UUID) row[0];
				UUID commitId = (UUID) row[1];
				UUID taskId = (UUID) row[2];
				linkedCommitsBySprint.computeIfAbsent(sprintId, id -> new HashSet<>()).add(commitId);
				if (doneTasksBySprint.getOrDefault(sprintId, Set.of()).contains(taskId)) {
					doneWithCommitBySprint.computeIfAbsent(sprintId, id -> new HashSet<>()).add(taskId);
				}
			}
		}

		Map<UUID, List<TimedEvent>> eventsByProject = loadEvents(projectIds);
		Map<UUID, LocalDateTime> lastCommit = lastCommitByProject(projectIds);
		Map<UUID, LocalDateTime> lastTask = lastTaskByProject(projectIds);
		Map<UUID, String> jiraStatusByProject = jiraStatus(projectIds);
		Map<UUID, String> githubStatusByProject = githubStatus(projectIds);
		Map<UUID, JobSnapshot> jiraJobs = new HashMap<>();
		Map<UUID, JobSnapshot> githubJobs = new HashMap<>();
		indexJobs(projectIds, jiraJobs, githubJobs);
		Set<UUID> configuredGroupProjects = configuredGroupProjects(courseId);
		Map<UUID, List<ReviewRow>> reviewsBySprint = reviewsBySprint(sprintIds);
		attachPeerReviewEvents(eventsByProject, sprintsByProject, reviewsBySprint);

		List<TeamCard> cards = new ArrayList<>();
		long healthy = 0;
		long warning = 0;
		long critical = 0;
		long unknown = 0;
		long withoutProject = 0;
		long withoutSprint = 0;
		long syncFailure = 0;
		long[] classTotals = new long[6];

		ContributionConfigMode mode =
				course.getContributionConfigMode() == null
						? ContributionConfigMode.COURSE
						: course.getContributionConfigMode();

		for (Team team : teamRows) {
			TeamCard card = toCard(
					team,
					membersByTeam.getOrDefault(team.getId(), List.of()),
					currentByProject,
					sprintsByProject,
					tasksBySprint,
					overdueBySprint,
					doneTasksBySprint,
					doneWithCommitBySprint,
					linkedCommitsBySprint,
					eventsByProject,
					lastCommit,
					lastTask,
					jiraStatusByProject,
					githubStatusByProject,
					jiraJobs,
					githubJobs,
					configuredGroupProjects,
					reviewsBySprint,
					mode,
					now,
					nowInstant);
			cards.add(card);
			if (card.projectId() == null) {
				withoutProject++;
			}
			if (card.projectId() != null && card.currentSprint() == null) {
				withoutSprint++;
			}
			if (card.risk().level() == LecturerDashboardRiskLevel.HEALTHY) {
				healthy++;
			} else if (card.risk().level() == LecturerDashboardRiskLevel.WARNING) {
				warning++;
			} else if (card.risk().level() == LecturerDashboardRiskLevel.CRITICAL) {
				critical++;
			} else {
				unknown++;
			}
			if (isSyncFailure(card.sync())) {
				syncFailure++;
			}
			if (card.progress() != null) {
				classTotals[0] += card.progress().totalTasks();
				classTotals[1] += card.progress().todo();
				classTotals[2] += card.progress().inProgress();
				classTotals[3] += card.progress().inReview();
				classTotals[4] += card.progress().done();
				classTotals[5] += card.progress().blocked();
			}
		}

		long classOverdue = cards.stream().mapToLong(card -> card.progress() == null ? 0 : card.progress().overdue()).sum();
		TaskStatusTotals totals = new TaskStatusTotals(
				classTotals[0],
				classTotals[1],
				classTotals[2],
				classTotals[3],
				classTotals[4],
				classTotals[5],
				classOverdue,
				percent(classTotals[4], classTotals[0]));

		Summary summary = new Summary(
				activeEnrollments.size(),
				unassigned,
				teamRows.size(),
				healthy,
				warning,
				critical,
				unknown,
				withoutProject,
				withoutSprint,
				syncFailure);

		return new LecturerCourseDashboardResponse(
				course.getId(),
				course.getCourseCode(),
				course.getSubject() == null ? null : course.getSubject().getSubjectCode(),
				course.getSubject() == null ? null : course.getSubject().getName(),
				course.getAcademicClass() == null ? null : course.getAcademicClass().getClassCode(),
				course.getSemester() == null ? null : course.getSemester().getCode(),
				scope,
				nowInstant,
				LecturerDashboardRiskEngine.POLICY,
				summary,
				totals,
				cards);
	}

	private TeamCard toCard(
			Team team,
			List<TeamMember> roster,
			Map<UUID, Sprint> currentByProject,
			Map<UUID, List<Sprint>> sprintsByProject,
			Map<UUID, long[]> tasksBySprint,
			Map<UUID, Long> overdueBySprint,
			Map<UUID, Set<UUID>> doneTasksBySprint,
			Map<UUID, Set<UUID>> doneWithCommitBySprint,
			Map<UUID, Set<UUID>> linkedCommitsBySprint,
			Map<UUID, List<TimedEvent>> eventsByProject,
			Map<UUID, LocalDateTime> lastCommit,
			Map<UUID, LocalDateTime> lastTask,
			Map<UUID, String> jiraStatusByProject,
			Map<UUID, String> githubStatusByProject,
			Map<UUID, JobSnapshot> jiraJobs,
			Map<UUID, JobSnapshot> githubJobs,
			Set<UUID> configuredGroupProjects,
			Map<UUID, List<ReviewRow>> reviewsBySprint,
			ContributionConfigMode mode,
			LocalDateTime now,
			Instant nowInstant) {
		Project project = team.getProject();
		UUID projectId = project == null ? null : project.getId();
		int memberCount = roster.size();
		boolean groupConfigured = projectId != null && configuredGroupProjects.contains(projectId);
		boolean weightsConfigured = mode != ContributionConfigMode.PROJECT_GROUP || groupConfigured;
		Configuration configuration = new Configuration(mode, weightsConfigured);

		if (projectId == null) {
			return new TeamCard(
					team.getId(),
					team.getTeamNo() == null ? 0 : team.getTeamNo(),
					team.getName(),
					null,
					null,
					memberCount,
					null,
					null,
					null,
					null,
					null,
					null,
					configuration,
					null,
					LecturerDashboardRiskEngine.evaluate(new Signals(
							false,
							false,
							null,
							false,
							null,
							0,
							0,
							0,
							false,
							false,
							weightsConfigured,
							mode == ContributionConfigMode.PROJECT_GROUP,
							null,
							false,
							null,
							null,
							List.of())),
					null);
		}

		Sprint current = currentByProject.get(projectId);
		Sprint previous = pickPrevious(sprintsByProject.getOrDefault(projectId, List.of()), current);
		CurrentSprint currentDto = current == null ? null : toCurrentSprint(current, now);
		Progress progress = current == null
				? null
				: toProgress(current.getId(), currentDto, tasksBySprint, overdueBySprint);
		Traceability traceability = current == null
				? null
				: toTraceability(
						current.getId(),
						doneTasksBySprint,
						doneWithCommitBySprint,
						linkedCommitsBySprint,
						eventsByProject.getOrDefault(projectId, List.of()),
						current.getStartDate(),
						current.getEndDate());
		Activity activity = toActivity(
				projectId, current, lastCommit, lastTask, eventsByProject.getOrDefault(projectId, List.of()), now);
		PeerReview peer = current == null ? null : toPeer(current, roster, reviewsBySprint, nowInstant);
		Sync sync = toSync(projectId, jiraStatusByProject, githubStatusByProject, jiraJobs, githubJobs);
		PreviousSprintComparison comparison =
				previous == null
						? null
						: toPrevious(
								previous,
								current,
								tasksBySprint,
								doneTasksBySprint,
								doneWithCommitBySprint,
								linkedCommitsBySprint,
								eventsByProject.getOrDefault(projectId, List.of()),
								activity);

		Integer inactiveDays = activity == null ? null : activity.inactiveDays();
		boolean lastKnown = activity != null && activity.lastActivityAt() != null;
		Double gap = progress == null ? null : progress.scheduleGapPercentagePoints();
		long blocked = progress == null ? 0 : progress.blocked();
		long overdue = progress == null ? 0 : progress.overdue();
		long missingLink = traceability == null ? 0 : traceability.completedTasksWithoutCommit();
		boolean jiraFailed = sync != null && "FAILED".equals(sync.jiraSyncStatus());
		boolean githubFailed = sync != null && "FAILED".equals(sync.githubSyncStatus());
		boolean sprintEnded = current != null
				&& (isClosed(current)
						|| (current.getEndDate() != null && !now.isBefore(current.getEndDate())));
		Double elapsed = currentDto == null ? null : currentDto.elapsedPercent();
		List<UUID> pending = peer == null ? List.of() : peer.pendingStudentProfileIds();

		return new TeamCard(
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				projectId,
				project.getName(),
				memberCount,
				currentDto,
				progress,
				activity,
				traceability,
				peer,
				sync,
				configuration,
				comparison,
				LecturerDashboardRiskEngine.evaluate(new Signals(
						true,
						current != null,
						inactiveDays,
						lastKnown,
						gap,
						blocked,
						overdue,
						missingLink,
						jiraFailed,
						githubFailed,
						weightsConfigured,
						mode == ContributionConfigMode.PROJECT_GROUP,
						elapsed,
						sprintEnded,
						peer == null ? null : peer.expectedReviews(),
						peer == null ? null : peer.submittedReviews(),
						pending)),
				null);
	}

	private static CurrentSprint toCurrentSprint(Sprint sprint, LocalDateTime now) {
		return new CurrentSprint(
				sprint.getId(),
				sprint.getName(),
				sprint.getState() == null ? null : sprint.getState().toUpperCase(),
				toDate(sprint.getStartDate()),
				toDate(sprint.getEndDate()),
				elapsedPercent(sprint.getStartDate(), sprint.getEndDate(), now));
	}

	private static Progress toProgress(
			UUID sprintId,
			CurrentSprint current,
			Map<UUID, long[]> tasksBySprint,
			Map<UUID, Long> overdueBySprint) {
		long[] bucket = tasksBySprint.getOrDefault(sprintId, new long[6]);
		long overdue = overdueBySprint.getOrDefault(sprintId, 0L);
		Double completion = percent(bucket[4], bucket[0]);
		Double gap = null;
		if (current.elapsedPercent() != null && completion != null) {
			gap = round(current.elapsedPercent() - completion);
		}
		return new Progress(bucket[0], bucket[1], bucket[2], bucket[3], bucket[4], bucket[5], overdue, completion, gap);
	}

	private static Traceability toTraceability(
			UUID sprintId,
			Map<UUID, Set<UUID>> doneTasksBySprint,
			Map<UUID, Set<UUID>> doneWithCommitBySprint,
			Map<UUID, Set<UUID>> linkedCommitsBySprint,
			List<TimedEvent> events,
			LocalDateTime start,
			LocalDateTime end) {
		Set<UUID> done = doneTasksBySprint.getOrDefault(sprintId, Set.of());
		Set<UUID> withCommit = doneWithCommitBySprint.getOrDefault(sprintId, Set.of());
		long completed = done.size();
		long with = withCommit.size();
		Set<UUID> linked = linkedCommitsBySprint.getOrDefault(sprintId, Set.of());
		long linkedCount = linked.size();
		long unlinked = 0;
		if (start != null && end != null) {
			Set<UUID> seen = new HashSet<>();
			for (TimedEvent event : events) {
				if (event.kind() != Kind.COMMIT || event.at() == null || event.id() == null) {
					continue;
				}
				if (event.at().isBefore(start) || event.at().isAfter(end) || !seen.add(event.id())) {
					continue;
				}
				if (!linked.contains(event.id())) {
					unlinked++;
				}
			}
		}
		return new Traceability(
				completed, with, completed - with, linkedCount, unlinked, percent(with, completed));
	}

	private Activity toActivity(
			UUID projectId,
			Sprint current,
			Map<UUID, LocalDateTime> lastCommit,
			Map<UUID, LocalDateTime> lastTask,
			List<TimedEvent> events,
			LocalDateTime now) {
		LocalDateTime last = latest(lastCommit.get(projectId), lastTask.get(projectId));
		Integer inactiveDays = last == null ? null : (int) ChronoUnit.DAYS.between(last.toLocalDate(), now.toLocalDate());
		List<ActivityDay> series = List.of();
		long total = 0;
		if (current != null && current.getStartDate() != null && current.getEndDate() != null) {
			series = series(current.getStartDate().toLocalDate(), current.getEndDate().toLocalDate(), events);
			total = series.stream().mapToLong(ActivityDay::totalActivities).sum();
		}
		return new Activity(toInstant(last), inactiveDays, total, series);
	}

	private static PeerReview toPeer(
			Sprint sprint, List<TeamMember> roster, Map<UUID, List<ReviewRow>> reviewsBySprint, Instant now) {
		List<UUID> reviewers = roster.stream()
				.filter(member -> member.getRoleInTeam() != RoleInTeam.MENTOR)
				.map(member -> member.getCourseEnrollment().getStudentProfile().getId())
				.toList();
		int n = reviewers.size();
		long expected = n <= 1 ? 0 : (long) n * (n - 1);
		Set<UUID> reviewerSet = new HashSet<>(reviewers);
		List<ReviewRow> rows = reviewsBySprint.getOrDefault(sprint.getId(), List.of());
		Set<String> pairs = new HashSet<>();
		for (ReviewRow row : rows) {
			if (!reviewerSet.contains(row.reviewerId()) || !reviewerSet.contains(row.revieweeId())) {
				continue;
			}
			pairs.add(row.reviewerId() + ":" + row.revieweeId());
		}
		long submitted = pairs.size();
		List<UUID> pending = new ArrayList<>();
		if (expected > 0) {
			Map<UUID, Integer> remaining = new HashMap<>();
			for (UUID reviewer : reviewers) {
				remaining.put(reviewer, n - 1);
			}
			for (String pair : pairs) {
				UUID reviewerId = UUID.fromString(pair.substring(0, pair.indexOf(':')));
				remaining.computeIfPresent(reviewerId, (id, left) -> Math.max(0, left - 1));
			}
			for (UUID reviewer : reviewers) {
				if (remaining.getOrDefault(reviewer, 0) > 0) {
					pending.add(reviewer);
				}
			}
		}
		Instant deadline = toInstant(sprint.getEndDate());
		return new PeerReview(
				expected, submitted, percent(submitted, expected), pending.size(), List.copyOf(pending), deadline);
	}

	private static PreviousSprintComparison toPrevious(
			Sprint previous,
			Sprint current,
			Map<UUID, long[]> tasksBySprint,
			Map<UUID, Set<UUID>> doneTasksBySprint,
			Map<UUID, Set<UUID>> doneWithCommitBySprint,
			Map<UUID, Set<UUID>> linkedCommitsBySprint,
			List<TimedEvent> events,
			Activity currentActivity) {
		long[] prevTasks = tasksBySprint.getOrDefault(previous.getId(), new long[6]);
		Double prevCompletion = percent(prevTasks[4], prevTasks[0]);
		Double currentCompletion = current == null ? null : percent(
				tasksBySprint.getOrDefault(current.getId(), new long[6])[4],
				tasksBySprint.getOrDefault(current.getId(), new long[6])[0]);
		Double completionDelta =
				prevCompletion == null || currentCompletion == null ? null : round(currentCompletion - prevCompletion);

		long prevActivity = 0;
		if (previous.getStartDate() != null && previous.getEndDate() != null) {
			prevActivity = series(previous.getStartDate().toLocalDate(), previous.getEndDate().toLocalDate(), events)
					.stream()
					.mapToLong(ActivityDay::totalActivities)
					.sum();
		}
		Double activityDelta = null;
		if (currentActivity != null && prevActivity > 0) {
			activityDelta = round(((currentActivity.totalActivities() - prevActivity) * 100.0) / prevActivity);
		} else if (currentActivity != null && prevActivity == 0 && currentActivity.totalActivities() == 0) {
			activityDelta = 0.0;
		}

		Traceability prevTrace = toTraceability(
				previous.getId(),
				doneTasksBySprint,
				doneWithCommitBySprint,
				linkedCommitsBySprint,
				events,
				previous.getStartDate(),
				previous.getEndDate());
		Traceability currentTrace = current == null
				? null
				: toTraceability(
						current.getId(),
						doneTasksBySprint,
						doneWithCommitBySprint,
						linkedCommitsBySprint,
						events,
						current.getStartDate(),
						current.getEndDate());
		Double traceDelta = null;
		if (prevTrace.taskCommitLinkRate() != null && currentTrace != null && currentTrace.taskCommitLinkRate() != null) {
			traceDelta = round(currentTrace.taskCommitLinkRate() - prevTrace.taskCommitLinkRate());
		}
		return new PreviousSprintComparison(
				previous.getId(), previous.getName(), completionDelta, activityDelta, traceDelta);
	}

	private static Sync toSync(
			UUID projectId,
			Map<UUID, String> jiraStatusByProject,
			Map<UUID, String> githubStatusByProject,
			Map<UUID, JobSnapshot> jiraJobs,
			Map<UUID, JobSnapshot> githubJobs) {
		JobSnapshot jira = jiraJobs.get(projectId);
		JobSnapshot github = githubJobs.get(projectId);
		return new Sync(
				jiraStatusByProject.get(projectId),
				jira == null ? null : jira.status(),
				jira == null ? null : jira.lastSuccess(),
				githubStatusByProject.get(projectId),
				github == null ? null : github.status(),
				github == null ? null : github.lastSuccess());
	}

	private Map<UUID, long[]> taskBuckets(Set<UUID> sprintIds) {
		Map<UUID, long[]> out = new HashMap<>();
		if (sprintIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.countGroupedBySprintIdsAndStatus(sprintIds)) {
			UUID sprintId = (UUID) row[0];
			TaskStatus status = (TaskStatus) row[1];
			long count = (Long) row[2];
			long[] bucket = out.computeIfAbsent(sprintId, id -> new long[6]);
			bucket[0] += count;
			if (status == TaskStatus.TODO) {
				bucket[1] += count;
			} else if (status == TaskStatus.IN_PROGRESS) {
				bucket[2] += count;
			} else if (status == TaskStatus.IN_REVIEW) {
				bucket[3] += count;
			} else if (status == TaskStatus.DONE) {
				bucket[4] += count;
			} else if (status == TaskStatus.BLOCKED) {
				bucket[5] += count;
			}
		}
		return out;
	}

	private Map<UUID, Long> overdueBuckets(Set<UUID> sprintIds, LocalDateTime now) {
		Map<UUID, Long> out = new HashMap<>();
		if (sprintIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.countOverdueBySprintIds(sprintIds, now)) {
			out.put((UUID) row[0], (Long) row[1]);
		}
		return out;
	}

	private Map<UUID, Set<UUID>> doneTaskIds(Set<UUID> sprintIds) {
		Map<UUID, Set<UUID>> out = new HashMap<>();
		if (sprintIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.findDoneTaskIdsBySprintIds(sprintIds)) {
			out.computeIfAbsent((UUID) row[0], id -> new HashSet<>()).add((UUID) row[1]);
		}
		return out;
	}

	private Map<UUID, List<TimedEvent>> loadEvents(List<UUID> projectIds) {
		Map<UUID, List<TimedEvent>> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : commits.findProjectIdAndIdAndCommittedAtByProjectIds(projectIds)) {
			out.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
					.add(new TimedEvent(Kind.COMMIT, (LocalDateTime) row[2], (UUID) row[1]));
		}
		if (!projectIds.isEmpty()) {
			addProjectTimes(out, tasks.findProjectIdAndCreatedAtByProjectIds(projectIds), Kind.TASK);
			addProjectTimes(out, files.findProjectIdAndCreatedAtByProjectIds(projectIds), Kind.DOCUMENT);
			addProjectTimes(out, webLinks.findProjectIdAndCreatedAtByProjectIds(projectIds), Kind.DOCUMENT);
		}
		return out;
	}

	private static void addProjectTimes(Map<UUID, List<TimedEvent>> out, List<Object[]> rows, Kind kind) {
		for (Object[] row : rows) {
			out.computeIfAbsent((UUID) row[0], id -> new ArrayList<>())
					.add(new TimedEvent(kind, (LocalDateTime) row[1], null));
		}
	}

	private Map<UUID, List<ReviewRow>> reviewsBySprint(Set<UUID> sprintIds) {
		Map<UUID, List<ReviewRow>> out = new HashMap<>();
		if (sprintIds.isEmpty()) {
			return out;
		}
		for (Object[] row : peerReviews.findSubmittedRowsBySprintIds(sprintIds)) {
			UUID sprintId = (UUID) row[0];
			out.computeIfAbsent(sprintId, id -> new ArrayList<>())
					.add(new ReviewRow((UUID) row[1], (UUID) row[2], (LocalDateTime) row[3]));
		}
		return out;
	}

	private static void attachPeerReviewEvents(
			Map<UUID, List<TimedEvent>> eventsByProject,
			Map<UUID, List<Sprint>> sprintsByProject,
			Map<UUID, List<ReviewRow>> reviewsBySprint) {
		Map<UUID, UUID> projectBySprint = new HashMap<>();
		for (Map.Entry<UUID, List<Sprint>> entry : sprintsByProject.entrySet()) {
			for (Sprint sprint : entry.getValue()) {
				projectBySprint.put(sprint.getId(), entry.getKey());
			}
		}
		for (Map.Entry<UUID, List<ReviewRow>> entry : reviewsBySprint.entrySet()) {
			UUID projectId = projectBySprint.get(entry.getKey());
			if (projectId == null) {
				continue;
			}
			List<TimedEvent> events = eventsByProject.computeIfAbsent(projectId, id -> new ArrayList<>());
			for (ReviewRow row : entry.getValue()) {
				events.add(new TimedEvent(Kind.PEER_REVIEW, row.createdAt(), null));
			}
		}
	}

	private Map<UUID, LocalDateTime> lastCommitByProject(List<UUID> projectIds) {
		Map<UUID, LocalDateTime> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : commits.countAndMaxCommittedAtGroupedByProjects(projectIds)) {
			out.put((UUID) row[0], (LocalDateTime) row[2]);
		}
		return out;
	}

	private Map<UUID, LocalDateTime> lastTaskByProject(List<UUID> projectIds) {
		Map<UUID, LocalDateTime> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.findMaxUpdatedAtGroupedByProjects(projectIds)) {
			out.put((UUID) row[0], (LocalDateTime) row[1]);
		}
		return out;
	}

	private Map<UUID, String> jiraStatus(List<UUID> projectIds) {
		Map<UUID, String> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (JiraIntegration row : jiraIntegrations.findByProject_IdIn(projectIds)) {
			out.put(row.getProject().getId(), statusName(row.getConnectionStatus()));
		}
		return out;
	}

	private Map<UUID, String> githubStatus(List<UUID> projectIds) {
		Map<UUID, String> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (GitRepo row : gitRepos.findByProject_IdIn(projectIds)) {
			UUID projectId = row.getProject().getId();
			String next = statusName(row.getConnectionStatus());
			String current = out.get(projectId);
			out.put(projectId, betterStatus(current, next));
		}
		return out;
	}

	private void indexJobs(List<UUID> projectIds, Map<UUID, JobSnapshot> jiraJobs, Map<UUID, JobSnapshot> githubJobs) {
		if (projectIds.isEmpty()) {
			return;
		}
		List<SyncJobLog> jobs = syncJobs.findByTargetIdInOrderByStartedAtDesc(projectIds);
		for (SyncJobLog job : jobs) {
			Map<UUID, JobSnapshot> target = "JIRA".equals(job.getTargetSystem())
					? jiraJobs
					: "GITHUB".equals(job.getTargetSystem()) ? githubJobs : null;
			if (target == null || target.containsKey(job.getTargetId())) {
				continue;
			}
			Instant lastSuccess = job.getStatus() == SyncJobStatus.SUCCEEDED ? toInstant(job.getCompletedAt()) : null;
			target.put(
					job.getTargetId(),
					new JobSnapshot(job.getStatus() == null ? null : job.getStatus().name(), lastSuccess));
		}
		for (SyncJobLog job : jobs) {
			if (job.getStatus() != SyncJobStatus.SUCCEEDED) {
				continue;
			}
			Map<UUID, JobSnapshot> target = "JIRA".equals(job.getTargetSystem())
					? jiraJobs
					: "GITHUB".equals(job.getTargetSystem()) ? githubJobs : null;
			if (target == null) {
				continue;
			}
			JobSnapshot current = target.get(job.getTargetId());
			if (current != null && current.lastSuccess() == null) {
				target.put(
						job.getTargetId(),
						new JobSnapshot(current.status(), toInstant(job.getCompletedAt())));
			}
		}
	}

	private Set<UUID> configuredGroupProjects(UUID courseId) {
		Set<UUID> out = new HashSet<>();
		for (var config : groupWeights.findByTeam_Course_Id(courseId)) {
			if (config.getProject() != null) {
				out.add(config.getProject().getId());
			}
		}
		return out;
	}

	private static Sprint pickCurrent(List<Sprint> list) {
		for (Sprint sprint : list) {
			if (sprint.getState() != null && sprint.getState().equalsIgnoreCase("active")) {
				return sprint;
			}
		}
		return null;
	}

	private static Sprint pickPrevious(List<Sprint> list, Sprint current) {
		Sprint best = null;
		LocalDateTime currentStart = current == null ? null : current.getStartDate();
		for (Sprint sprint : list) {
			if (current != null && sprint.getId().equals(current.getId())) {
				continue;
			}
			if (!isClosed(sprint)) {
				continue;
			}
			if (currentStart != null
					&& sprint.getStartDate() != null
					&& !sprint.getStartDate().isBefore(currentStart)) {
				continue;
			}
			if (best == null
					|| (sprint.getStartDate() != null
							&& (best.getStartDate() == null || sprint.getStartDate().isAfter(best.getStartDate())))) {
				best = sprint;
			}
		}
		return best;
	}

	private static boolean isClosed(Sprint sprint) {
		return sprint.getCompleteDate() != null
				|| (sprint.getState() != null && sprint.getState().equalsIgnoreCase("closed"));
	}

	private static List<ActivityDay> series(LocalDate start, LocalDate end, List<TimedEvent> events) {
		LocalDate from = start;
		LocalDate to = end;
		if (ChronoUnit.DAYS.between(from, to) + 1 > MAX_SERIES_DAYS) {
			from = to.minusDays(MAX_SERIES_DAYS - 1L);
		}
		Map<LocalDate, long[]> days = new HashMap<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			days.put(day, new long[4]);
		}
		for (TimedEvent event : events) {
			if (event.at() == null) {
				continue;
			}
			LocalDate day = event.at().toLocalDate();
			long[] bucket = days.get(day);
			if (bucket == null) {
				continue;
			}
			switch (event.kind()) {
				case COMMIT -> bucket[0]++;
				case TASK -> bucket[1]++;
				case PEER_REVIEW -> bucket[2]++;
				case DOCUMENT -> bucket[3]++;
			}
		}
		List<ActivityDay> out = new ArrayList<>();
		for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
			long[] bucket = days.get(day);
			long total = bucket[0] + bucket[1] + bucket[2] + bucket[3];
			out.add(new ActivityDay(day, bucket[0], bucket[1], bucket[2], bucket[3], total));
		}
		return out;
	}

	private static boolean isSyncFailure(Sync sync) {
		return sync != null && ("FAILED".equals(sync.jiraSyncStatus()) || "FAILED".equals(sync.githubSyncStatus()));
	}

	private static String betterStatus(String current, String next) {
		if (current == null) {
			return next;
		}
		if ("ACTIVE".equals(next) || current.equals(next)) {
			return next == null ? current : rankStatus(next) >= rankStatus(current) ? next : current;
		}
		return rankStatus(next) >= rankStatus(current) ? next : current;
	}

	private static int rankStatus(String status) {
		return switch (status) {
			case "ACTIVE" -> 3;
			case "DEGRADED" -> 2;
			case "ERROR" -> 1;
			default -> 0;
		};
	}

	private static String statusName(IntegrationStatus status) {
		return status == null ? null : status.name();
	}

	private static Double elapsedPercent(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
		if (start == null || end == null || !end.isAfter(start)) {
			return null;
		}
		if (now.isBefore(start)) {
			return 0.0;
		}
		if (!now.isBefore(end)) {
			return 100.0;
		}
		double span = ChronoUnit.SECONDS.between(start, end);
		if (span <= 0) {
			return null;
		}
		return round((ChronoUnit.SECONDS.between(start, now) * 100.0) / span);
	}

	private static Double percent(long part, long total) {
		if (total <= 0) {
			return null;
		}
		return round((part * 100.0) / total);
	}

	private static double round(double value) {
		return Math.round(value * 100.0) / 100.0;
	}

	private static LocalDate toDate(LocalDateTime value) {
		return value == null ? null : value.toLocalDate();
	}

	private static Instant toInstant(LocalDateTime value) {
		return value == null ? null : value.toInstant(ZoneOffset.UTC);
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

	private static DashboardScope parseScope(String raw) {
		if (raw == null || raw.isBlank() || DashboardScope.CURRENT_SPRINT.name().equals(raw)) {
			return DashboardScope.CURRENT_SPRINT;
		}
		throw new AcademicException(
				AcademicErrorCode.INVALID_DASHBOARD_SCOPE,
				HttpStatus.BAD_REQUEST,
				"Unsupported dashboard scope.");
	}

	private static void requireNotAdmin(UserAccount actor) {
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			throw new AcademicException(
					AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Course dashboard is limited to the assigned lecturer.");
		}
	}

	private enum Kind {
		COMMIT,
		TASK,
		PEER_REVIEW,
		DOCUMENT
	}

	private record TimedEvent(Kind kind, LocalDateTime at, UUID id) {}

	private record ReviewRow(UUID reviewerId, UUID revieweeId, LocalDateTime createdAt) {}

	private record JobSnapshot(String status, Instant lastSuccess) {}
}

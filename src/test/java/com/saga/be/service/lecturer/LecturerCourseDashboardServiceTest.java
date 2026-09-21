package com.saga.be.service.lecturer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse;
import com.saga.be.dto.lecturer.LecturerCourseDashboardResponse.TeamCard;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.ContributionConfigMode;
import com.saga.be.entity.enums.DashboardScope;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.LecturerDashboardRiskLevel;
import com.saga.be.entity.enums.LecturerDashboardRiskReasonCode;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ContributionOverrideRepository;
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
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LecturerCourseDashboardServiceTest {

	private static final Instant NOW = Instant.parse("2026-09-21T12:00:00Z");

	@Mock
	private LecturerCourseAuthorization authorization;
	@Mock
	private TeamRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private SprintRepository sprints;
	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private PeerReviewRepository peerReviews;
	@Mock
	private TaskFileRepository files;
	@Mock
	private TaskWebLinkRepository webLinks;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private GitRepoRepository gitRepos;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private ProjectGroupWeightConfigRepository groupWeights;

	private LecturerCourseDashboardService service;
	private UUID courseId;

	@BeforeEach
	void setUp() {
		service = new LecturerCourseDashboardService(
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
				Clock.fixed(NOW, ZoneOffset.UTC));
		courseId = UUID.randomUUID();
		lenient().when(members.findFetchedByCourse_Id(courseId)).thenReturn(List.of());
		lenient()
				.when(enrollments.findByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE))
				.thenReturn(List.of());
		lenient().when(groupWeights.findByTeam_Course_Id(courseId)).thenReturn(List.of());
	}

	@Test
	void constructorsDoNotAcceptContributionOverrideRepository() {
		for (Constructor<?> constructor : LecturerCourseDashboardService.class.getDeclaredConstructors()) {
			assertThat(constructor.getParameterTypes()).doesNotContain(ContributionOverrideRepository.class);
		}
	}

	@Test
	void adminDeniedBeforeCourseLookup() {
		UserAccount admin = actor(AccountRole.ADMIN);

		assertThatThrownBy(() -> service.getDashboard(admin, courseId, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		verify(authorization, never()).requireCourse(any(), any());
	}

	@Test
	void invalidScopeRejected() {
		UserAccount lecturer = actor(AccountRole.LECTURER);

		assertThatThrownBy(() -> service.getDashboard(lecturer, courseId, "ALL_TIME"))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.INVALID_DASHBOARD_SCOPE);
		verify(authorization, never()).requireCourse(any(), any());
	}

	@Test
	void assignedLecturerGetsEmptyDashboard() {
		UserAccount lecturer = actor(AccountRole.LECTURER);
		Course course = course();
		when(authorization.requireCourse(lecturer, courseId)).thenReturn(course);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of());

		LecturerCourseDashboardResponse response = service.getDashboard(lecturer, courseId, null);

		assertThat(response.courseId()).isEqualTo(course.getId());
		assertThat(response.scope()).isEqualTo(DashboardScope.CURRENT_SPRINT);
		assertThat(response.generatedAt()).isEqualTo(NOW);
		assertThat(response.teams()).isEmpty();
		assertThat(response.summary().totalTeams()).isZero();
	}

	@Test
	void teamWithoutProjectIsCriticalAndReminderIsNull() {
		UserAccount lecturer = actor(AccountRole.LECTURER);
		Course course = course();
		when(authorization.requireCourse(lecturer, courseId)).thenReturn(course);
		Team team = team(1, "No project", null);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(team));
		CourseEnrollment enrollment = enrollment();
		when(enrollments.findByCourse_IdAndEnrollmentStatus(courseId, EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(enrollment));

		LecturerCourseDashboardResponse response = service.getDashboard(lecturer, courseId, "CURRENT_SPRINT");

		TeamCard card = response.teams().getFirst();
		assertThat(card.projectId()).isNull();
		assertThat(card.reminder()).isNull();
		assertThat(card.risk().level()).isEqualTo(LecturerDashboardRiskLevel.CRITICAL);
		assertThat(card.risk().reasons())
				.extracting(reason -> reason.code())
				.containsExactly(LecturerDashboardRiskReasonCode.NO_PROJECT);
		assertThat(card.configuration().contributionMode()).isEqualTo(ContributionConfigMode.COURSE);
		assertThat(card.configuration().contributionWeightsConfigured()).isTrue();
		assertThat(response.summary().teamsWithoutProject()).isEqualTo(1);
		assertThat(response.summary().criticalTeams()).isEqualTo(1);
		assertThat(response.summary().unassignedStudents()).isEqualTo(1);
		assertThat(response.summary().enrolledStudents()).isEqualTo(1);
	}

	@Test
	void inactiveWarningUsesThreeCalendarDays() {
		UserAccount lecturer = actor(AccountRole.LECTURER);
		Course course = course();
		when(authorization.requireCourse(lecturer, courseId)).thenReturn(course);
		Project project = project();
		Team team = team(1, "Team A", project);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(team));
		stubProjectReads(project.getId());
		when(sprints.findActiveByProjectIdIn(List.of(project.getId()))).thenReturn(List.of());
		when(commits.countAndMaxCommittedAtGroupedByProjects(List.of(project.getId())))
				.thenReturn(List.<Object[]>of(new Object[] {
					project.getId(), 1L, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(3)
				}));

		TeamCard card = service.getDashboard(lecturer, courseId, null).teams().getFirst();

		assertThat(card.activity().inactiveDays()).isEqualTo(3);
		assertThat(card.risk().reasons())
				.anySatisfy(reason -> {
					assertThat(reason.code()).isEqualTo(LecturerDashboardRiskReasonCode.INACTIVE);
					assertThat(reason.severity()).isEqualTo(LecturerDashboardRiskLevel.WARNING);
					assertThat(reason.thresholdValue()).isEqualTo(3.0);
				});
	}

	@Test
	void scheduleLagCriticalAtThirtyFivePoints() {
		UserAccount lecturer = actor(AccountRole.LECTURER);
		Course course = course();
		when(authorization.requireCourse(lecturer, courseId)).thenReturn(course);
		Project project = project();
		Team team = team(1, "Team A", project);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(team));
		Sprint current = sprint(
				project,
				"active",
				"Sprint 1",
				LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).minusDays(7),
				LocalDateTime.ofInstant(NOW, ZoneOffset.UTC).plusDays(3));
		stubProjectReads(project.getId());
		when(sprints.findActiveByProjectIdIn(List.of(project.getId()))).thenReturn(List.of(current));
		when(tasks.countGroupedBySprintIdsAndStatus(any()))
				.thenReturn(List.<Object[]>of(
						new Object[] {current.getId(), TaskStatus.DONE, 1L},
						new Object[] {current.getId(), TaskStatus.TODO, 9L}));
		when(tasks.countOverdueBySprintIds(any(), any())).thenReturn(List.of());
		when(tasks.findDoneTaskIdsBySprintIds(any())).thenReturn(List.of());
		when(links.findLinkedCommitAndTaskIdsBySprintIds(any())).thenReturn(List.of());
		when(peerReviews.findSubmittedRowsBySprintIds(any())).thenReturn(List.of());

		TeamCard card = service.getDashboard(lecturer, courseId, null).teams().getFirst();

		assertThat(card.progress().completionPercent()).isEqualTo(10.0);
		assertThat(card.progress().scheduleGapPercentagePoints()).isGreaterThanOrEqualTo(35.0);
		assertThat(card.risk().reasons())
				.anySatisfy(reason -> {
					assertThat(reason.code()).isEqualTo(LecturerDashboardRiskReasonCode.SCHEDULE_LAG);
					assertThat(reason.severity()).isEqualTo(LecturerDashboardRiskLevel.CRITICAL);
					assertThat(reason.thresholdValue()).isEqualTo(35.0);
				});
	}

	private void stubProjectReads(UUID projectId) {
		lenient().when(commits.findProjectIdAndIdAndCommittedAtByProjectIds(List.of(projectId))).thenReturn(List.of());
		lenient().when(tasks.findProjectIdAndCreatedAtByProjectIds(List.of(projectId))).thenReturn(List.of());
		lenient().when(files.findProjectIdAndCreatedAtByProjectIds(List.of(projectId))).thenReturn(List.of());
		lenient().when(webLinks.findProjectIdAndCreatedAtByProjectIds(List.of(projectId))).thenReturn(List.of());
		lenient().when(commits.countAndMaxCommittedAtGroupedByProjects(List.of(projectId))).thenReturn(List.of());
		lenient().when(tasks.findMaxUpdatedAtGroupedByProjects(List.of(projectId))).thenReturn(List.of());
		lenient().when(jiraIntegrations.findByProject_IdIn(List.of(projectId))).thenReturn(List.of());
		lenient().when(gitRepos.findByProject_IdIn(List.of(projectId))).thenReturn(List.of());
		lenient().when(syncJobs.findByTargetIdInOrderByStartedAtDesc(List.of(projectId))).thenReturn(List.of());
	}

	private static UserAccount actor(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setAccountRole(role);
		return account;
	}

	private Course course() {
		Course course = new Course();
		course.setId(courseId);
		course.setCourseCode("SE123");
		course.setContributionConfigMode(ContributionConfigMode.COURSE);
		return course;
	}

	private static Project project() {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		project.setName("Capstone");
		return project;
	}

	private static Team team(int teamNo, String name, Project project) {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(teamNo);
		team.setName(name);
		team.setProject(project);
		return team;
	}

	private static CourseEnrollment enrollment() {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		return enrollment;
	}

	private static Sprint sprint(
			Project project, String state, String name, LocalDateTime start, LocalDateTime end) {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setJiraIntegration(integration);
		sprint.setState(state);
		sprint.setName(name);
		sprint.setStartDate(start);
		sprint.setEndDate(end);
		return sprint;
	}
}

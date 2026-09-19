package com.saga.be.service.student;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentDashboardAnomalyCandidateRow;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StudentDashboardServiceTest {

	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private SprintRepository sprints;
	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskGitCommitLinkRepository commitLinks;
	@Mock
	private PeerReviewRepository peerReviews;
	@Mock
	private IdentityMapRepository identities;

	private static final Clock UTC_SUNDAY = Clock.fixed(Instant.parse("2026-09-20T12:00:00Z"), ZoneOffset.UTC);

	private StudentDashboardService service;
	private UserAccount account;
	private StudentProfile profile;
	private Course course;
	private CourseEnrollment enrollment;

	@BeforeEach
	void setUp() {
		service = new StudentDashboardService(
				enrollments,
				members,
				jiraIntegrations,
				repos,
				sprints,
				tasks,
				commits,
				commitLinks,
				peerReviews,
				identities,
				UTC_SUNDAY);
		account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("member@gmail.com");
		account.setFullName("Alpha Student");
		account.setAvatarUrl("https://example.com/a.png");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setStudentCode("SE111111");
		profile.setUserAccount(account);
		course = course("SWP391", "Software Development Project", "FA26");
		enrollment = enrollment(course, EnrollmentStatus.ACTIVE);
	}

	@Test
	void memberReturns200WithTeamAndProject() {
		Team team = team(project());
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(3L);
		stubEmptyIntegrations(team.getProject().getId());

		StudentDashboardResponse response = service.get(account.getId(), course.getId());

		assertEquals(profile.getId(), response.student().studentId());
		assertEquals(account.getId(), response.student().userId());
		assertEquals("SE111111", response.student().studentCode());
		assertEquals("Alpha Student", response.student().fullName());
		assertEquals("https://example.com/a.png", response.student().avatarUrl());
		assertEquals("MEMBER", response.student().teamRole());
		assertEquals(course.getId(), response.course().courseId());
		assertEquals("SWP391", response.course().subjectCode());
		assertEquals(team.getId(), response.team().teamId());
		assertEquals(3L, response.team().membersCount());
		assertEquals(team.getProject().getId(), response.team().projectId());
		assertFalse(response.integrations().jira().connected());
		assertFalse(response.integrations().github().connected());
		assertNull(response.currentSprint());
		assertEquals(0L, response.myMetrics().tasks().totalAssigned());
		assertNull(response.myMetrics().tasks().completionPercent());
		assertEquals(0L, response.myMetrics().commits().totalCommits());
		assertNull(response.myMetrics().commits().traceabilityPercent());
		assertTrue(response.myActiveTasks().isEmpty());
		assertTrue(response.recentCommits().isEmpty());
		assertEquals(3, response.weeklyCommits().size());
		assertEquals(LocalDate.of(2026, 8, 31), response.weeklyCommits().getFirst().startDate());
		assertEquals(LocalDate.of(2026, 9, 20), response.weeklyCommits().get(2).endDate());
		assertEquals(0L, response.weeklyCommits().getFirst().commits());
		assertTrue(response.actionableAlerts().isEmpty());
	}

	@Test
	void leaderAndMentorRolesAreEmitted() {
		Team team = team(null);
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.LEADER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		assertEquals("LEADER", service.get(account.getId(), course.getId()).student().teamRole());

		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MENTOR)));
		assertEquals("MENTOR", service.get(account.getId(), course.getId()).student().teamRole());
	}

	@Test
	void missingEnrollmentIsForbidden() {
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.empty());
		AcademicException ex = assertThrows(AcademicException.class, () -> service.get(account.getId(), course.getId()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
		verify(members, never()).findFetchedByCourseEnrollment_Id(any());
	}

	@Test
	void noTeamReturnsNullTeamAndNoDownstreamReads() {
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.empty());

		StudentDashboardResponse response = service.get(account.getId(), course.getId());

		assertEquals(profile.getId(), response.student().studentId());
		assertNull(response.student().teamRole());
		assertEquals(course.getId(), response.course().courseId());
		assertNull(response.team());
		assertNull(response.integrations());
		assertNull(response.currentSprint());
		assertNull(response.myMetrics());
		assertTrue(response.myActiveTasks().isEmpty());
		assertTrue(response.recentCommits().isEmpty());
		assertTrue(response.weeklyCommits().isEmpty());
		assertTrue(response.actionableAlerts().isEmpty());
		verify(members, never()).countActiveByTeam_Id(any());
		verify(jiraIntegrations, never()).findByProject_Id(any());
		verify(repos, never()).countAndMaxLastSyncedAtGroupedByStatus(any());
		verify(sprints, never()).findActiveByProject_Id(any());
		verify(tasks, never()).countStatusAndStoryPointsForAssignee(any(), any());
		verify(commits, never()).countAndMaxCommittedAtByProjectAndAuthor(any(), any());
		verify(commits, never()).findWeeklyCommittedAtByProjectAndAuthor(any(), any(), any(), any());
		verify(peerReviews, never()).countRemainingPeers(any(), any(), any());
	}

	@Test
	void teamWithoutProjectReturnsNullIntegrationsAndSprint() {
		Team team = team(null);
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(2L);

		StudentDashboardResponse response = service.get(account.getId(), course.getId());

		assertEquals(team.getId(), response.team().teamId());
		assertNull(response.team().projectId());
		assertNull(response.team().projectName());
		assertEquals(2L, response.team().membersCount());
		assertNull(response.integrations());
		assertNull(response.currentSprint());
		assertNull(response.myMetrics());
		assertTrue(response.myActiveTasks().isEmpty());
		assertTrue(response.recentCommits().isEmpty());
		assertTrue(response.weeklyCommits().isEmpty());
		assertTrue(response.actionableAlerts().isEmpty());
		verify(jiraIntegrations, never()).findByProject_Id(any());
		verify(tasks, never()).countStatusAndStoryPointsForAssignee(any(), any());
		verify(commits, never()).countAndMaxCommittedAtByProjectAndAuthor(any(), any());
		verify(commits, never()).findWeeklyCommittedAtByProjectAndAuthor(any(), any(), any(), any());
		verify(peerReviews, never()).countRemainingPeers(any(), any(), any());
	}

	@Test
	void jiraActiveIsConnectedUsingSuccessfulSyncOnly() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		JiraIntegration jira = new JiraIntegration();
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setProjectKey("SAGA");
		jira.setLastSuccessfulSyncAt(LocalDateTime.of(2026, 9, 1, 10, 0));
		jira.setLastSyncedAt(LocalDateTime.of(2026, 9, 2, 10, 0));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(jira));
		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId)).thenReturn(List.of());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		var row = service.get(account.getId(), course.getId()).integrations().jira();
		assertTrue(row.connected());
		assertEquals("SAGA", row.projectKey());
		assertEquals("ACTIVE", row.status());
		assertEquals(LocalDateTime.of(2026, 9, 1, 10, 0), row.lastSyncedAt());
	}

	@Test
	void jiraRevokedAndLegacyConnectedAreDisconnected() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId)).thenReturn(List.of());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		JiraIntegration revoked = new JiraIntegration();
		revoked.setConnectionStatus(IntegrationStatus.REVOKED);
		revoked.setProjectKey("OLD");
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(revoked));
		var revokedRow = service.get(account.getId(), course.getId()).integrations().jira();
		assertFalse(revokedRow.connected());
		assertEquals("REVOKED", revokedRow.status());
		assertEquals("OLD", revokedRow.projectKey());

		JiraIntegration legacy = new JiraIntegration();
		legacy.setConnectionStatus(IntegrationStatus.CONNECTED);
		legacy.setProjectKey("LEG");
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(legacy));
		var legacyRow = service.get(account.getId(), course.getId()).integrations().jira();
		assertFalse(legacyRow.connected());
		assertEquals("CONNECTED", legacyRow.status());
	}

	@Test
	void githubActivePlusRevokedCountsActiveOnly() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.of(
						new Object[] {IntegrationStatus.ACTIVE, 2L, LocalDateTime.of(2026, 9, 3, 8, 0)},
						new Object[] {IntegrationStatus.REVOKED, 5L, LocalDateTime.of(2026, 8, 1, 0, 0)}));
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		var github = service.get(account.getId(), course.getId()).integrations().github();
		assertTrue(github.connected());
		assertEquals(2L, github.repositoryCount());
		assertEquals("ACTIVE", github.status());
		assertEquals(LocalDateTime.of(2026, 9, 3, 8, 0), github.lastSyncedAt());
	}

	@Test
	void githubRevokedOnlyAndLegacyConnectedAreDisconnected() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {IntegrationStatus.REVOKED, 2L, LocalDateTime.of(2026, 8, 1, 0, 0)}));
		var revoked = service.get(account.getId(), course.getId()).integrations().github();
		assertFalse(revoked.connected());
		assertEquals(0L, revoked.repositoryCount());
		assertEquals("REVOKED", revoked.status());
		assertNull(revoked.lastSyncedAt());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {IntegrationStatus.CONNECTED, 1L, LocalDateTime.of(2026, 7, 1, 0, 0)}));
		var legacy = service.get(account.getId(), course.getId()).integrations().github();
		assertFalse(legacy.connected());
		assertEquals(0L, legacy.repositoryCount());
		assertEquals("CONNECTED", legacy.status());
	}

	@Test
	void githubDegradedAndErrorKeepTheirOwnStatus() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {IntegrationStatus.DEGRADED, 1L, LocalDateTime.of(2026, 8, 2, 0, 0)}));
		var degraded = service.get(account.getId(), course.getId()).integrations().github();
		assertFalse(degraded.connected());
		assertEquals(0L, degraded.repositoryCount());
		assertEquals("DEGRADED", degraded.status());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {IntegrationStatus.ERROR, 1L, null}));
		var error = service.get(account.getId(), course.getId()).integrations().github();
		assertFalse(error.connected());
		assertEquals(0L, error.repositoryCount());
		assertEquals("ERROR", error.status());
	}

	@Test
	void githubMixedNonActiveStatusesAreMixedNotRevoked() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
				.thenReturn(List.of(
						new Object[] {IntegrationStatus.REVOKED, 1L, LocalDateTime.of(2026, 8, 1, 0, 0)},
						new Object[] {IntegrationStatus.DEGRADED, 1L, LocalDateTime.of(2026, 8, 2, 0, 0)},
						new Object[] {IntegrationStatus.CONNECTED, 1L, LocalDateTime.of(2026, 7, 1, 0, 0)}));

		var github = service.get(account.getId(), course.getId()).integrations().github();
		assertFalse(github.connected());
		assertEquals(0L, github.repositoryCount());
		assertEquals("MIXED", github.status());
		assertNull(github.lastSyncedAt());
	}

	@Test
	void currentSprintPicksFirstActiveAndNullPercentWhenNoTasks() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		Sprint closed = sprint("closed");
		Sprint firstActive = sprint("active");
		Sprint secondActive = sprint("ACTIVE");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(closed, firstActive, secondActive));
		when(tasks.countGroupedByStatusForProjectAndSprint(projectId, firstActive.getId())).thenReturn(List.of());

		var current = service.get(account.getId(), course.getId()).currentSprint();
		assertEquals(firstActive.getId(), current.id());
		assertEquals(0L, current.totalTasks());
		assertEquals(0L, current.completedTasks());
		assertNull(current.completionPercent());
	}

	@Test
	void currentSprintCompletionUsesDoneOverTotalIncludingNullStatus() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		Sprint active = sprint("active");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active));
		when(tasks.countGroupedByStatusForProjectAndSprint(projectId, active.getId()))
				.thenReturn(List.of(
						new Object[] {TaskStatus.DONE, 2L},
						new Object[] {TaskStatus.TODO, 1L},
						new Object[] {null, 1L}));

		var current = service.get(account.getId(), course.getId()).currentSprint();
		assertEquals(4L, current.totalTasks());
		assertEquals(2L, current.completedTasks());
		assertEquals(50.0d, current.completionPercent());
	}

	@Test
	void futureOrClosedOnlyYieldsNullSprint() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint("future"), sprint("closed")));

		assertNull(service.get(account.getId(), course.getId()).currentSprint());
		verify(tasks, never()).countGroupedByStatusForProjectAndSprint(any(), any());
	}

	@Test
	void personalTaskMetricsAreProjectWideAndNullPercentWhenEmpty() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		when(tasks.countStatusAndStoryPointsForAssignee(projectId, profile.getId()))
				.thenReturn(List.of(
						new Object[] {TaskStatus.TODO, 1L, 0L},
						new Object[] {TaskStatus.IN_PROGRESS, 2L, 5L},
						new Object[] {TaskStatus.IN_REVIEW, 1L, 3L},
						new Object[] {TaskStatus.DONE, 3L, 8L},
						new Object[] {TaskStatus.BLOCKED, 0L, 2L}));

		var metrics = service.get(account.getId(), course.getId()).myMetrics().tasks();
		assertEquals(7L, metrics.totalAssigned());
		assertEquals(1L, metrics.todo());
		assertEquals(2L, metrics.inProgress());
		assertEquals(1L, metrics.inReview());
		assertEquals(3L, metrics.done());
		assertEquals(0L, metrics.blocked());
		assertEquals((3 * 100.0) / 7, metrics.completionPercent());
		assertEquals(18L, metrics.totalStoryPoints());
		assertEquals(8L, metrics.completedStoryPoints());
		verify(commitLinks, never()).countDistinctLinkedAuthoredV23(any(), any());
	}

	@Test
	void personalCommitMetricsUseV23AndDistinctLinks() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		when(commits.countAndMaxCommittedAtByProjectAndAuthor(projectId, profile.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {34L, LocalDateTime.of(2026, 9, 4, 8, 0)}));
		when(commitLinks.countDistinctLinkedAuthoredV23(projectId, profile.getId())).thenReturn(31L);

		var metrics = service.get(account.getId(), course.getId()).myMetrics().commits();
		assertEquals(34L, metrics.totalCommits());
		assertEquals(31L, metrics.linkedCommits());
		assertEquals(3L, metrics.unlinkedCommits());
		assertEquals((31 * 100.0) / 34, metrics.traceabilityPercent());
		assertEquals(LocalDateTime.of(2026, 9, 4, 8, 0), metrics.lastCommittedAt());
	}

	@Test
	void attentionPreviewKeepsAnomalyDoneAndDropsOrdinaryDone() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		Task open = assignedTask(TaskStatus.TODO, Priority.LOW, LocalDateTime.of(2026, 9, 20, 0, 0), null);
		Task anomaly = assignedTask(TaskStatus.DONE, Priority.MEDIUM, null, "[\"saga:code\"]");
		Task ambiguous = assignedTask(TaskStatus.DONE, Priority.HIGH, null, "[\"saga:code\",\"saga:test\"]");
		when(tasks.findAttentionNonDoneByProjectAndAssignee(eq(projectId), eq(profile.getId()), any(Pageable.class)))
				.thenReturn(List.of(open));
		when(tasks.findDoneWithoutV23EvidenceCandidates(projectId, profile.getId()))
				.thenReturn(List.of(candidate(anomaly), candidate(ambiguous)));
		when(commitLinks.countRawAndV23LinksByTaskIds(any()))
				.thenReturn(List.of(
						new Object[] {open.getId(), 2L, 1L},
						new Object[] {anomaly.getId(), 1L, 0L}));

		var response = service.get(account.getId(), course.getId());
		var preview = response.myActiveTasks();
		assertEquals(2, preview.size());
		assertEquals(anomaly.getId(), preview.getFirst().id());
		assertTrue(preview.getFirst().hasAnomaly());
		assertEquals(1L, preview.getFirst().linkedCommitCount());
		assertEquals(0L, preview.getFirst().evidenceCommitCount());
		assertEquals(open.getId(), preview.get(1).id());
		assertFalse(preview.get(1).hasAnomaly());
		assertEquals(1, response.actionableAlerts().size());
		assertEquals("MSR:" + anomaly.getId(), response.actionableAlerts().getFirst().id());
		assertEquals("MSR_ANOMALY", response.actionableAlerts().getFirst().type());
		assertEquals(anomaly.getId(), response.actionableAlerts().getFirst().targetIds().taskId());
		verify(peerReviews, never()).countRemainingPeers(any(), any(), any());
	}

	@Test
	void recentCommitsMapBulkKeysAndShortSha() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		GitCommit commit = authoredCommit("abcdef123456", "fix", "org/saga");
		when(commits.findRecentAuthoredV23ByProject(eq(projectId), eq(profile.getId()), any(Pageable.class)))
				.thenReturn(List.of(commit));
		when(commitLinks.findExternalKeysByCommitIds(any()))
				.thenReturn(List.of(
						new Object[] {commit.getId(), "SAGA-12"},
						new Object[] {commit.getId(), "SAGA-15"},
						new Object[] {commit.getId(), null}));

		var recent = service.get(account.getId(), course.getId()).recentCommits();
		assertEquals(1, recent.size());
		assertEquals("abcdef1", recent.getFirst().shortSha());
		assertEquals("org/saga", recent.getFirst().repositoryName());
		assertEquals(List.of("SAGA-12", "SAGA-15"), recent.getFirst().linkedTaskKeys());
		assertEquals(LocalDateTime.of(2026, 9, 4, 9, 0), recent.getFirst().committedAt());
		assertEquals("abc", StudentDashboardService.shortSha("abc"));
		assertNull(StudentDashboardService.shortSha(null));
	}

	@Test
	void recentCommitKeepsNullProviderCommittedAt() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		GitCommit commit = authoredCommit("abcdef123456", "fix", "org/saga");
		commit.setCommittedAt(null);
		commit.setCreatedAt(LocalDateTime.of(2026, 9, 10, 8, 0));
		when(commits.findRecentAuthoredV23ByProject(eq(projectId), eq(profile.getId()), any(Pageable.class)))
				.thenReturn(List.of(commit));

		var recent = service.get(account.getId(), course.getId()).recentCommits();
		assertEquals(1, recent.size());
		assertNull(recent.getFirst().committedAt());
		assertEquals("abcdef1", recent.getFirst().shortSha());
		assertEquals("org/saga", recent.getFirst().repositoryName());
	}

	@Test
	void weeklyCommitsBucketOnlyCommittedAtAndExcludeFutureWeek() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);
		when(commits.findWeeklyCommittedAtByProjectAndAuthor(eq(projectId), eq(profile.getId()), any(), any()))
				.thenReturn(List.of(
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 8, 31, 0, 0)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 6, 23, 59)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 7, 0, 0)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 13, 23, 59, 59)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 14, 0, 0)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 20, 12, 0)},
						new Object[] {UUID.randomUUID(), LocalDateTime.of(2026, 9, 21, 0, 0)}));

		var weeks = service.get(account.getId(), course.getId()).weeklyCommits();
		assertEquals(3, weeks.size());
		assertEquals(LocalDate.of(2026, 8, 31), weeks.get(0).startDate());
		assertEquals(LocalDate.of(2026, 9, 6), weeks.get(0).endDate());
		assertEquals(2L, weeks.get(0).commits());
		assertEquals(2L, weeks.get(1).commits());
		assertEquals(LocalDate.of(2026, 9, 14), weeks.get(2).startDate());
		assertEquals(LocalDate.of(2026, 9, 20), weeks.get(2).endDate());
		assertEquals(2L, weeks.get(2).commits());
	}

	@Test
	void academicZoneChangesCurrentIsoWeekIdentity() {
		service = new StudentDashboardService(
				enrollments,
				members,
				jiraIntegrations,
				repos,
				sprints,
				tasks,
				commits,
				commitLinks,
				peerReviews,
				identities,
				Clock.fixed(Instant.parse("2026-09-20T17:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(projectId);

		var weeks = service.get(account.getId(), course.getId()).weeklyCommits();
		assertEquals(LocalDate.of(2026, 9, 7), weeks.get(0).startDate());
		assertEquals(LocalDate.of(2026, 9, 21), weeks.get(2).startDate());
		assertEquals(LocalDate.of(2026, 9, 27), weeks.get(2).endDate());
	}

	@Test
	void dstCapableZoneKeepsMondaySundayCalendarWeeks() {
		service = new StudentDashboardService(
				enrollments,
				members,
				jiraIntegrations,
				repos,
				sprints,
				tasks,
				commits,
				commitLinks,
				peerReviews,
				identities,
				Clock.fixed(Instant.parse("2026-03-11T16:00:00Z"), ZoneId.of("America/New_York")));
		Team team = team(project());
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(1L);
		stubEmptyIntegrations(team.getProject().getId());

		var weeks = service.get(account.getId(), course.getId()).weeklyCommits();
		assertEquals(LocalDate.of(2026, 2, 23), weeks.get(0).startDate());
		assertEquals(LocalDate.of(2026, 3, 9), weeks.get(2).startDate());
		assertEquals(LocalDate.of(2026, 3, 15), weeks.get(2).endDate());
	}

	@Test
	void noCurrentSprintYieldsMsrAlertsButNoPeerAlert() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(5L);
		stubEmptyIntegrations(projectId);
		Task anomaly = assignedTask(TaskStatus.DONE, Priority.HIGH, LocalDateTime.of(2026, 9, 2, 0, 0), "[\"saga:code\"]");
		when(tasks.findDoneWithoutV23EvidenceCandidates(projectId, profile.getId()))
				.thenReturn(List.of(candidate(anomaly)));

		var alerts = service.get(account.getId(), course.getId()).actionableAlerts();
		assertEquals(1, alerts.size());
		assertEquals("MSR_ANOMALY", alerts.getFirst().type());
		verify(peerReviews, never()).countRemainingPeers(any(), any(), any());
	}

	@Test
	void peerPendingUsesCurrentSprintAndRemainingPeers() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(5L);
		stubEmptyIntegrations(projectId);
		Sprint active = sprint("active");
		active.setName("Sprint 3");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active));
		when(tasks.countGroupedByStatusForProjectAndSprint(projectId, active.getId())).thenReturn(List.of());
		when(peerReviews.countRemainingPeers(team.getId(), active.getId(), profile.getId())).thenReturn(4L);

		var first = service.get(account.getId(), course.getId()).actionableAlerts();
		assertEquals(1, first.size());
		assertEquals("PEER_REVIEW_PENDING:" + active.getId(), first.getFirst().id());
		assertEquals("PEER_REVIEW_PENDING", first.getFirst().type());
		assertEquals("INFO", first.getFirst().severity());
		assertEquals("PEER_REVIEW_PENDING", first.getFirst().actionType());
		assertEquals(4, first.getFirst().remainingPeers());
		assertEquals(course.getId(), first.getFirst().targetIds().courseId());
		assertEquals(team.getId(), first.getFirst().targetIds().teamId());
		assertEquals(projectId, first.getFirst().targetIds().projectId());
		assertEquals(active.getId(), first.getFirst().targetIds().sprintId());
		assertNull(first.getFirst().targetIds().taskId());
		assertEquals("You still have 4 teammate reviews to complete for Sprint 3.", first.getFirst().message());

		when(peerReviews.countRemainingPeers(team.getId(), active.getId(), profile.getId())).thenReturn(0L);
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
	}

	@Test
	void msrAlertsStayInAnomalyOrderThenPeer() {
		Team team = team(project());
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId()))
				.thenReturn(Optional.of(member(team, RoleInTeam.MEMBER)));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(3L);
		stubEmptyIntegrations(projectId);
		Sprint active = sprint("active");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active));
		when(tasks.countGroupedByStatusForProjectAndSprint(projectId, active.getId())).thenReturn(List.of());
		when(peerReviews.countRemainingPeers(team.getId(), active.getId(), profile.getId())).thenReturn(2L);
		Task later = assignedTask(TaskStatus.DONE, Priority.HIGHEST, LocalDateTime.of(2026, 9, 10, 0, 0), "[\"saga:code\"]");
		Task earlier = assignedTask(TaskStatus.DONE, Priority.LOW, LocalDateTime.of(2026, 9, 1, 0, 0), "[\"saga:test\"]");
		Task noDueHigh = assignedTask(TaskStatus.DONE, Priority.HIGHEST, null, "[\"saga:code\"]");
		Task noDueLow = assignedTask(TaskStatus.DONE, Priority.LOW, null, "[\"saga:test\"]");
		when(tasks.findDoneWithoutV23EvidenceCandidates(projectId, profile.getId()))
				.thenReturn(List.of(candidate(later), candidate(noDueLow), candidate(earlier), candidate(noDueHigh)));

		var alerts = service.get(account.getId(), course.getId()).actionableAlerts();
		assertEquals(5, alerts.size());
		assertEquals("MSR:" + earlier.getId(), alerts.get(0).id());
		assertEquals("MSR:" + later.getId(), alerts.get(1).id());
		assertEquals("MSR:" + noDueHigh.getId(), alerts.get(2).id());
		assertEquals("MSR:" + noDueLow.getId(), alerts.get(3).id());
		assertEquals("PEER_REVIEW_PENDING:" + active.getId(), alerts.get(4).id());
		assertEquals(2, alerts.get(4).remainingPeers());
		verify(identities, never()).countEligibleGithubIdentityAge(any());
	}

	@Test
	void ghostingFiresWhenAllGatesPassAndNoRecentV23Commit() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint active = eligibleSprint();
		stubGhostingEligible(team, membership, active, true);
		when(commits.existsAuthoredV23CommittedAtInRange(eq(team.getProject().getId()), eq(profile.getId()), any(), any()))
				.thenReturn(false);

		var alerts = service.get(account.getId(), course.getId()).actionableAlerts();
		assertEquals(1, alerts.size());
		assertEquals("GHOSTING:" + profile.getId() + ":" + course.getId(), alerts.getFirst().id());
		assertEquals("GHOSTING_WARNING", alerts.getFirst().type());
		assertEquals("WARNING", alerts.getFirst().severity());
		assertEquals("GHOSTING_WARNING", alerts.getFirst().actionType());
		assertEquals(StudentDashboardService.GHOSTING_MESSAGE, alerts.getFirst().message());
		assertFalse(alerts.getFirst().message().toLowerCase().contains("no work"));
		assertFalse(alerts.getFirst().message().contains("120"));
		assertEquals(course.getId(), alerts.getFirst().targetIds().courseId());
		assertEquals(team.getId(), alerts.getFirst().targetIds().teamId());
		assertEquals(team.getProject().getId(), alerts.getFirst().targetIds().projectId());
		assertEquals(active.getId(), alerts.getFirst().targetIds().sprintId());
		assertNull(alerts.getFirst().targetIds().taskId());
		assertNull(alerts.getFirst().remainingPeers());
	}

	@Test
	void ghostingIsSuppressedWhenRecentV23CommitExists() {
		Team team = team(project());
		stubGhostingEligible(team, eligibleMember(team), eligibleSprint(), true);
		when(commits.existsAuthoredV23CommittedAtInRange(eq(team.getProject().getId()), eq(profile.getId()), any(), any()))
				.thenReturn(true);
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
	}

	@Test
	void ghostingIsSuppressedByYoungSprintMembershipEnrollmentOrBlockedOnly() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint young = eligibleSprint();
		young.setStartDate(LocalDateTime.of(2026, 9, 17, 0, 0));
		stubGhostingEligible(team, membership, young, true);
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
		verify(identities, never()).countEligibleGithubIdentityAge(any());

		Sprint old = eligibleSprint();
		membership.setCreatedAt(LocalDateTime.of(2026, 9, 17, 8, 0));
		stubGhostingEligible(team, membership, old, true);
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());

		membership.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 17, 0, 0));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());

		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		stubGhostingEligible(team, membership, old, true);
		when(tasks.countStatusAndStoryPointsForAssignee(team.getProject().getId(), profile.getId()))
				.thenReturn(List.of(
						new Object[] {TaskStatus.DONE, 2L, 5L},
						new Object[] {TaskStatus.BLOCKED, 1L, 0L}));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
		verify(identities, never()).countEligibleGithubIdentityAge(any());
	}

	@Test
	void ghostingRequiresAllActiveReposAndAllConfirmedIdentitiesOldEnough() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint active = eligibleSprint();
		stubGhostingEligible(team, membership, active, true);
		when(repos.countAndMaxLastSyncedAtGroupedByStatus(team.getProject().getId()))
				.thenReturn(List.<Object[]>of(
						new Object[] {
							IntegrationStatus.ACTIVE, 2L, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 17, 0, 0)
						}));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
		verify(identities, never()).countEligibleGithubIdentityAge(any());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(team.getProject().getId()))
				.thenReturn(List.<Object[]>of(
						new Object[] {
							IntegrationStatus.ACTIVE, 1L, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 0, 0)
						},
						new Object[] {IntegrationStatus.REVOKED, 1L, LocalDateTime.of(2026, 9, 19, 0, 0), LocalDateTime.of(2026, 9, 19, 0, 0)}));
		when(identities.countEligibleGithubIdentityAge(account.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {2L, 0L, LocalDateTime.of(2026, 9, 17, 0, 0)}));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
		verify(commits, never()).existsAuthoredV23CommittedAtInRange(any(), any(), any(), any());

		when(identities.countEligibleGithubIdentityAge(account.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {1L, 1L, LocalDateTime.of(2026, 9, 1, 0, 0)}));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());

		when(identities.countEligibleGithubIdentityAge(account.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {0L, 0L, null}));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());

		when(repos.countAndMaxLastSyncedAtGroupedByStatus(team.getProject().getId()))
				.thenReturn(List.<Object[]>of(
						new Object[] {
							IntegrationStatus.ACTIVE, 2L, LocalDateTime.of(2026, 9, 1, 0, 0), LocalDateTime.of(2026, 9, 1, 8, 0)
						}));
		when(identities.countEligibleGithubIdentityAge(account.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {2L, 0L, LocalDateTime.of(2026, 9, 1, 0, 0)}));
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());
	}

	@Test
	void ghostingAgeGatesPassOnExactWindowStartDate() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint sprint = eligibleSprint();
		sprint.setStartDate(LocalDateTime.of(2026, 9, 16, 23, 59));
		membership.setCreatedAt(LocalDateTime.of(2026, 9, 16, 23, 59));
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 16, 23, 59));
		stubGhostingEligible(team, membership, sprint, true);
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());
	}

	@Test
	void ghostingAssignedWorkAllowsInProgressInReviewAndBlockedPlusTodo() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint active = eligibleSprint();
		stubGhostingEligible(team, membership, active, true);
		when(tasks.countStatusAndStoryPointsForAssignee(team.getProject().getId(), profile.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {TaskStatus.IN_PROGRESS, 1L, 2L}));
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());

		when(tasks.countStatusAndStoryPointsForAssignee(team.getProject().getId(), profile.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {TaskStatus.IN_REVIEW, 1L, 2L}));
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());

		when(tasks.countStatusAndStoryPointsForAssignee(team.getProject().getId(), profile.getId()))
				.thenReturn(List.of(
						new Object[] {TaskStatus.BLOCKED, 1L, 0L},
						new Object[] {TaskStatus.TODO, 1L, 1L}));
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());

		when(tasks.countStatusAndStoryPointsForAssignee(team.getProject().getId(), profile.getId()))
				.thenReturn(List.of());
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
	}

	@Test
	void ghostingTodayFollowsDashboardZoneWhilePersistedDatesStayNaive() {
		service = new StudentDashboardService(
				enrollments,
				members,
				jiraIntegrations,
				repos,
				sprints,
				tasks,
				commits,
				commitLinks,
				peerReviews,
				identities,
				Clock.fixed(Instant.parse("2026-09-20T17:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh")));
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint sprint = eligibleSprint();
		sprint.setStartDate(LocalDateTime.of(2026, 9, 17, 0, 0));
		membership.setCreatedAt(LocalDateTime.of(2026, 9, 17, 0, 0));
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 17, 0, 0));
		stubGhostingEligible(team, membership, sprint, true);
		assertEquals("GHOSTING_WARNING", service.get(account.getId(), course.getId()).actionableAlerts().getFirst().type());

		clearInvocations(identities);
		sprint.setStartDate(LocalDateTime.of(2026, 9, 18, 0, 0));
		assertTrue(service.get(account.getId(), course.getId()).actionableAlerts().isEmpty());
		verify(identities, never()).countEligibleGithubIdentityAge(any());
	}

	@Test
	void ghostingSitsBetweenMsrAndPeer() {
		Team team = team(project());
		TeamMember membership = eligibleMember(team);
		Sprint active = eligibleSprint();
		stubGhostingEligible(team, membership, active, true);
		when(peerReviews.countRemainingPeers(team.getId(), active.getId(), profile.getId())).thenReturn(1L);
		Task anomaly = assignedTask(TaskStatus.DONE, Priority.HIGH, LocalDateTime.of(2026, 9, 2, 0, 0), "[\"saga:code\"]");
		when(tasks.findDoneWithoutV23EvidenceCandidates(team.getProject().getId(), profile.getId()))
				.thenReturn(List.of(candidate(anomaly)));
		when(commits.existsAuthoredV23CommittedAtInRange(eq(team.getProject().getId()), eq(profile.getId()), any(), any()))
				.thenReturn(false);

		var alerts = service.get(account.getId(), course.getId()).actionableAlerts();
		assertEquals(3, alerts.size());
		assertEquals("MSR_ANOMALY", alerts.get(0).type());
		assertEquals("GHOSTING_WARNING", alerts.get(1).type());
		assertEquals("PEER_REVIEW_PENDING", alerts.get(2).type());
		assertEquals("GHOSTING:" + profile.getId() + ":" + course.getId(), alerts.get(1).id());
	}

	private void stubEmptyIntegrations(UUID projectId) {
		lenient().when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		lenient().when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId)).thenReturn(List.of());
		lenient().when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		lenient().when(tasks.countStatusAndStoryPointsForAssignee(projectId, profile.getId())).thenReturn(List.of());
		lenient()
				.when(tasks.findAttentionNonDoneByProjectAndAssignee(eq(projectId), eq(profile.getId()), any(Pageable.class)))
				.thenReturn(List.of());
		lenient().when(tasks.findDoneWithoutV23EvidenceCandidates(projectId, profile.getId())).thenReturn(List.of());
		lenient()
				.when(commits.countAndMaxCommittedAtByProjectAndAuthor(projectId, profile.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {0L, null}));
		lenient()
				.when(commits.findRecentAuthoredV23ByProject(eq(projectId), eq(profile.getId()), any(Pageable.class)))
				.thenReturn(List.of());
		lenient()
				.when(commits.findWeeklyCommittedAtByProjectAndAuthor(eq(projectId), eq(profile.getId()), any(), any()))
				.thenReturn(List.of());
	}

	private static StudentDashboardAnomalyCandidateRow candidate(Task task) {
		return new StudentDashboardAnomalyCandidateRow(
				task.getId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus(),
				task.getPriority(),
				task.getStoryPoint(),
				task.getDueDate(),
				task.getLabelsJson());
	}

	private Task assignedTask(TaskStatus status, Priority priority, LocalDateTime due, String labelsJson) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setTitle(status.name());
		task.setStatus(status);
		task.setPriority(priority);
		task.setDueDate(due);
		task.setLabelsJson(labelsJson);
		task.setExternalKey("SAGA-" + status.name());
		return task;
	}

	private static GitCommit authoredCommit(String sha, String message, String fullName) {
		GitRepo repo = new GitRepo();
		repo.setFullName(fullName);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setRepo(repo);
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 4, 9, 0));
		return commit;
	}

	private Course course(String subjectCode, String subjectName, String semesterCode) {
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode(subjectCode);
		subject.setName(subjectName);
		Semester semester = new Semester();
		semester.setId(UUID.randomUUID());
		semester.setCode(semesterCode);
		Course row = new Course();
		row.setId(UUID.randomUUID());
		row.setCourseCode(subjectCode + "-SE18");
		row.setSubject(subject);
		row.setSemester(semester);
		return row;
	}

	private CourseEnrollment enrollment(Course owned, EnrollmentStatus status) {
		CourseEnrollment row = new CourseEnrollment();
		row.setId(UUID.randomUUID());
		row.setStudentProfile(profile);
		row.setCourse(owned);
		row.setEnrollmentStatus(status);
		row.setEnrolledAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		return row;
	}

	private static Team team(Project project) {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(1);
		team.setName("Alpha");
		team.setProject(project);
		return team;
	}

	private static Project project() {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		project.setName("SAGA");
		return project;
	}

	private void stubGhostingEligible(Team team, TeamMember membership, Sprint active, boolean githubConnected) {
		UUID projectId = team.getProject().getId();
		when(enrollments.findFetchedActiveByUserAndCourse(account.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findFetchedByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.of(membership));
		when(members.countActiveByTeam_Id(team.getId())).thenReturn(3L);
		stubEmptyIntegrations(projectId);
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active));
		when(tasks.countGroupedByStatusForProjectAndSprint(projectId, active.getId())).thenReturn(List.of());
		when(tasks.countStatusAndStoryPointsForAssignee(projectId, profile.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {TaskStatus.TODO, 1L, 3L}));
		if (githubConnected) {
			when(repos.countAndMaxLastSyncedAtGroupedByStatus(projectId))
					.thenReturn(List.<Object[]>of(
							new Object[] {
								IntegrationStatus.ACTIVE,
								1L,
								LocalDateTime.of(2026, 9, 1, 8, 0),
								LocalDateTime.of(2026, 9, 1, 0, 0)
							}));
		}
		lenient()
				.when(identities.countEligibleGithubIdentityAge(account.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {1L, 0L, LocalDateTime.of(2026, 9, 1, 0, 0)}));
		lenient()
				.when(commits.existsAuthoredV23CommittedAtInRange(eq(projectId), eq(profile.getId()), any(), any()))
				.thenReturn(false);
	}

	private static TeamMember eligibleMember(Team team) {
		TeamMember member = member(team, RoleInTeam.MEMBER);
		member.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		return member;
	}

	private static Sprint eligibleSprint() {
		Sprint sprint = sprint("active");
		sprint.setStartDate(LocalDateTime.of(2026, 9, 16, 0, 0));
		return sprint;
	}

	private static TeamMember member(Team team, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setRoleInTeam(role);
		return member;
	}

	private static Sprint sprint(String state) {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setExternalSprintId("ext-" + state);
		sprint.setName("Sprint " + state);
		sprint.setState(state);
		return sprint;
	}
}

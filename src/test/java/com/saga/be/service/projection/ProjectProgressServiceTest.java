package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.project.ProjectMemberProgressResponse;
import com.saga.be.dto.project.ProjectProgressResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.sync.ProjectManualSyncService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectProgressServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private SprintRepository sprints;
	@Mock
	private TeamRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private TaskWorkSessionRepository workSessions;
	@Mock
	private ContributionConfirmationRepository confirmations;
	@Mock
	private TaskFileRepository files;
	@Mock
	private TaskWebLinkRepository webLinks;
	@Mock
	private UserAccountRepository users;
	@Mock
	private ProjectRepository projects;
	@Mock
	private ProjectIntegrationService integrations;
	@Mock
	private ProjectManualSyncService manualSync;

	private ProjectDataAuthorization authorization;
	private ProjectProgressService service;
	private UUID projectId;
	private UUID userId;
	private Team team;

	@BeforeEach
	void setUp() {
		authorization = new ProjectDataAuthorization(users, members, projects);
		service = new ProjectProgressService(
				tasks, commits, links, sprints, teams, members, workSessions, confirmations, files, webLinks,
				authorization, integrations, manualSync);
		projectId = UUID.randomUUID();
		userId = UUID.randomUUID();
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(1);
		team.setName("Team 1");
	}

	// ---------- AUTH ----------

	@Test
	void adminDeniedProjectProgress() {
		// Same policy as ProjectDataAuthorization#requireReader — not silently expanded.
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.getProjectProgress(userId, projectId)).isInstanceOf(IntegrationException.class);
	}

	@Test
	void lecturerAssignedAllowed() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		stubEmptyAggregates();
		assertThat(service.getProjectProgress(userId, projectId)).isNotNull();
	}

	@Test
	void lecturerUnrelatedCourseDenied() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.getProjectProgress(userId, projectId)).isInstanceOf(AcademicException.class);
	}

	@Test
	void studentOtherTeamDenied() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.getProjectProgress(userId, projectId)).isInstanceOf(IntegrationException.class);
	}

	@Test
	void teamLeaderOwnProjectAllowed() {
		stubActiveLeader();
		stubEmptyAggregates();
		ProjectProgressResponse response = service.getProjectProgress(userId, projectId);
		assertThat(response.projectId()).isEqualTo(projectId);
	}

	@Test
	void ordinaryMemberDenied_progressIsMoreSensitiveThanRawTaskAndCommitLists() {
		// Product requirement: unlike ProjectDataAuthorization#requireReader (which allows any
		// ACTIVE member to read raw Task/Commit/Sprint lists), the aggregated progress dashboard
		// must deny an ordinary MEMBER — only the ACTIVE Team Leader or an assigned Lecturer.
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		assertThatThrownBy(() -> service.getProjectProgress(userId, projectId)).isInstanceOf(IntegrationException.class);
	}

	@Test
	void teamNotFoundForProjectThrows() {
		stubActiveLeader();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.getProjectProgress(userId, projectId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	// ---------- TASK SUMMARY ----------

	@Test
	void zeroTasksYieldsNullCompletionPercent() {
		stubActiveLeader();
		stubEmptyAggregates();
		ProjectProgressResponse response = service.getProjectProgress(userId, projectId);
		assertThat(response.taskSummary().total()).isZero();
		assertThat(response.taskSummary().completionPercent()).isNull();
	}

	@Test
	void mixedStatusesAggregateCorrectlyAcrossAllFiveStatuses() {
		stubActiveLeader();
		stubEmptyAggregates();
		UUID studentA = UUID.randomUUID();
		when(tasks.countGroupedByAssigneeAndStatus(projectId))
				.thenReturn(List.of(
						row(studentA, TaskStatus.TODO, 2L),
						row(null, TaskStatus.TODO, 1L),
						row(studentA, TaskStatus.IN_PROGRESS, 1L),
						row(null, TaskStatus.IN_REVIEW, 1L),
						row(studentA, TaskStatus.DONE, 3L),
						row(null, TaskStatus.BLOCKED, 1L)));

		ProjectProgressResponse response = service.getProjectProgress(userId, projectId);

		ProjectProgressResponse.TaskSummary summary = response.taskSummary();
		assertThat(summary.total()).isEqualTo(9);
		assertThat(summary.todo()).isEqualTo(3);
		assertThat(summary.inProgress()).isEqualTo(1);
		assertThat(summary.inReview()).isEqualTo(1);
		assertThat(summary.done()).isEqualTo(3);
		assertThat(summary.blocked()).isEqualTo(1);
		assertThat(summary.completionPercent()).isEqualTo((3 * 100.0) / 9);
	}

	// ---------- SPRINT ----------

	@Test
	void noActiveSprintYieldsNull() {
		stubActiveLeader();
		stubEmptyAggregates();
		Sprint closed = sprint("closed");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(closed));
		assertThat(service.getProjectProgress(userId, projectId).currentSprint()).isNull();
	}

	@Test
	void oneActiveSprintIncludedWithTaskCounts() {
		stubActiveLeader();
		stubEmptyAggregates();
		Sprint active = sprint("active");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active));
		when(tasks.countByProject_IdAndSprint_IdAndDeletedAtIsNull(projectId, active.getId())).thenReturn(5L);
		when(tasks.countByProject_IdAndSprint_IdAndStatusAndDeletedAtIsNull(projectId, active.getId(), TaskStatus.DONE))
				.thenReturn(2L);

		ProjectProgressResponse.SprintSummary summary = service.getProjectProgress(userId, projectId).currentSprint();

		assertThat(summary).isNotNull();
		assertThat(summary.id()).isEqualTo(active.getId());
		assertThat(summary.totalTasks()).isEqualTo(5);
		assertThat(summary.completedTasks()).isEqualTo(2);
	}

	@Test
	void multipleActiveSprintsPicksDeterministicFirstFromExistingOrdering() {
		stubActiveLeader();
		stubEmptyAggregates();
		Sprint first = sprint("active");
		Sprint second = sprint("active");
		// findActiveByProject_Id already orders desc by startDate/createdAt; the service must not
		// re-sort or pick arbitrarily — it takes the first element of whatever order the query gives.
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(first, second));

		ProjectProgressResponse.SprintSummary summary = service.getProjectProgress(userId, projectId).currentSprint();

		assertThat(summary.id()).isEqualTo(first.getId());
	}

	@Test
	void revokedJiraStillReturnsLastKnownActiveSprintButSyncStatusExposesRevoked() {
		// Revoking Jira does not retroactively rewrite a sprint row last synced with
		// state="active" — /progress correctly keeps returning that last-known projection (no
		// live Jira call, no projection deletion) as long as sync.jiraStatus clearly labels the
		// connection as REVOKED so the FE does not mistake it for currently-live data.
		stubActiveLeader();
		stubEmptyAggregates();
		Sprint staleActive = sprint("active");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(staleActive));
		when(integrations.summary(userId, projectId))
				.thenReturn(new ProjectIntegrationsResponse(
						null,
						new ProjectIntegrationsResponse.JiraIntegrationSummary(
								"cloud-1", "Old Site", "SAGA", "42", "REVOKED")));

		ProjectProgressResponse response = service.getProjectProgress(userId, projectId);

		assertThat(response.currentSprint()).isNotNull();
		assertThat(response.currentSprint().id()).isEqualTo(staleActive.getId());
		assertThat(response.sync().jiraStatus()).isEqualTo("REVOKED");
	}

	// ---------- COMMITS ----------

	@Test
	void commitSummaryMapsTotalsLinkedAndLastCommit() {
		stubActiveLeader();
		stubEmptyAggregates();
		LocalDateTime last = LocalDateTime.now();
		when(commits.countByRepo_Project_Id(projectId)).thenReturn(42L);
		when(links.countDistinctLinkedCommitsByProject_Id(projectId)).thenReturn(10L);
		when(commits.findMaxCommittedAtByProject_Id(projectId)).thenReturn(last);

		ProjectProgressResponse.CommitSummary summary = service.getProjectProgress(userId, projectId).commitSummary();

		assertThat(summary.total()).isEqualTo(42);
		assertThat(summary.linked()).isEqualTo(10);
		assertThat(summary.lastCommitAt()).isEqualTo(last);
	}

	// ---------- EVIDENCE ----------

	@Test
	void evidenceSummaryPassesThroughRealCounts() {
		stubActiveLeader();
		stubEmptyAggregates();
		when(workSessions.countByProject_Id(projectId)).thenReturn(4L);
		when(files.countByTask_Project_Id(projectId)).thenReturn(2L);
		when(webLinks.countByTask_Project_Id(projectId)).thenReturn(1L);
		when(confirmations.countByProject_Id(projectId)).thenReturn(3L);

		ProjectProgressResponse.EvidenceSummary summary =
				service.getProjectProgress(userId, projectId).evidenceSummary();

		assertThat(summary.workSessions()).isEqualTo(4);
		assertThat(summary.files()).isEqualTo(2);
		assertThat(summary.webLinks()).isEqualTo(1);
		assertThat(summary.confirmations()).isEqualTo(3);
	}

	// ---------- MEMBER ATTRIBUTION ----------

	@Test
	void memberWithAssignedCompletedAndIncompleteTasks() {
		stubActiveLeader();
		stubEmptyAggregates();
		TeamMember leader = teamMember(RoleInTeam.LEADER, "leader@fpt.edu.vn", "SE001");
		UUID studentId = leader.getCourseEnrollment().getStudentProfile().getId();
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leader));
		when(tasks.countGroupedByAssigneeAndStatus(projectId))
				.thenReturn(List.of(row(studentId, TaskStatus.DONE, 2L), row(studentId, TaskStatus.TODO, 1L)));

		List<ProjectProgressResponse.MemberSummary> memberProgress =
				service.getProjectProgress(userId, projectId).memberProgress();

		assertThat(memberProgress).hasSize(1);
		ProjectProgressResponse.TaskAttribution attribution = memberProgress.getFirst().taskSummary();
		assertThat(attribution.assigned()).isEqualTo(3);
		assertThat(attribution.completed()).isEqualTo(2);
		assertThat(attribution.incomplete()).isEqualTo(1);
	}

	@Test
	void memberWithNoTasksShowsExplicitZeroNotOmitted() {
		stubActiveLeader();
		stubEmptyAggregates();
		TeamMember member = teamMember(RoleInTeam.MEMBER, "member@fpt.edu.vn", "SE002");
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member));

		List<ProjectProgressResponse.MemberSummary> memberProgress =
				service.getProjectProgress(userId, projectId).memberProgress();

		assertThat(memberProgress).hasSize(1);
		assertThat(memberProgress.getFirst().taskSummary().assigned()).isZero();
		assertThat(memberProgress.getFirst().commitSummary().total()).isZero();
	}

	@Test
	void unresolvedAssigneeNotAttributedToAnyMemberButCountsInProjectTotal() {
		stubActiveLeader();
		stubEmptyAggregates();
		TeamMember member = teamMember(RoleInTeam.MEMBER, "member@fpt.edu.vn", "SE002");
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member));
		// null studentId = Jira assignee could not be mapped to any SAGA student (row[0] == null).
		when(tasks.countGroupedByAssigneeAndStatus(projectId))
				.thenReturn(List.<Object[]>of(row(null, TaskStatus.TODO, 5L)));

		ProjectProgressResponse response = service.getProjectProgress(userId, projectId);

		assertThat(response.taskSummary().total()).isEqualTo(5);
		assertThat(response.memberProgress().getFirst().taskSummary().assigned()).isZero();
	}

	@Test
	void distinctCommitAndTaskCountsPassThroughWithoutReinflation() {
		stubActiveLeader();
		stubEmptyAggregates();
		TeamMember member = teamMember(RoleInTeam.MEMBER, "member@fpt.edu.vn", "SE002");
		UUID studentId = member.getCourseEnrollment().getStudentProfile().getId();
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member));
		when(commits.countAndMaxCommittedAtGroupedByAuthorStudent(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {studentId, 5L, LocalDateTime.now()}));
		// One commit linked to two tasks: repository already returns DISTINCT counts (1 commit, 2 tasks).
		when(links.countLinkedCommitsAndTasksGroupedByAuthorStudent(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {studentId, 1L, 2L}));

		ProjectProgressResponse.CommitAttribution attribution =
				service.getProjectProgress(userId, projectId).memberProgress().getFirst().commitSummary();

		assertThat(attribution.total()).isEqualTo(5);
		assertThat(attribution.linkedToTasks()).isEqualTo(1);
		assertThat(attribution.tasksWithLinkedCommits()).isEqualTo(2);
	}

	// ---------- MEMBER DETAIL ----------

	@Test
	void leaderViewsOwnActiveTeamMemberAllowed() {
		stubActiveLeader();
		TeamMember member = teamMember(RoleInTeam.LEADER, "leader@fpt.edu.vn", "SE001");
		UUID studentId = member.getCourseEnrollment().getStudentProfile().getId();
		UUID accountId = member.getCourseEnrollment().getStudentProfile().getUserAccount().getId();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member));
		Task done = task(TaskStatus.DONE);
		Task todo = task(TaskStatus.TODO);
		when(tasks.findActiveFetchedByProject_IdAndAssigneeStudent_Id(projectId, studentId))
				.thenReturn(List.of(done, todo));
		when(commits.countAndMaxCommittedAtGroupedByAuthorStudent(projectId)).thenReturn(List.of());
		when(links.countLinkedCommitsAndTasksGroupedByAuthorStudent(projectId)).thenReturn(List.of());
		when(workSessions.countByProject_IdAndUser_Id(projectId, accountId)).thenReturn(3L);

		ProjectMemberProgressResponse detail = service.getMemberProgress(userId, projectId, studentId);

		assertThat(detail.assignedTasks()).hasSize(2);
		assertThat(detail.taskSummary().assigned()).isEqualTo(2);
		assertThat(detail.taskSummary().completed()).isEqualTo(1);
		assertThat(detail.taskSummary().incomplete()).isEqualTo(1);
		assertThat(detail.evidenceSummary().workSessions()).isEqualTo(3);
	}

	@Test
	void lecturerCanViewActiveTeamMemberDetail() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		TeamMember member = teamMember(RoleInTeam.MEMBER, "member@fpt.edu.vn", "SE002");
		UUID studentId = member.getCourseEnrollment().getStudentProfile().getId();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(member));
		when(tasks.findActiveFetchedByProject_IdAndAssigneeStudent_Id(projectId, studentId)).thenReturn(List.of());
		when(commits.countAndMaxCommittedAtGroupedByAuthorStudent(projectId)).thenReturn(List.of());
		when(links.countLinkedCommitsAndTasksGroupedByAuthorStudent(projectId)).thenReturn(List.of());

		ProjectMemberProgressResponse detail = service.getMemberProgress(userId, projectId, studentId);

		assertThat(detail.studentId()).isEqualTo(studentId);
	}

	@Test
	void memberFromDifferentTeamDenied() {
		// The target studentId is a real, active team member — just not of the team that owns
		// projectId. Membership is verified against the ACTUAL team resolved from projectId, not
		// merely "does some team, somewhere, have this student active."
		stubActiveLeader();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		TeamMember memberOfAnotherTeam = teamMember(RoleInTeam.MEMBER, "other-team@fpt.edu.vn", "SE099");
		UUID otherTeamStudentId = memberOfAnotherTeam.getCourseEnrollment().getStudentProfile().getId();
		// This project's own team roster does not include that student at all.
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of());

		assertThatThrownBy(() -> service.getMemberProgress(userId, projectId, otherTeamStudentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	void withdrawnFormerMemberDenied() {
		// Was genuinely on this exact team at some point (row is returned by
		// findFetchedByTeam_Id), but their enrollment is no longer ACTIVE — must be excluded the
		// same as someone who was never on the team at all, not merely "aggregate returns zero."
		stubActiveLeader();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		TeamMember withdrawn = teamMember(RoleInTeam.MEMBER, "withdrawn@fpt.edu.vn", "SE050");
		withdrawn.getCourseEnrollment().setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		UUID withdrawnStudentId = withdrawn.getCourseEnrollment().getStudentProfile().getId();
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(withdrawn));

		assertThatThrownBy(() -> service.getMemberProgress(userId, projectId, withdrawnStudentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	void arbitraryValidStudentIdNotOnTeamDenied() {
		// Team has other real ACTIVE members, but the requested studentId matches none of them.
		stubActiveLeader();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		TeamMember someoneElse = teamMember(RoleInTeam.MEMBER, "someone-else@fpt.edu.vn", "SE077");
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(someoneElse));
		UUID randomStudentId = UUID.randomUUID();

		assertThatThrownBy(() -> service.getMemberProgress(userId, projectId, randomStudentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	void memberDetailForNonMemberThrowsNotFound() {
		stubActiveLeader();
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of());
		UUID randomStudentId = UUID.randomUUID();
		assertThatThrownBy(() -> service.getMemberProgress(userId, projectId, randomStudentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	// ---------- helpers ----------

	private void stubActiveLeader() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	private void stubEmptyAggregates() {
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of());
		when(tasks.countGroupedByAssigneeAndStatus(projectId)).thenReturn(List.of());
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		when(commits.countAndMaxCommittedAtGroupedByAuthorStudent(projectId)).thenReturn(List.of());
		when(links.countLinkedCommitsAndTasksGroupedByAuthorStudent(projectId)).thenReturn(List.of());
		when(integrations.summary(userId, projectId))
				.thenReturn(new ProjectIntegrationsResponse(null, null));
		when(manualSync.latestStatus(userId, projectId)).thenReturn(List.of());
	}

	private static Object[] row(UUID studentId, TaskStatus status, long count) {
		return new Object[] {studentId, status, count};
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}

	private TeamMember teamMember(RoleInTeam role, String email, String studentCode) {
		UserAccount studentAccount = new UserAccount();
		studentAccount.setId(UUID.randomUUID());
		studentAccount.setEmail(email);
		studentAccount.setFullName(email);
		studentAccount.setAccountRole(AccountRole.STUDENT);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(studentAccount);
		profile.setStudentCode(studentCode);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private Sprint sprint(String state) {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setName("Sprint " + state);
		sprint.setState(state);
		return sprint;
	}

	private Task task(TaskStatus status) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-" + UUID.randomUUID().toString().substring(0, 4));
		task.setTitle("Task");
		task.setStatus(status);
		return task;
	}
}

package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.SprintActivityResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDate;
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
class SprintActivityAnalyticsServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private SprintRepository sprints;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private UserAccountRepository users;
	@Mock
	private ProjectRepository projects;

	private SprintActivityAnalyticsService service;
	private UUID projectId;
	private UUID userId;
	private UUID studentId;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new SprintActivityAnalyticsService(
				tasks, commits, links, sprints, students, members, users, authorization);
		projectId = UUID.randomUUID();
		userId = UUID.randomUUID();
		studentId = UUID.randomUUID();
	}

	@Test
	void adminDenied() {
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.ADMIN)));
		assertThatThrownBy(() -> service.getSprintActivity(userId, projectId, null))
				.isInstanceOf(IntegrationException.class);
	}

	@Test
	void ordinaryMemberAllowed_unlikeProjectProgressDashboard() {
		stubActiveMember();
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId)).thenReturn(List.of());

		SprintActivityResponse response = service.getSprintActivity(userId, projectId, null);

		assertThat(response.sprints()).isEmpty();
		assertThat(response.generatedAt()).isNotNull();
		verify(tasks, never()).countGroupedBySprintAndStatus(projectId);
	}

	@Test
	void studentIgnoresRequestedTeammateStudentId() {
		stubActiveMember();
		UUID teammateId = UUID.randomUUID();
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId)).thenReturn(List.of());

		service.getSprintActivity(userId, projectId, teammateId);

		verify(tasks).countGroupedBySprintAndStatusForAssignee(projectId, studentId);
		verify(members, never()).existsActiveByProjectIdAndStudentProfileId(projectId, teammateId);
	}

	@Test
	void lecturerUnrelatedCourseDenied() {
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.LECTURER)));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.getSprintActivity(userId, projectId, null))
				.isInstanceOf(AcademicException.class);
	}

	@Test
	void lecturerWithoutStudentIdGetsProjectWideTotals() {
		stubAssignedLecturer();
		Sprint sprint = sprint(
				"active",
				LocalDateTime.of(2026, 9, 10, 0, 0),
				LocalDateTime.of(2026, 9, 24, 23, 59));
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint));
		when(tasks.countGroupedBySprintAndStatus(projectId))
				.thenReturn(List.of(
						taskRow(sprint.getId(), TaskStatus.TODO, 2L),
						taskRow(sprint.getId(), TaskStatus.IN_PROGRESS, 3L),
						taskRow(sprint.getId(), TaskStatus.IN_REVIEW, 2L),
						taskRow(sprint.getId(), TaskStatus.DONE, 5L)));
		when(links.findLinkedCommitIdsBySprint(projectId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProject(projectId)).thenReturn(List.of());

		SprintActivityResponse.SprintActivity row =
				service.getSprintActivity(userId, projectId, null).sprints().getFirst();

		assertThat(row.sprintId()).isEqualTo(sprint.getId());
		assertThat(row.sprintName()).isEqualTo("SAGA Sprint 4");
		assertThat(row.state()).isEqualTo("ACTIVE");
		assertThat(row.startDate()).isEqualTo(LocalDate.of(2026, 9, 10));
		assertThat(row.endDate()).isEqualTo(LocalDate.of(2026, 9, 24));
		assertThat(row.tasks().total()).isEqualTo(12);
		assertThat(row.tasks().todo()).isEqualTo(2);
		assertThat(row.tasks().inProgress()).isEqualTo(3);
		assertThat(row.tasks().inReview()).isEqualTo(2);
		assertThat(row.tasks().done()).isEqualTo(5);
		verify(tasks, never()).countGroupedBySprintAndStatusForAssignee(projectId, studentId);
	}

	@Test
	void lecturerStudentIdMustBeActiveTeamMember() {
		stubAssignedLecturer();
		UUID unknown = UUID.randomUUID();
		when(members.existsActiveByProjectIdAndStudentProfileId(projectId, unknown)).thenReturn(false);

		assertThatThrownBy(() -> service.getSprintActivity(userId, projectId, unknown))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	void lecturerCanScopeToOneActiveMember() {
		stubAssignedLecturer();
		when(members.existsActiveByProjectIdAndStudentProfileId(projectId, studentId)).thenReturn(true);
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId)).thenReturn(List.of());

		service.getSprintActivity(userId, projectId, studentId);

		verify(tasks).countGroupedBySprintAndStatusForAssignee(projectId, studentId);
	}

	@Test
	void blockedTasksCountInTotalOnly() {
		stubActiveMember();
		Sprint sprint = sprint("closed", LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 14, 0, 0));
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint));
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId))
				.thenReturn(List.of(
						taskRow(sprint.getId(), TaskStatus.TODO, 1L),
						taskRow(sprint.getId(), TaskStatus.BLOCKED, 2L),
						taskRow(sprint.getId(), TaskStatus.DONE, 1L)));
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId)).thenReturn(List.of());

		SprintActivityResponse.TaskCounts counts =
				service.getSprintActivity(userId, projectId, null).sprints().getFirst().tasks();

		assertThat(counts.total()).isEqualTo(4);
		assertThat(counts.todo()).isEqualTo(1);
		assertThat(counts.done()).isEqualTo(1);
		assertThat(counts.inProgress()).isZero();
	}

	@Test
	void linkedAndUnlinkedCommitsUseSprintWindowAndDistinctIds() {
		stubActiveMember();
		LocalDateTime start = LocalDateTime.of(2026, 9, 10, 0, 0);
		LocalDateTime end = LocalDateTime.of(2026, 9, 24, 23, 59);
		Sprint sprint = sprint("active", start, end);
		UUID linkedA = UUID.randomUUID();
		UUID linkedAgain = linkedA;
		UUID linkedB = UUID.randomUUID();
		UUID unlinkedInWindow = UUID.randomUUID();
		UUID outsideWindow = UUID.randomUUID();
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint));
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId))
				.thenReturn(List.of(
						new Object[] {sprint.getId(), linkedA},
						new Object[] {sprint.getId(), linkedAgain},
						new Object[] {sprint.getId(), linkedB}));
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId))
				.thenReturn(List.of(
						new Object[] {linkedA, start.plusDays(1)},
						new Object[] {linkedB, start.plusDays(2)},
						new Object[] {unlinkedInWindow, start.plusDays(3)},
						new Object[] {outsideWindow, start.minusDays(5)}));

		SprintActivityResponse.CommitCounts counts =
				service.getSprintActivity(userId, projectId, null).sprints().getFirst().commits();

		assertThat(counts.linked()).isEqualTo(2);
		assertThat(counts.unlinked()).isEqualTo(1);
	}

	@Test
	void missingSprintDatesYieldZeroUnlinked() {
		stubActiveMember();
		Sprint sprint = sprint("future", null, null);
		UUID unlinked = UUID.randomUUID();
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint));
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId))
				.thenReturn(List.<Object[]>of(new Object[] {unlinked, LocalDateTime.of(2026, 9, 12, 10, 0)}));

		assertThat(service.getSprintActivity(userId, projectId, null).sprints().getFirst().commits().unlinked())
				.isZero();
	}

	@Test
	void sprintWithNoPersonalWorkStillReturnedAsZeros() {
		stubActiveMember();
		Sprint sprint = sprint(
				"active",
				LocalDateTime.of(2026, 9, 10, 0, 0),
				LocalDateTime.of(2026, 9, 24, 23, 59));
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(sprint));
		when(tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId)).thenReturn(List.of());
		when(links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId)).thenReturn(List.of());
		when(commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId)).thenReturn(List.of());

		SprintActivityResponse.SprintActivity row =
				service.getSprintActivity(userId, projectId, null).sprints().getFirst();

		assertThat(row.tasks().total()).isZero();
		assertThat(row.commits().linked()).isZero();
		assertThat(row.commits().unlinked()).isZero();
	}

	private void stubActiveMember() {
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.STUDENT)));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
		StudentProfile profile = new StudentProfile();
		profile.setId(studentId);
		when(students.findByUserAccount_Id(userId)).thenReturn(Optional.of(profile));
	}

	private void stubAssignedLecturer() {
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.LECTURER)));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}

	private static Sprint sprint(String state, LocalDateTime start, LocalDateTime end) {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setName("SAGA Sprint 4");
		sprint.setState(state);
		sprint.setStartDate(start);
		sprint.setEndDate(end);
		return sprint;
	}

	private static Object[] taskRow(UUID sprintId, TaskStatus status, long count) {
		return new Object[] {sprintId, status, count};
	}
}

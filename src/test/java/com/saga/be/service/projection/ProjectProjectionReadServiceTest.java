package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectProjectionReadServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private com.saga.be.repository.SprintRepository sprints;
	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;

	private ProjectDataAuthorization authorization;
	private ProjectProjectionReadService service;
	private UUID projectId;
	private UUID userId;

	@BeforeEach
	void setUp() {
		authorization = new ProjectDataAuthorization(users, members, projects);
		service = new ProjectProjectionReadService(tasks, commits, links, sprints, authorization);
		projectId = UUID.randomUUID();
		userId = UUID.randomUUID();
	}

	@Test
	void studentMember_canListTasksWithBatchedLinkCounts() {
		stubStudent(RoleInTeam.MEMBER);
		List<Task> rows = tasks(10);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(rows);
		AtomicInteger countQueries = new AtomicInteger();
		when(links.countLinksByProjectGrouped(projectId)).thenAnswer(inv -> {
			countQueries.incrementAndGet();
			List<Object[]> aggregates = new ArrayList<>();
			for (Task task : rows) {
				aggregates.add(new Object[] {task.getId(), 2L});
			}
			return aggregates;
		});

		List<ProjectTaskResponse> result = service.listTasks(userId, projectId);

		assertThat(result).hasSize(10);
		assertThat(result.getFirst().linkedCommitCount()).isEqualTo(2);
		assertThat(countQueries.get()).isEqualTo(1);
	}

	@Test
	void linkedCommitCount_queryCountConstant_for10Vs100Tasks() {
		stubStudent(RoleInTeam.LEADER);
		AtomicInteger countQueries = new AtomicInteger();
		when(links.countLinksByProjectGrouped(projectId)).thenAnswer(inv -> {
			countQueries.incrementAndGet();
			return List.of();
		});
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(tasks(10));
		service.listTasks(userId, projectId);
		int after10 = countQueries.get();
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(tasks(100));
		service.listTasks(userId, projectId);
		int after100 = countQueries.get();
		assertThat(after10).isEqualTo(1);
		assertThat(after100 - after10).isEqualTo(1);
	}

	@Test
	void studentOtherTeam_forbidden() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.listTasks(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(tasks, never()).findActiveFetchedByProject_Id(any());
	}

	@Test
	void assignedLecturer_canRead() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of());
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());
		assertThat(service.listTasks(userId, projectId)).isEmpty();
	}

	@Test
	void unrelatedLecturer_forbidden() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.listCommits(userId, projectId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
	}

	@Test
	void admin_denied() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.listTasks(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(members, never()).existsActiveByProjectIdAndUserId(any(), any());
		verify(projects, never()).existsAssignedToLecturerUser(any(), any());
	}

	@Test
	void taskCommits_mismatchedProject_notFound() {
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, taskId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(links, never()).findFetchedCommitsByProjectAndTask(any(), any());
	}

	@Test
	void taskCommits_returnsPersistedLinksOnly() {
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		Task task = new Task();
		task.setId(taskId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.of(task));
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName("org/saga");
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash("abc");
		commit.setMessage("SAGA-1");
		commit.setRepo(repo);
		commit.setHeadRef("main");
		when(links.findFetchedCommitsByProjectAndTask(projectId, taskId)).thenReturn(List.of(commit));

		List<ProjectCommitResponse> result = service.listTaskCommits(userId, projectId, taskId);
		assertThat(result).hasSize(1);
		assertThat(result.getFirst().sha()).isEqualTo("abc");
		assertThat(result.getFirst().headRef()).isEqualTo("main");
		verify(links, times(1)).findFetchedCommitsByProjectAndTask(projectId, taskId);
	}

	@Test
	void taskCommits_nullHeadRef_staysNullSafely() {
		// BUG 2 fix: the commit API must expose stored headRef -- but a commit synced before
		// headRef was populated, or lacking branch metadata, must not break the response.
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		Task task = new Task();
		task.setId(taskId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.of(task));
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName("org/saga");
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setShaHash("def");
		commit.setMessage("SAGA-2");
		commit.setRepo(repo);
		commit.setHeadRef(null);
		when(links.findFetchedCommitsByProjectAndTask(projectId, taskId)).thenReturn(List.of(commit));

		List<ProjectCommitResponse> result = service.listTaskCommits(userId, projectId, taskId);
		assertThat(result).hasSize(1);
		assertThat(result.getFirst().headRef()).isNull();
		assertThat(result.getFirst().sha()).isEqualTo("def");
		assertThat(result.getFirst().repoId()).isEqualTo(repo.getId());
		assertThat(result.getFirst().repositoryFullName()).isEqualTo("org/saga");
	}

	@Test
	void listTasks_exposesAssigneePriorityStoryPointSprintAndNullsSafely() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalId("10001");
		task.setExternalKey("SAGA-1");
		task.setTitle("Login");
		task.setDescription("desc");
		task.setStatus(TaskStatus.TODO);
		task.setIssueTypeName("Story");
		task.setAssigneeExternalId("jira-account-1");
		task.setPriority(com.saga.be.entity.enums.Priority.HIGH);
		task.setStoryPoint(5);
		com.saga.be.entity.jira.Sprint sprint = new com.saga.be.entity.jira.Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setExternalSprintId("31");
		sprint.setName("Sprint 1");
		sprint.setState("active");
		task.setSprint(sprint);
		com.saga.be.entity.account.StudentProfile profile = new com.saga.be.entity.account.StudentProfile();
		profile.setId(UUID.randomUUID());
		UserAccount assigneeUser = account(AccountRole.STUDENT);
		assigneeUser.setFullName("Leader One");
		profile.setUserAccount(assigneeUser);
		task.setAssigneeStudent(profile);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId))
				.thenReturn(List.<Object[]>of(new Object[] {task.getId(), 3L}));

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.assigneeExternalId()).isEqualTo("jira-account-1");
		assertThat(response.assigneeDisplayName()).isEqualTo("Leader One");
		assertThat(response.assigneeStudentId()).isEqualTo(profile.getId());
		assertThat(response.priority()).isEqualTo("HIGH");
		assertThat(response.storyPoint()).isEqualTo(5);
		assertThat(response.sprint().externalSprintId()).isEqualTo("31");
		assertThat(response.sprint().name()).isEqualTo("Sprint 1");
		assertThat(response.linkedCommitCount()).isEqualTo(3L);
		assertThat(response.description()).isEqualTo("desc");
	}

	@Test
	void listTasks_nullAssigneePriorityStorySprint_areNull() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-2");
		task.setTitle("Empty");
		task.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.assigneeExternalId()).isNull();
		assertThat(response.assigneeDisplayName()).isNull();
		assertThat(response.priority()).isNull();
		assertThat(response.storyPoint()).isNull();
		assertThat(response.sprint()).isNull();
	}

	@Test
	void getTask_requiresSameProject() {
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		when(tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.getTask(userId, projectId, taskId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	private void stubStudent(RoleInTeam ignored) {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}

	private List<Task> tasks(int n) {
		List<Task> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Task task = new Task();
			task.setId(UUID.randomUUID());
			task.setExternalKey("SAGA-" + i);
			task.setTitle("T" + i);
			task.setStatus(TaskStatus.TODO);
			rows.add(task);
		}
		return rows;
	}
}

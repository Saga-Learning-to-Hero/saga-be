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
		service = new ProjectProjectionReadService(tasks, commits, links, authorization);
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
		when(links.findFetchedCommitsByProjectAndTask(projectId, taskId)).thenReturn(List.of(commit));

		List<ProjectCommitResponse> result = service.listTaskCommits(userId, projectId, taskId);
		assertThat(result).hasSize(1);
		assertThat(result.getFirst().sha()).isEqualTo("abc");
		verify(links, times(1)).findFetchedCommitsByProjectAndTask(projectId, taskId);
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

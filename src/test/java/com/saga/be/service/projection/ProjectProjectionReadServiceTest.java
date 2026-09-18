package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.ProjectCommitPageResponse;
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
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

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
		service = new ProjectProjectionReadService(
				tasks, commits, links, sprints, authorization, new TaskHierarchyService(projects, tasks, org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class)));
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
		assertThatThrownBy(() -> service.listCommits(userId, projectId, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		verify(commits, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void listCommits_adminDeniedUnchanged() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.listCommits(userId, projectId, 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(commits, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void listCommits_outsiderDeniedUnchanged() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.listCommits(userId, projectId, 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(commits, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void listCommits_memberCanRead() {
		stubStudent(RoleInTeam.MEMBER);
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
		ProjectCommitPageResponse result = service.listCommits(userId, projectId, null, null);
		assertThat(result.items()).isEmpty();
		assertThat(result.page()).isZero();
		assertThat(result.size()).isEqualTo(50);
		assertThat(result.total()).isZero();
		verify(commits, never()).findFetchedByIdIn(any());
		verify(commits, never()).findFetchedByProject_Id(any());
	}

	@Test
	void listCommits_assignedLecturerCanRead() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
		assertThat(service.listCommits(userId, projectId, 0, 50).total()).isZero();
	}

	@Test
	void listCommits_exposesNullableMergeClassificationWithoutMessageHeuristic() {
		stubStudent(RoleInTeam.MEMBER);
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName("org/saga");
		GitCommit unknown = commit(repo, "u1", "init", null);
		GitCommit root = commit(repo, "r1", "root", 0);
		GitCommit normal = commit(repo, "n1", "Merge branch 'x'", 1);
		GitCommit merge = commit(repo, "m1", "custom message", 2);
		GitCommit octopus = commit(repo, "o1", "octopus", 3);
		List<UUID> ordered = List.of(unknown.getId(), root.getId(), normal.getId(), merge.getId(), octopus.getId());
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(ordered, PageRequest.of(0, 50), 5));
		when(commits.findFetchedByIdIn(ordered)).thenReturn(List.of(octopus, merge, normal, root, unknown));

		ProjectCommitPageResponse page = service.listCommits(userId, projectId, null, null);

		assertThat(page.items()).extracting(ProjectCommitResponse::parentCount).containsExactly(null, 0, 1, 2, 3);
		assertThat(page.items()).extracting(ProjectCommitResponse::isMerge).containsExactly(null, false, false, true, true);
		assertThat(page.total()).isEqualTo(5);
		verify(commits, never()).findFetchedByProject_Id(any());
	}

	@Test
	void listCommits_reconstructsExactIdPageOrderAfterShuffledInFetch() {
		stubStudent(RoleInTeam.MEMBER);
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName("org/saga");
		GitCommit first = commit(repo, "aaa", "first", 1);
		GitCommit second = commit(repo, "bbb", "second", 1);
		List<UUID> ordered = List.of(first.getId(), second.getId());
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(ordered, PageRequest.of(0, 50), 2));
		when(commits.findFetchedByIdIn(ordered)).thenReturn(List.of(second, first));

		ProjectCommitPageResponse page = service.listCommits(userId, projectId, 0, 50);

		assertThat(page.items()).extracting(ProjectCommitResponse::id).containsExactly(first.getId(), second.getId());
		assertThat(page.items()).extracting(ProjectCommitResponse::sha).containsExactly("aaa", "bbb");
	}

	@Test
	void listCommits_pageBeyondLast_returnsEmptyItemsWithRequestedPage() {
		stubStudent(RoleInTeam.MEMBER);
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(9, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 50), 3));
		ProjectCommitPageResponse page = service.listCommits(userId, projectId, 9, 50);
		assertThat(page.items()).isEmpty();
		assertThat(page.page()).isEqualTo(9);
		assertThat(page.size()).isEqualTo(50);
		assertThat(page.total()).isEqualTo(3);
		verify(commits, never()).findFetchedByIdIn(any());
	}

	@Test
	void listCommits_rejectsInvalidPageAndSize() {
		stubStudent(RoleInTeam.MEMBER);
		assertInvalidPage(-1, 50);
		assertInvalidPage(0, 0);
		assertInvalidPage(0, 201);
		verify(commits, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void listCommits_acceptsSizeBounds() {
		stubStudent(RoleInTeam.MEMBER);
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 1))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
		when(commits.findPageIdsByProject(eq(projectId), eq(PageRequest.of(0, 200))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));
		assertThat(service.listCommits(userId, projectId, 0, 1).size()).isEqualTo(1);
		assertThat(service.listCommits(userId, projectId, 0, 200).size()).isEqualTo(200);
	}

	private void assertInvalidPage(Integer page, Integer size) {
		assertThatThrownBy(() -> service.listCommits(userId, projectId, page, size))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> {
					AcademicException academic = (AcademicException) ex;
					assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
					assertThat(academic.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
	}

	private static GitCommit commit(GitRepo repo, String sha, String message, Integer parentCount) {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setParentCount(parentCount);
		return commit;
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
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, taskId, null, null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(links, never()).findPageIdsByProjectAndTask(any(), any(), any());
		verify(links, never()).findFetchedCommitsByProjectAndTask(any(), any());
		verify(commits, never()).findFetchedByIdIn(any());
	}

	@Test
	void taskCommits_adminDeniedUnchanged() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, UUID.randomUUID(), 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(tasks, never()).findByIdAndProject_IdAndDeletedAtIsNull(any(), any());
		verify(links, never()).findPageIdsByProjectAndTask(any(), any(), any());
	}

	@Test
	void taskCommits_outsiderDeniedUnchanged() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, UUID.randomUUID(), 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(tasks, never()).findByIdAndProject_IdAndDeletedAtIsNull(any(), any());
		verify(links, never()).findPageIdsByProjectAndTask(any(), any(), any());
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
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(commit.getId()), PageRequest.of(0, 50), 1));
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));

		ProjectCommitPageResponse result = service.listTaskCommits(userId, projectId, taskId, null, null);
		assertThat(result.items()).hasSize(1);
		assertThat(result.page()).isZero();
		assertThat(result.size()).isEqualTo(50);
		assertThat(result.total()).isEqualTo(1);
		assertThat(result.items().getFirst().sha()).isEqualTo("abc");
		assertThat(result.items().getFirst().headRef()).isEqualTo("main");
		verify(links, times(1)).findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 50)));
		verify(links, never()).findFetchedCommitsByProjectAndTask(any(), any());
		verify(commits, never()).findPageIdsByProject(any(), any());
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
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(commit.getId()), PageRequest.of(0, 50), 1));
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));

		ProjectCommitPageResponse result = service.listTaskCommits(userId, projectId, taskId, null, null);
		assertThat(result.items()).hasSize(1);
		assertThat(result.items().getFirst().headRef()).isNull();
		assertThat(result.items().getFirst().sha()).isEqualTo("def");
		assertThat(result.items().getFirst().repoId()).isEqualTo(repo.getId());
		assertThat(result.items().getFirst().repositoryFullName()).isEqualTo("org/saga");
	}

	@Test
	void taskCommits_reconstructsExactIdPageOrderAfterShuffledInFetch() {
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		Task task = new Task();
		task.setId(taskId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.of(task));
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName("org/saga");
		GitCommit first = commit(repo, "aaa", "first", 1);
		GitCommit second = commit(repo, "bbb", "second", 1);
		GitCommit third = commit(repo, "ccc", "third", 0);
		List<UUID> ordered = List.of(first.getId(), second.getId(), third.getId());
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(ordered, PageRequest.of(0, 50), 3));
		when(commits.findFetchedByIdIn(ordered)).thenReturn(List.of(third, first, second));

		ProjectCommitPageResponse page = service.listTaskCommits(userId, projectId, taskId, 0, 50);

		assertThat(page.items())
				.extracting(ProjectCommitResponse::id)
				.containsExactly(first.getId(), second.getId(), third.getId());
		assertThat(page.items()).extracting(ProjectCommitResponse::sha).containsExactly("aaa", "bbb", "ccc");
	}

	@Test
	void taskCommits_pagesEdgesAndInvalid() {
		stubStudent(RoleInTeam.MEMBER);
		UUID taskId = UUID.randomUUID();
		Task task = new Task();
		task.setId(taskId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)).thenReturn(Optional.of(task));
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(1, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(1, 50), 3));
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(9, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(9, 50), 3));
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 1))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 1), 0));
		when(links.findPageIdsByProjectAndTask(eq(projectId), eq(taskId), eq(PageRequest.of(0, 200))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 200), 0));

		ProjectCommitPageResponse defaults = service.listTaskCommits(userId, projectId, taskId, null, null);
		assertThat(defaults.page()).isZero();
		assertThat(defaults.size()).isEqualTo(50);
		assertThat(defaults.total()).isZero();
		assertThat(defaults.items()).isEmpty();
		verify(commits, never()).findFetchedByIdIn(any());

		ProjectCommitPageResponse explicit = service.listTaskCommits(userId, projectId, taskId, 0, 50);
		assertThat(explicit.page()).isZero();
		assertThat(explicit.size()).isEqualTo(50);

		ProjectCommitPageResponse second = service.listTaskCommits(userId, projectId, taskId, 1, 50);
		assertThat(second.page()).isEqualTo(1);
		assertThat(second.items()).isEmpty();
		assertThat(second.total()).isEqualTo(3);

		ProjectCommitPageResponse beyond = service.listTaskCommits(userId, projectId, taskId, 9, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.total()).isEqualTo(3);

		assertThat(service.listTaskCommits(userId, projectId, taskId, 0, 1).size()).isEqualTo(1);
		assertThat(service.listTaskCommits(userId, projectId, taskId, 0, 200).size()).isEqualTo(200);

		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, taskId, -1, 50))
				.isInstanceOf(AcademicException.class)
				.satisfies(this::assertTaskCommitInvalid);
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, taskId, 0, 0))
				.isInstanceOf(AcademicException.class)
				.satisfies(this::assertTaskCommitInvalid);
		assertThatThrownBy(() -> service.listTaskCommits(userId, projectId, taskId, 0, 201))
				.isInstanceOf(AcademicException.class)
				.satisfies(this::assertTaskCommitInvalid);
	}

	private void assertTaskCommitInvalid(Throwable ex) {
		AcademicException academic = (AcademicException) ex;
		assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThat(academic.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
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
	void listTasks_ordinaryTask_parentIsNull() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-1");
		task.setTitle("Ordinary task");
		task.setStatus(TaskStatus.TODO);
		task.setIssueTypeName("Task");
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.parent()).isNull();
	}

	@Test
	void listTasks_subtask_exposesParentExternalIdAndKey() {
		// Response echoes Jira's own parent identity verbatim -- no local Task lookup/join, so this
		// is correct even if the parent Task row does not exist locally (never synced, or never
		// will), matching section 5/6 of the audit: FE must be able to render "Parent Task ->
		// Subtask" purely from provider identity, not a local foreign key.
		stubStudent(RoleInTeam.MEMBER);
		Task subtask = new Task();
		subtask.setId(UUID.randomUUID());
		subtask.setExternalKey("SAGA-50");
		subtask.setTitle("Implement login form");
		subtask.setStatus(TaskStatus.TODO);
		subtask.setIssueTypeName("Subtask");
		subtask.setParentExternalId("10049");
		subtask.setParentExternalKey("SAGA-49");
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(subtask));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.parent()).isNotNull();
		assertThat(response.parent().externalId()).isEqualTo("10049");
		assertThat(response.parent().externalKey()).isEqualTo("SAGA-49");
	}

	@Test
	void listTasks_multipleSubtasks_shareSameParentKey() {
		stubStudent(RoleInTeam.MEMBER);
		Task subtaskA = new Task();
		subtaskA.setId(UUID.randomUUID());
		subtaskA.setExternalKey("SAGA-50");
		subtaskA.setStatus(TaskStatus.TODO);
		subtaskA.setIssueTypeName("Subtask");
		subtaskA.setParentExternalId("10049");
		subtaskA.setParentExternalKey("SAGA-49");
		Task subtaskB = new Task();
		subtaskB.setId(UUID.randomUUID());
		subtaskB.setExternalKey("SAGA-51");
		subtaskB.setStatus(TaskStatus.TODO);
		subtaskB.setIssueTypeName("Subtask");
		subtaskB.setParentExternalId("10049");
		subtaskB.setParentExternalKey("SAGA-49");
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(subtaskA, subtaskB));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		List<ProjectTaskResponse> responses = service.listTasks(userId, projectId);

		assertThat(responses).hasSize(2);
		assertThat(responses).allSatisfy(response -> {
			assertThat(response.parent().externalId()).isEqualTo("10049");
			assertThat(response.parent().externalKey()).isEqualTo("SAGA-49");
		});
	}

	@Test
	void listTasks_nativeParent_isLightweightAndDoesNotQueryPerRow() {
		stubStudent(RoleInTeam.MEMBER);
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		parent.setTitle("Parent story");
		Task child = new Task();
		child.setId(UUID.randomUUID());
		child.setExternalKey("SAGA-50");
		child.setTitle("Child");
		child.setParentTask(parent);
		child.setParentExternalId("10049");
		child.setParentExternalKey("SAGA-49");
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(child));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.parent()).isNotNull();
		assertThat(response.parent().externalId()).isEqualTo("10049");
		assertThat(response.parent().externalKey()).isEqualTo("SAGA-49");
		assertThat(response.parentTask()).isNotNull();
		assertThat(response.parentTask().id()).isEqualTo(parent.getId());
		assertThat(response.parentTask().title()).isEqualTo("Parent story");
		assertThat(response.subtasks()).isNull();
		verify(tasks, never()).findById(any());
		verify(tasks, never()).findActiveDirectChildSummaries(any());
	}

	@Test
	void listTasks_softDeletedNativeParent_isAbsent() {
		stubStudent(RoleInTeam.MEMBER);
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		parent.setTitle("Deleted parent");
		parent.setDeletedAt(java.time.LocalDateTime.now());
		Task child = new Task();
		child.setId(UUID.randomUUID());
		child.setExternalKey("SAGA-50");
		child.setTitle("Child");
		child.setParentTask(parent);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(child));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.parentTask()).isNull();
		verify(tasks, never()).findById(any());
		verify(tasks, times(1)).findActiveFetchedByProject_Id(projectId);
		verify(tasks, never()).findActiveDirectChildSummaries(any());
	}

	@Test
	void listTasks_nativeParent_queryCountIndependentOfRowCount() {
		stubStudent(RoleInTeam.MEMBER);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(tasksWithNativeParents(10));
		service.listTasks(userId, projectId);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(tasksWithNativeParents(100));
		service.listTasks(userId, projectId);

		verify(tasks, times(2)).findActiveFetchedByProject_Id(projectId);
		verify(links, times(2)).countLinksByProjectGrouped(projectId);
		verify(tasks, never()).findById(any());
		verify(tasks, never()).findActiveDirectChildSummaries(any());
	}

	@Test
	void getTask_exposesDirectSubtasksOnly() {
		stubStudent(RoleInTeam.MEMBER);
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		parent.setTitle("Parent");
		parent.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByIdAndProject_Id(parent.getId(), projectId)).thenReturn(Optional.of(parent));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());
		UUID childId = UUID.randomUUID();
		when(tasks.findActiveDirectChildSummaries(parent.getId()))
				.thenReturn(java.util.List.<Object[]>of(new Object[] {childId, "Child", TaskStatus.IN_PROGRESS}));

		ProjectTaskResponse response = service.getTask(userId, projectId, parent.getId());

		assertThat(response.subtasks()).containsExactly(new ProjectTaskResponse.Subtask(childId, "Child", "IN_PROGRESS"));
		assertThat(response.parentTask()).isNull();
		verify(tasks, times(1)).findActiveDirectChildSummaries(parent.getId());
		verify(tasks, never()).findById(any());
	}

	@Test
	void getTask_softDeletedNativeParent_isAbsent() {
		stubStudent(RoleInTeam.MEMBER);
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		parent.setTitle("Deleted parent");
		parent.setDeletedAt(java.time.LocalDateTime.now());
		Task child = new Task();
		child.setId(UUID.randomUUID());
		child.setTitle("Child");
		child.setStatus(TaskStatus.TODO);
		child.setParentTask(parent);
		when(tasks.findActiveFetchedByIdAndProject_Id(child.getId(), projectId)).thenReturn(Optional.of(child));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());
		when(tasks.findActiveDirectChildSummaries(child.getId())).thenReturn(List.of());

		ProjectTaskResponse response = service.getTask(userId, projectId, child.getId());

		assertThat(response.parentTask()).isNull();
		verify(tasks, times(1)).findActiveFetchedByIdAndProject_Id(child.getId(), projectId);
		verify(tasks, times(1)).findActiveDirectChildSummaries(child.getId());
		verify(tasks, never()).findById(any());
	}

	@Test
	void getTask_omitsSoftDeletedDirectChildren() {
		stubStudent(RoleInTeam.MEMBER);
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		parent.setTitle("Parent");
		parent.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByIdAndProject_Id(parent.getId(), projectId)).thenReturn(Optional.of(parent));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());
		UUID activeChildId = UUID.randomUUID();
		when(tasks.findActiveDirectChildSummaries(parent.getId()))
				.thenReturn(java.util.List.<Object[]>of(new Object[] {activeChildId, "C1", TaskStatus.TODO}));

		ProjectTaskResponse response = service.getTask(userId, projectId, parent.getId());

		assertThat(response.subtasks()).containsExactly(new ProjectTaskResponse.Subtask(activeChildId, "C1", "TODO"));
		verify(tasks, times(1)).findActiveDirectChildSummaries(parent.getId());
		verify(tasks, never()).findById(any());
	}

	@Test
	void listParentOptions_adminDenied() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.listParentOptions(userId, projectId, null, 0, 20, null))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(tasks, never()).findParentOptions(any(), any(), anyBoolean(), any(), any());
	}

	@Test
	void listParentOptions_outsiderDenied() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.listParentOptions(userId, projectId, null, 0, 20, null))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(tasks, never()).findParentOptions(any(), any(), anyBoolean(), any(), any());
	}

	@Test
	void listParentOptions_memberAllowed() {
		stubStudent(RoleInTeam.MEMBER);
		when(tasks.findParentOptions(eq(projectId), any(), eq(true), eq(""), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(
						List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));
		var response = service.listParentOptions(userId, projectId, null, 0, 20, null);
		assertThat(response.items()).isEmpty();
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(20);
	}

	@Test
	void listParentOptions_lecturerAssignedAllowed() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		when(tasks.findParentOptions(eq(projectId), any(), eq(true), eq(""), any()))
				.thenReturn(new org.springframework.data.domain.PageImpl<>(List.of(), org.springframework.data.domain.PageRequest.of(0, 20), 0));

		var response = service.listParentOptions(userId, projectId, null, 0, 20, null);

		assertThat(response.items()).isEmpty();
		assertThat(response.total()).isZero();
	}

	@Test
	void listTasks_withLabels_exposesLabelList() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-1");
		task.setTitle("Login");
		task.setStatus(TaskStatus.TODO);
		task.setLabelsJson("[\"backend\",\"urgent\"]");
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.labels()).containsExactly("backend", "urgent");
	}

	@Test
	void listTasks_noLabels_returnsEmptyListNeverNull() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-2");
		task.setTitle("No labels");
		task.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.labels()).isNotNull().isEmpty();
	}

	@Test
	void listTasks_withDueDate_exposesPlainCalendarDate() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-1");
		task.setTitle("Has due date");
		task.setStatus(TaskStatus.TODO);
		task.setDueDate(java.time.LocalDateTime.of(2026, 9, 18, 0, 0));
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.dueDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 18));
	}

	@Test
	void listTasks_noDueDate_isNull() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-2");
		task.setTitle("No due date");
		task.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.dueDate()).isNull();
	}

	@Test
	void listTasks_withStartDate_exposesPlainCalendarDate() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-1");
		task.setTitle("Has start date");
		task.setStatus(TaskStatus.TODO);
		task.setStartDate(java.time.LocalDateTime.of(2026, 9, 14, 0, 0));
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.startDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 14));
	}

	@Test
	void listTasks_noStartDate_isNull() {
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-2");
		task.setTitle("No start date");
		task.setStatus(TaskStatus.TODO);
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.startDate()).isNull();
	}

	@Test
	void listTasks_startDateAndDueDate_noTimezoneShift() {
		// Persistence is local midnight DATETIME(6); the API boundary must truncate via
		// LocalDateTime.toLocalDate() with no Instant/UTC conversion that could shift the calendar
		// day. A late-in-day stored time must still expose the same local date.
		stubStudent(RoleInTeam.MEMBER);
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-3");
		task.setTitle("Dates");
		task.setStatus(TaskStatus.TODO);
		task.setStartDate(java.time.LocalDateTime.of(2026, 9, 14, 23, 30));
		task.setDueDate(java.time.LocalDateTime.of(2026, 9, 18, 23, 30));
		when(tasks.findActiveFetchedByProject_Id(projectId)).thenReturn(List.of(task));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.listTasks(userId, projectId).getFirst();

		assertThat(response.startDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 14));
		assertThat(response.dueDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 18));
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

	private List<Task> tasksWithNativeParents(int n) {
		List<Task> rows = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			Task parent = new Task();
			parent.setId(UUID.randomUUID());
			parent.setTitle("P" + i);
			Task task = new Task();
			task.setId(UUID.randomUUID());
			task.setExternalKey("SAGA-" + i);
			task.setTitle("T" + i);
			task.setStatus(TaskStatus.TODO);
			task.setParentTask(parent);
			rows.add(task);
		}
		return rows;
	}
}

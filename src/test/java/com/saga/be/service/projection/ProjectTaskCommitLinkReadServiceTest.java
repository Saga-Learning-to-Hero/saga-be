package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.dto.project.ProjectTaskCommitLinksResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitBranchRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProjectTaskCommitLinkReadServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private GitCommitBranchRepository memberships;

	private ProjectTaskCommitLinkReadService service;
	private UUID projectId;
	private UUID userId;

	@BeforeEach
	void setUp() {
		service = new ProjectTaskCommitLinkReadService(
				new ProjectDataAuthorization(users, members, projects), repos, links, memberships);
		projectId = UUID.randomUUID();
		userId = UUID.randomUUID();
	}

	@Test
	void batchEndpoint_returnsCanonicalLinks() {
		stubStudent();
		GitRepo repo = repo("org/demo", 55L);
		TaskGitCommitLink link = link(task("SAGA-66"), commit(repo, "544a942", "feat: SAGA-66", "develop"), TraceLinkSource.COMMIT_MESSAGE);
		stubUnfilteredPage(List.of(link), List.<Object[]>of(new Object[] {link.getGitCommit().getId(), "develop"}));

		ProjectTaskCommitLinksResponse response = service.list(userId, projectId, null, null, null, null);

		assertThat(response.projectId()).isEqualTo(projectId);
		assertThat(response.page()).isZero();
		assertThat(response.size()).isEqualTo(100);
		assertThat(response.total()).isEqualTo(1);
		assertThat(response.filter().branchResolution()).isEqualTo("REACHABLE_AT_SYNC");
		assertThat(response.filter().repoId()).isNull();
		assertThat(response.filter().resolvedAt()).isNull();
		assertThat(response.links()).hasSize(1);
		assertThat(response.links().getFirst().taskKey()).isEqualTo("SAGA-66");
		assertThat(response.links().getFirst().sha()).isEqualTo("544a942");
		assertThat(response.links().getFirst().repoId()).isEqualTo(repo.getId());
		assertThat(response.links().getFirst().repositoryId()).isEqualTo(55L);
		assertThat(response.links().getFirst().linkSource()).isEqualTo("COMMIT_MESSAGE");
		assertThat(response.links().getFirst().linkedAt()).isEqualTo(link.getCreatedAt());
	}

	@Test
	void manualLinkWithoutJiraKey_isReturned() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		TaskGitCommitLink link = link(
				task("SAGA-9"), commit(repo, "deadbeef", "refactor helpers with no ticket", "main"), TraceLinkSource.MANUAL);
		stubUnfilteredPage(List.of(link), List.of());

		ProjectTaskCommitLinksResponse response = service.list(userId, projectId, null, null, 0, 100);

		assertThat(response.links()).hasSize(1);
		assertThat(response.links().getFirst().message()).doesNotContain("SAGA-");
		assertThat(response.links().getFirst().linkSource()).isEqualTo("MANUAL");
	}

	@Test
	void mergeCommitLink_isReturned() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		TaskGitCommitLink link = link(
				task("SAGA-10"),
				commit(repo, "merge1", "Merge branch 'feature/x' into develop", "develop"),
				TraceLinkSource.RECONCILIATION);
		stubUnfilteredPage(List.of(link), List.of());

		assertThat(service.list(userId, projectId, null, null, 0, 100).links().getFirst().sha()).isEqualTo("merge1");
	}

	@Test
	void repositoryFilter_scopesToActiveGitRepoId() {
		stubStudent();
		GitRepo repo = repo("org/demo", 99L);
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		TaskGitCommitLink link = link(task("SAGA-1"), commit(repo, "abc", "m", "main"), TraceLinkSource.COMMIT_MESSAGE);
		when(links.findPageIdsByProjectAndRepo(eq(projectId), eq(repo.getId()), any()))
				.thenReturn(new PageImpl<>(List.of(link.getId()), PageRequest.of(0, 100), 1));
		when(links.findFetchedByIdIn(List.of(link.getId()))).thenReturn(List.of(link));
		when(memberships.findCommitIdAndBranchNameByCommitIdIn(any())).thenReturn(List.of());

		ProjectTaskCommitLinksResponse response = service.list(userId, projectId, repo.getId(), null, 0, 100);

		assertThat(response.filter().repoId()).isEqualTo(repo.getId());
		assertThat(response.filter().repositoryId()).isEqualTo(99L);
		assertThat(response.filter().repositoryFullName()).isEqualTo("org/demo");
		assertThat(response.links()).hasSize(1);
		verify(links, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void branchNameWithoutRepoId_isRequestInvalid() {
		stubStudent();
		assertThatThrownBy(() -> service.list(userId, projectId, null, "develop", 0, 100))
				.isInstanceOf(AcademicException.class)
				.satisfies(ex -> {
					AcademicException ae = (AcademicException) ex;
					assertThat(ae.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
					assertThat(ae.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
		verify(links, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void branchFilter_usesMembershipNotHeadRef() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		repo.setBranchMembershipSyncedAt(LocalDateTime.of(2026, 9, 14, 10, 0));
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		GitCommit commit = commit(repo, "abc", "no key here", "main");
		TaskGitCommitLink link = link(task("SAGA-7"), commit, TraceLinkSource.MANUAL);
		when(links.findPageIdsByProjectAndRepoAndBranch(eq(projectId), eq(repo.getId()), eq("develop"), any()))
				.thenReturn(new PageImpl<>(List.of(link.getId()), PageRequest.of(0, 100), 1));
		when(links.findFetchedByIdIn(List.of(link.getId()))).thenReturn(List.of(link));
		when(memberships.findCommitIdAndBranchNameByCommitIdIn(any()))
				.thenReturn(List.<Object[]>of(
						new Object[] {commit.getId(), "develop"}, new Object[] {commit.getId(), "main"}));

		ProjectTaskCommitLinksResponse response =
				service.list(userId, projectId, repo.getId(), "develop", 0, 100);

		assertThat(response.links()).hasSize(1);
		assertThat(response.links().getFirst().headRef()).isEqualTo("main");
		assertThat(response.links().getFirst().branchNames()).containsExactly("develop", "main");
		assertThat(response.filter().branchName()).isEqualTo("develop");
		assertThat(response.filter().resolvedAt()).isEqualTo(LocalDateTime.of(2026, 9, 14, 10, 0));
		verify(links, never()).findPageIdsByProjectAndRepo(any(), any(), any());
	}

	@Test
	void partialSyncCommit_visibleUnfilteredWithEmptyBranchNames_excludedByBranchFilter() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
		repo.setBranchMembershipSyncedAt(t1);
		GitCommit commitC = commit(repo, "commitc00000000000000000000000000000001", "feat: SAGA-1", "develop");
		TaskGitCommitLink link = link(task("SAGA-1"), commitC, TraceLinkSource.COMMIT_MESSAGE);
		stubUnfilteredPage(List.of(link), List.of());

		ProjectTaskCommitLinksResponse unfiltered = service.list(userId, projectId, null, null, 0, 100);

		assertThat(unfiltered.links()).hasSize(1);
		assertThat(unfiltered.links().getFirst().sha()).isEqualTo(commitC.getShaHash());
		assertThat(unfiltered.links().getFirst().headRef()).isEqualTo("develop");
		assertThat(unfiltered.links().getFirst().branchNames()).isEmpty();
		assertThat(unfiltered.filter().resolvedAt()).isNull();

		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(links.findPageIdsByProjectAndRepoAndBranch(eq(projectId), eq(repo.getId()), eq("develop"), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		ProjectTaskCommitLinksResponse filtered = service.list(userId, projectId, repo.getId(), "develop", 0, 100);

		assertThat(filtered.links()).isEmpty();
		assertThat(filtered.filter().resolvedAt()).isEqualTo(t1);
		verify(memberships, times(1)).findCommitIdAndBranchNameByCommitIdIn(any());
	}

	@Test
	void resolvedAtJson_isLocalDateTimeWithoutTimezoneSuffix() throws Exception {
		ObjectMapper mapper = new ObjectMapper();
		mapper.registerModule(new JavaTimeModule());
		mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		ProjectTaskCommitLinksResponse.Filter filter = new ProjectTaskCommitLinksResponse.Filter(
				UUID.randomUUID(),
				99L,
				"org/demo",
				"develop",
				ProjectTaskCommitLinksResponse.BRANCH_RESOLUTION_REACHABLE_AT_SYNC,
				LocalDateTime.of(2026, 9, 14, 10, 0, 0));

		String json = mapper.writeValueAsString(filter);

		assertThat(json).contains("\"resolvedAt\":\"2026-09-14T10:00:00\"");
		assertThat(json).doesNotContain("\"resolvedAt\":\"2026-09-14T10:00:00Z\"");
		assertThat(json).doesNotContain("+00:00");
	}

	@Test
	void unknownBranch_returnsEmptyNotNotFound() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(links.findPageIdsByProjectAndRepoAndBranch(eq(projectId), eq(repo.getId()), eq("feature/foo"), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));

		ProjectTaskCommitLinksResponse response =
				service.list(userId, projectId, repo.getId(), "feature/foo", 0, 100);

		assertThat(response.links()).isEmpty();
		assertThat(response.total()).isZero();
		verify(links, never()).findFetchedByIdIn(any());
	}

	@Test
	void wrongProjectRepo_notFound() {
		stubStudent();
		UUID repoId = UUID.randomUUID();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.list(userId, projectId, repoId, null, 0, 100))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	@Test
	void revokedRepo_notUsableAsFilter() {
		stubStudent();
		UUID repoId = UUID.randomUUID();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.list(userId, projectId, repoId, "main", 0, 100))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void pagination_isDeterministicAndUsesRequestedPage() {
		stubStudent();
		GitRepo repo = repo("org/demo", 1L);
		TaskGitCommitLink newer = link(task("SAGA-2"), commit(repo, "bbb", "later", "main"), TraceLinkSource.COMMIT_MESSAGE);
		TaskGitCommitLink older = link(task("SAGA-1"), commit(repo, "aaa", "earlier", "main"), TraceLinkSource.COMMIT_MESSAGE);
		when(links.findPageIdsByProject(eq(projectId), eq(PageRequest.of(1, 1))))
				.thenReturn(new PageImpl<>(List.of(older.getId()), PageRequest.of(1, 1), 2));
		when(links.findFetchedByIdIn(List.of(older.getId()))).thenReturn(List.of(older));
		when(memberships.findCommitIdAndBranchNameByCommitIdIn(any())).thenReturn(List.of());

		ProjectTaskCommitLinksResponse response = service.list(userId, projectId, null, null, 1, 1);

		assertThat(response.page()).isEqualTo(1);
		assertThat(response.size()).isEqualTo(1);
		assertThat(response.total()).isEqualTo(2);
		assertThat(response.links()).extracting(ProjectTaskCommitLinksResponse.Link::sha).containsExactly("aaa");
	}

	@Test
	void assignedLecturer_canRead() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		when(links.findPageIdsByProject(eq(projectId), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
		assertThat(service.list(userId, projectId, null, null, 0, 100).links()).isEmpty();
	}

	@Test
	void member_canRead() {
		stubStudent();
		when(links.findPageIdsByProject(eq(projectId), any()))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 100), 0));
		assertThat(service.list(userId, projectId, null, null, 0, 100).total()).isZero();
	}

	@Test
	void unrelatedStudent_denied() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> service.list(userId, projectId, null, null, 0, 100))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(links, never()).findPageIdsByProject(any(), any());
	}

	@Test
	void admin_deniedUnchanged() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.list(userId, projectId, null, null, 0, 100))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(repos, never()).findByIdAndProject_IdAndConnectionStatus(any(), any(), any());
	}

	@Test
	void invalidPage_isRequestInvalid() {
		stubStudent();
		assertThatThrownBy(() -> service.list(userId, projectId, null, null, -1, 100))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	private void stubUnfilteredPage(List<TaskGitCommitLink> rows, List<Object[]> branchRows) {
		List<UUID> ids = rows.stream().map(TaskGitCommitLink::getId).toList();
		when(links.findPageIdsByProject(eq(projectId), any()))
				.thenReturn(new PageImpl<>(ids, PageRequest.of(0, 100), ids.size()));
		when(links.findFetchedByIdIn(ids)).thenReturn(rows);
		when(memberships.findCommitIdAndBranchNameByCommitIdIn(any())).thenReturn(branchRows);
	}

	private void stubStudent() {
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.STUDENT)));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}

	private GitRepo repo(String fullName, long repositoryId) {
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setFullName(fullName);
		repo.setRepositoryId(repositoryId);
		repo.setProvider(GitProvider.GITHUB);
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		return repo;
	}

	private GitCommit commit(GitRepo repo, String sha, String message, String headRef) {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setHeadRef(headRef);
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 14, 9, 0));
		return commit;
	}

	private Task task(String key) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		return task;
	}

	private TaskGitCommitLink link(Task task, GitCommit commit, TraceLinkSource source) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setId(UUID.randomUUID());
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(source);
		link.setCreatedAt(LocalDateTime.of(2026, 9, 14, 9, 0));
		return link;
	}
}

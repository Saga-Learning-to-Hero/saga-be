package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.ProjectGitBranchListResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProjectGitBranchReadServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private GithubProjectInstallationRepository projectInstallations;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private GitHubAppJwtService githubJwt;

	private ProjectGitBranchReadService service;
	private UUID userId;
	private UUID projectId;
	private Project project;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new ProjectGitBranchReadService(authorization, repos, projectInstallations, github, githubJwt);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		project = new Project();
		project.setId(projectId);
	}

	@Test
	void activeMember_canListBranches() {
		stubStudentMember();
		GitRepo repo = activeRepo();
		stubInstallation();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main", "dev"));

		ProjectGitBranchListResponse response = service.listBranches(userId, projectId, repo.getId());

		assertThat(response.branchCount()).isEqualTo(2);
		assertThat(response.branches()).extracting(ProjectGitBranchListResponse.Branch::name)
				.containsExactly("main", "dev");
	}

	@Test
	void defaultBranch_flaggedCorrectly() {
		stubStudentMember();
		GitRepo repo = activeRepo();
		stubInstallation();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("dev", "main"));

		ProjectGitBranchListResponse response = service.listBranches(userId, projectId, repo.getId());

		assertThat(response.branches())
				.filteredOn(b -> b.name().equals("main"))
				.singleElement()
				.extracting(ProjectGitBranchListResponse.Branch::isDefault)
				.isEqualTo(true);
		assertThat(response.branches())
				.filteredOn(b -> b.name().equals("dev"))
				.singleElement()
				.extracting(ProjectGitBranchListResponse.Branch::isDefault)
				.isEqualTo(false);
	}

	@Test
	void branchWithZeroUniqueCommits_stillReturned() {
		// Regression: a branch that introduces no new commit never appears as any GitCommit.headRef
		// (see GitHubCommitSyncServiceTest#sharedCommitAcrossBranches_...), but it MUST still show
		// up here since this reads GitHub's own branch listing, not derived commit data.
		stubStudentMember();
		GitRepo repo = activeRepo();
		stubInstallation();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main", "feat/no-new-commits"));

		ProjectGitBranchListResponse response = service.listBranches(userId, projectId, repo.getId());

		assertThat(response.branches()).extracting(ProjectGitBranchListResponse.Branch::name)
				.contains("feat/no-new-commits");
		assertThat(response.branchCount()).isEqualTo(2);
	}

	@Test
	void pagination_moreThan100Branches_allReturned() {
		stubStudentMember();
		GitRepo repo = activeRepo();
		stubInstallation();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		List<String> many = new ArrayList<>();
		for (int i = 0; i < 150; i++) {
			many.add("b" + i);
		}
		when(github.listBranches("tok", "org", "a")).thenReturn(many);

		ProjectGitBranchListResponse response = service.listBranches(userId, projectId, repo.getId());

		assertThat(response.branchCount()).isEqualTo(150);
	}

	@Test
	void repositoryBelongsToAnotherProject_notFound() {
		stubStudentMember();
		UUID repoId = UUID.randomUUID();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repoId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getStatus())
				.isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void revokedRepository_cannotActAsCurrentBranchSource() {
		// The repository lookup itself is scoped to connectionStatus=ACTIVE, so a REVOKED row (kept
		// for history under V15) can never resolve here -- same NOT_FOUND path as "not this project".
		stubStudentMember();
		UUID repoId = UUID.randomUUID();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repoId))
				.isInstanceOf(AcademicException.class);
	}

	@Test
	void nonMemberStudent_denied() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		UUID repoId = UUID.randomUUID();

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repoId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
	}

	@Test
	void admin_denied_sameAsCommitsPage() {
		UserAccount admin = new UserAccount();
		admin.setId(userId);
		admin.setAccountRole(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		UUID repoId = UUID.randomUUID();

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repoId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
	}

	@Test
	void providerError_mappedSafely_notFabricated() {
		stubStudentMember();
		GitRepo repo = activeRepo();
		stubInstallation();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		when(github.listBranches("tok", "org", "a"))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.GITHUB_RATE_LIMITED,
						HttpStatus.BAD_GATEWAY,
						"GitHub rate limit prevented completing repository sync."));

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repo.getId()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.GITHUB_RATE_LIMITED);
	}

	@Test
	void inactiveInstallation_deniedWithoutFabricatingBranches() {
		stubStudentMember();
		GitRepo repo = activeRepo();
		when(repos.findByIdAndProject_IdAndConnectionStatus(repo.getId(), projectId, IntegrationStatus.ACTIVE))
				.thenReturn(Optional.of(repo));
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.SUSPENDED);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		when(projectInstallations.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(membership));

		assertThatThrownBy(() -> service.listBranches(userId, projectId, repo.getId()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
	}

	private void stubStudentMember() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private void stubInstallation() {
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		when(projectInstallations.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(membership));
		when(githubJwt.createJwt()).thenReturn("jwt");
		when(github.createInstallationToken("jwt", 99L)).thenReturn("tok");
	}

	private GitRepo activeRepo() {
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setOwnerLogin("org");
		repo.setName("a");
		repo.setFullName("org/a");
		repo.setDefaultBranch("main");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(123L);
		return repo;
	}
}

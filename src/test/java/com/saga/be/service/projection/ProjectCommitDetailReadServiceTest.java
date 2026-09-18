package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.dto.project.ProjectCommitDetailResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitDetail;
import com.saga.be.integration.github.GitHubOAuthClient.CommitFileChange;
import com.saga.be.integration.github.GitHubOAuthClient.CommitParent;
import com.saga.be.integration.github.GitHubOAuthClient.CommitStats;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ProjectCommitDetailReadServiceTest {

	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
	private static final String TOKEN = "ghs_super_secret_install_token";

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private GithubProjectInstallationRepository projectInstallations;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private GitHubAppJwtService githubJwt;

	private ProjectCommitDetailReadService service;
	private UUID userId;
	private UUID projectId;
	private UUID gitCommitId;
	private Project project;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new ProjectCommitDetailReadService(authorization, commits, projectInstallations, github, githubJwt);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		gitCommitId = UUID.randomUUID();
		project = new Project();
		project.setId(projectId);
	}

	@Test
	void activeMember_canReadCommitDetail() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.gitCommitId()).isEqualTo(gitCommitId);
		assertThat(response.sha()).isEqualTo(SHA);
		assertThat(response.authorName()).isEqualTo("Ada Lovelace");
		assertThat(response.authorLogin()).isEqualTo("ada");
		assertThat(response.filesTruncated()).isFalse();
		assertThat(response.files()).hasSize(5);
	}

	@Test
	void activeTeamLeader_canReadCommitDetail() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.gitCommitId()).isEqualTo(gitCommitId);
		assertThat(response.repositoryFullName()).isEqualTo("org/repo");
	}

	@Test
	void assignedLecturer_canReadCommitDetail() {
		stubAssignedLecturer();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.message()).isEqualTo("fix: SAGA-1");
		assertThat(response.parents()).extracting(ProjectCommitDetailResponse.Parent::sha)
				.containsExactly("parent-one", "parent-two");
		assertThat(response.parentCount()).isEqualTo(2);
		assertThat(response.isMerge()).isTrue();
	}

	@Test
	void admin_denied() {
		stubRole(AccountRole.ADMIN);

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void outsider_denied() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void commitFromAnotherProject_denied() {
		stubStudentMember();
		Project other = new Project();
		other.setId(UUID.randomUUID());
		GitCommit foreign = storedCommit(activeRepo(other));
		when(commits.findFetchedById(gitCommitId)).thenReturn(Optional.of(foreign));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void unknownGitCommitId_controlledNotFound() {
		stubStudentMember();
		when(commits.findFetchedById(gitCommitId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void revokedRepository_notReadable() {
		stubStudentMember();
		GitRepo revoked = activeRepo(project);
		revoked.setConnectionStatus(IntegrationStatus.REVOKED);
		when(commits.findFetchedById(gitCommitId)).thenReturn(Optional.of(storedCommit(revoked)));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void mapsProviderFilesParentsAndNullPatch() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.stats().total()).isEqualTo(15);
		assertThat(response.stats().additions()).isEqualTo(12);
		assertThat(response.stats().deletions()).isEqualTo(3);
		assertThat(response.files())
				.extracting(ProjectCommitDetailResponse.ChangedFile::status)
				.containsExactly("modified", "added", "removed", "renamed", "added");
		assertThat(response.files().get(3).previousFilename()).isEqualTo("a.txt");
		assertThat(response.files().get(3).patch()).isNull();
		assertThat(response.files().get(4).filename()).isEqualTo("pic.png");
		assertThat(response.files().get(4).patch()).isNull();
	}

	@Test
	void filesTruncated_passthroughFromProvider() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, true));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.filesTruncated()).isTrue();
	}

	@Test
	void authorFieldsComeFromGithubNotSagaStudentMapping() {
		stubStudentMember();
		GitCommit stored = storedCommit(activeRepo(project));
		StudentProfile mapped = new StudentProfile();
		mapped.setStudentCode("SE999999");
		stored.setAuthorStudent(mapped);
		stored.setMessage("saga stored message");
		stubResolvedCommit(stored);
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA)).thenReturn(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.authorName()).isEqualTo("Ada Lovelace");
		assertThat(response.authorLogin()).isEqualTo("ada");
		assertThat(response.message()).isEqualTo("fix: SAGA-1");
		assertThat(response.authorLogin()).isNotEqualTo("SE999999");
		assertThat(stored.getAuthorStudent().getStudentCode()).isEqualTo("SE999999");
	}

	@Test
	void shaMismatch_unavailableWithoutLeakingToken() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", false));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException mapped = (IntegrationException) ex;
					assertThat(mapped.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(mapped.getMessage()).doesNotContain(TOKEN);
				});
	}

	@Test
	void provider404_unavailable() {
		stubStudentMember();
		stubResolvedCommit(storedCommit(activeRepo(project)));
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
						HttpStatus.BAD_GATEWAY,
						"GitHub commit could not be loaded."));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
	}

	@Test
	void provider403And429_rateLimited() {
		stubStudentMember();
		stubResolvedCommit(storedCommit(activeRepo(project)));
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.GITHUB_RATE_LIMITED,
						HttpStatus.BAD_GATEWAY,
						"GitHub rate limit prevented loading this commit."));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.GITHUB_RATE_LIMITED);
	}

	@Test
	void provider5xx_unavailable() {
		stubStudentMember();
		stubResolvedCommit(storedCommit(activeRepo(project)));
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
						HttpStatus.BAD_GATEWAY,
						"GitHub commit could not be loaded."));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
	}

	@Test
	void inactiveInstallation_invalidWithoutCallingGetCommit() {
		stubStudentMember();
		stubResolvedCommit(storedCommit(activeRepo(project)));
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.SUSPENDED);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		when(projectInstallations.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(membership));

		assertThatThrownBy(() -> service.getDetail(userId, projectId, gitCommitId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
		verify(github, never()).getCommit(any(), any(), any(), any());
	}

	@Test
	void getDoesNotPersistStatsOrFiles() {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		service.getDetail(userId, projectId, gitCommitId);

		verify(commits, never()).save(any());
		verify(commits, never()).saveAll(any());
	}

	@Test
	void providerHttpHappensOutsideJdbcTransaction() {
		stubStudentMember();
		stubResolvedCommit(storedCommit(activeRepo(project)));
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA)).thenAnswer(invocation -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return providerDetail(SHA, false);
		});

		service.getDetail(userId, projectId, gitCommitId);
	}

	@Test
	void jsonDoesNotSerializeTokensOrEmails() throws Exception {
		stubStudentMember();
		stubResolvedCommitAndProvider(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);
		String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(response);

		assertThat(json).doesNotContain(TOKEN);
		assertThat(json).doesNotContain("jwt-app");
		assertThat(json).doesNotContain("ada@example.com");
		assertThat(json).doesNotContain("installationId");
		assertThat(json).doesNotContain("accessToken");
		assertThat(json).contains("filesTruncated");
		assertThat(json).contains("gitCommitId");
		assertThat(json).contains("previousFilename");
	}

	@Test
	void shaMatchIsCaseInsensitive() {
		stubStudentMember();
		GitCommit stored = storedCommit(activeRepo(project));
		stored.setShaHash(SHA.toUpperCase());
		stubResolvedCommit(stored);
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA.toUpperCase())).thenReturn(providerDetail(SHA, false));

		ProjectCommitDetailResponse response = service.getDetail(userId, projectId, gitCommitId);

		assertThat(response.sha()).isEqualTo(SHA.toUpperCase());
	}

	private void stubStudentMember() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private void stubAssignedLecturer() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
	}

	private void stubRole(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}

	private void stubResolvedCommitAndProvider(CommitDetail provider) {
		stubResolvedCommit(storedCommit(activeRepo(project)));
		stubInstallation();
		when(github.getCommit(TOKEN, "org", "repo", SHA)).thenReturn(provider);
	}

	private void stubResolvedCommit(GitCommit commit) {
		when(commits.findFetchedById(gitCommitId)).thenReturn(Optional.of(commit));
	}

	private void stubInstallation() {
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		when(projectInstallations.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(membership));
		when(githubJwt.createJwt()).thenReturn("jwt-app");
		when(github.createInstallationToken("jwt-app", 99L)).thenReturn(TOKEN);
	}

	private GitRepo activeRepo(Project owner) {
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(owner);
		repo.setOwnerLogin("org");
		repo.setName("repo");
		repo.setFullName("org/repo");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(123L);
		return repo;
	}

	private GitCommit storedCommit(GitRepo repo) {
		GitCommit commit = new GitCommit();
		commit.setId(gitCommitId);
		commit.setRepo(repo);
		commit.setShaHash(SHA);
		commit.setMessage("saga stored message");
		return commit;
	}

	private static CommitDetail providerDetail(String sha, boolean truncated) {
		return new CommitDetail(
				sha,
				"https://github.com/org/repo/commit/" + sha,
				"fix: SAGA-1",
				"Ada Lovelace",
				"ada",
				"2026-09-01T10:00:00Z",
				new CommitStats(15, 12, 3),
				List.of(new CommitParent("parent-one"), new CommitParent("parent-two")),
				List.of(
						new CommitFileChange("src/a.java", null, "modified", 3, 1, 4, "@@ -1 +1 @@"),
						new CommitFileChange("src/new.java", null, "added", 10, 0, 10, "@@ +10 @@"),
						new CommitFileChange("old.txt", null, "removed", 0, 2, 2, "@@ -2 @@"),
						new CommitFileChange("b.txt", "a.txt", "renamed", 0, 0, 0, null),
						new CommitFileChange("pic.png", null, "added", 0, 0, 0, null)),
				truncated);
	}
}

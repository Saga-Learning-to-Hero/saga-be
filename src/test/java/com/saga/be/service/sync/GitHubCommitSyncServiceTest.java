package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitSummary;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.GitCommitProjectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class GitHubCommitSyncServiceTest {

	@Mock
	private GitRepoRepository repos;
	@Mock
	private GithubInstallationRepository installations;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private GitHubAppJwtService githubJwt;
	@Mock
	private GitCommitProjectionService projection;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private PlatformTransactionManager transactionManager;

	private IntegrationProperties properties;
	private GitHubCommitSyncService service;
	private UUID projectId;
	private Project project;

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenAnswer(inv -> new SimpleTransactionStatus());
		service = new GitHubCommitSyncService(
				repos, installations, github, githubJwt, projection, syncJobs, properties, transactionManager);
		projectId = UUID.randomUUID();
		project = new Project();
		project.setId(projectId);
		when(syncJobs.save(any(SyncJobLog.class))).thenAnswer(inv -> inv.getArgument(0));
		when(repos.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));
	}

	@Test
	void limitWithinFirstPage_requestsOnePage() {
		properties.setGithubCommitBackfillLimit(50);
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(50)))
				.thenReturn(summaries(50, "p1-"));
		when(projection.upsertBatch(eq(repo), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(50);
		verify(github, times(1)).listCommits(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(50));
	}

	@Test
	void limitEqualsProviderPageMax_requestsOneFullPage() {
		properties.setGithubCommitBackfillLimit(100);
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p1-"));
		when(projection.upsertBatch(eq(repo), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(100);
		verify(github, times(1)).listCommits(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void limitExceedsFirstPage_paginatesAndStopsAtLimit() {
		properties.setGithubCommitBackfillLimit(250);
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p1-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(2), eq(100)))
				.thenReturn(summaries(100, "p2-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(3), eq(50)))
				.thenReturn(summaries(50, "p3-"));
		when(projection.upsertBatch(eq(repo), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(250);
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100));
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(2), eq(100));
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(3), eq(50));
		verify(github, times(3)).listCommits(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
	}

	@Test
	void fewerCommitsThanLimit_stopsOnShortFinalPage() {
		properties.setGithubCommitBackfillLimit(300);
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p1-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(2), eq(100)))
				.thenReturn(summaries(30, "p2-"));
		when(projection.upsertBatch(eq(repo), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(130);
		verify(github, times(2)).listCommits(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(github, never()).listCommits(anyString(), anyString(), anyString(), anyString(), eq(3), anyInt());
	}

	@Test
	void emptyRepository_noCommitsNoExtraPages() {
		properties.setGithubCommitBackfillLimit(50);
		GitRepo repo = activeRepo("org", "empty");
		stubInstallationAndRepos(List.of(repo));
		when(github.listCommits(eq("tok"), eq("org"), eq("empty"), eq("main"), eq(1), eq(50)))
				.thenReturn(List.of());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(0);
		verify(github, times(1)).listCommits(anyString(), anyString(), anyString(), anyString(), anyInt(), anyInt());
		verify(projection, never()).upsertBatch(any(), any());
	}

	@Test
	void oneRepoFailure_doesNotSkipLaterRepos() {
		properties.setGithubCommitBackfillLimit(10);
		GitRepo a = activeRepo("org", "a");
		a.setId(UUID.randomUUID());
		a.setRepositoryId(1L);
		GitRepo b = activeRepo("org", "b");
		b.setId(UUID.randomUUID());
		b.setRepositoryId(2L);
		GitRepo c = activeRepo("org", "c");
		c.setId(UUID.randomUUID());
		c.setRepositoryId(3L);
		stubInstallationAndRepos(List.of(a, b, c));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(10)))
				.thenReturn(summaries(10, "a-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("b"), eq("main"), eq(1), eq(10)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_GATEWAY, "boom"));
		when(github.listCommits(eq("tok"), eq("org"), eq("c"), eq("main"), eq(1), eq(10)))
				.thenReturn(summaries(10, "c-"));
		when(projection.upsertBatch(eq(a), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());
		when(projection.upsertBatch(eq(c), any())).thenAnswer(inv -> ((List<?>) inv.getArgument(1)).size());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(job.getItemsProcessed()).isEqualTo(20);
		assertThat(job.getItemsFailed()).isEqualTo(1);
		verify(projection).upsertBatch(eq(a), any());
		verify(projection, never()).upsertBatch(eq(b), any());
		verify(projection).upsertBatch(eq(c), any());
		verify(repos, atLeastOnce()).save(b);
	}

	private void stubInstallationAndRepos(List<GitRepo> active) {
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.of(installation));
		when(repos.findFetchedByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE))
				.thenReturn(active);
		when(githubJwt.createJwt()).thenReturn("jwt");
		when(github.createInstallationToken("jwt", 99L)).thenReturn("tok");
	}

	private GitRepo activeRepo(String owner, String name) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setOwnerLogin(owner);
		repo.setName(name);
		repo.setFullName(owner + "/" + name);
		repo.setDefaultBranch("main");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		return repo;
	}

	private static List<CommitSummary> summaries(int count, String prefix) {
		List<CommitSummary> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			list.add(new CommitSummary(prefix + i, "msg " + i, null, null, null));
		}
		return list;
	}
}

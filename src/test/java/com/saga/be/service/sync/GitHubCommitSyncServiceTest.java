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

import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitSummary;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.GitCommitProjectionService;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
	private GithubProjectInstallationRepository projectInstallations;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private GitHubAppJwtService githubJwt;
	@Mock
	private GitCommitProjectionService projection;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private SyncJobClaimService claims;
	@Mock
	private PlatformTransactionManager transactionManager;

	private GitHubCommitSyncService service;
	private UUID projectId;
	private Project project;
	private AtomicInteger openTx;

	@BeforeEach
	void setUp() {
		openTx = new AtomicInteger();
		when(transactionManager.getTransaction(any(TransactionDefinition.class))).thenAnswer(inv -> {
			openTx.incrementAndGet();
			return new SimpleTransactionStatus();
		});
		org.mockito.Mockito.lenient().doAnswer(inv -> {
			openTx.decrementAndGet();
			return null;
		}).when(transactionManager).commit(any());
		org.mockito.Mockito.lenient().doAnswer(inv -> {
			openTx.decrementAndGet();
			return null;
		}).when(transactionManager).rollback(any());
		service = new GitHubCommitSyncService(
				repos,
				projectInstallations,
				github,
				githubJwt,
				projection,
				syncJobs,
				claims,
				transactionManager,
				new com.saga.be.realtime.ProjectRealtimePublisher(event -> {}));
		projectId = UUID.randomUUID();
		project = new Project();
		project.setId(projectId);
		when(claims.tryClaim(eq("GITHUB"), eq(projectId), any()))
				.thenAnswer(inv -> {
					SyncJobLog job = new SyncJobLog();
					job.setTargetSystem("GITHUB");
					job.setTargetId(projectId);
					job.setStatus(SyncJobStatus.RUNNING);
					job.setItemsProcessed(0);
					job.setItemsFailed(0);
					return Optional.of(job);
				});
		org.mockito.Mockito.lenient()
				.when(claims.markSucceeded(any(SyncJobLog.class), anyInt()))
				.thenAnswer(inv -> {
					SyncJobLog job = inv.getArgument(0);
					job.setStatus(SyncJobStatus.SUCCEEDED);
					job.setItemsProcessed(inv.getArgument(1));
					job.setItemsFailed(0);
					return job;
				});
		org.mockito.Mockito.lenient()
				.when(claims.markFailed(any(SyncJobLog.class), anyString(), anyString()))
				.thenAnswer(inv -> {
					SyncJobLog job = inv.getArgument(0);
					job.setStatus(SyncJobStatus.FAILED);
					job.setErrorCategory(inv.getArgument(1));
					job.setFailureStage(inv.getArgument(2));
					return job;
				});
		org.mockito.Mockito.lenient()
				.when(syncJobs.save(any(SyncJobLog.class)))
				.thenAnswer(inv -> inv.getArgument(0));
		org.mockito.Mockito.lenient().when(repos.save(any(GitRepo.class))).thenAnswer(inv -> inv.getArgument(0));
		org.mockito.Mockito.lenient()
				.when(projection.upsertBatch(any(), any()))
				.thenAnswer(inv -> {
					Object arg = inv.getArgument(1);
					return arg instanceof List<?> list ? list.size() : 0;
				});
	}

	@Test
	void claimCutoff_excludesPreClaimLocallyWithoutProviderSince() {
		GitRepo repo = activeRepo("org", "a");
		// Simulates cross-project reuse: another SAGA project's row for this exact physical
		// repository was created BEFORE this row, so the claim cutoff must protect that earlier
		// project's history.
		when(repos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						repo.getProvider(), repo.getRepositoryId(), projectId, repo.getCreatedAt()))
				.thenReturn(true);
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(
						new CommitSummary("pre", "old", "2025-12-01T00:00:00Z", null, null),
						new CommitSummary("edge", "edge", "2026-01-01T00:00:00Z", null, null),
						new CommitSummary("post", "new", "2026-06-01T12:00:00Z", null, null)));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(2);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(projection).upsertBatch(eq(repo), drafts.capture());
		assertThat(drafts.getValue()).extracting(CommitDraft::sha).containsExactly("edge", "post");
		// Full pagination without provider-side since (GitHub "last updated" != SAGA committedAt).
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100));
		verify(github, never())
				.listCommits(
						anyString(),
						anyString(),
						anyString(),
						anyString(),
						anyInt(),
						anyInt(),
						any());
	}

	@Test
	void firstEverOwner_importsExistingHistoryPredatingTheClaim() {
		// No other SAGA project's row for this physical repository predates this one (the
		// existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan mock defaults to
		// false), so the repository's full existing Git history -- including commits from before
		// the git_repo row was even created -- must be imported, not dropped merely because SAGA
		// was linked to an already-existing repository.
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(
						new CommitSummary("ancient", "old", "2020-01-01T00:00:00Z", null, null),
						new CommitSummary("post", "new", "2026-06-01T12:00:00Z", null, null)));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(2);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(projection).upsertBatch(eq(repo), drafts.capture());
		assertThat(drafts.getValue()).extracting(CommitDraft::sha).containsExactlyInAnyOrder("ancient", "post");
	}

	@Test
	void firstOwnerReclaimedAfterLaterProject_keepsFirstOwnerSemantics_aThenBThenA() {
		// A claims this physical repository first (repo.createdAt fixed at claim time). Project B
		// later claims and disconnects the SAME physical repository, then A reconnects reusing its
		// ORIGINAL row (createdAt unchanged). B's row existing somewhere in history must NOT make A
		// start requiring a cutoff -- B's createdAt is not BEFORE A's, so the exists-check must
		// answer false for A regardless of what happened to the repository afterward.
		GitRepo repo = activeRepo("org", "a");
		when(repos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						repo.getProvider(), repo.getRepositoryId(), projectId, repo.getCreatedAt()))
				.thenReturn(false);
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary(
						"predates-a-claim", "old", "2020-01-01T00:00:00Z", null, null)));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(1);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(projection).upsertBatch(eq(repo), drafts.capture());
		assertThat(drafts.getValue()).extracting(CommitDraft::sha).containsExactly("predates-a-claim");
	}

	@Test
	void laterOwnerRemainsCutoffProtected_bAfterA() {
		// B's row was created AFTER A's row for the same physical repository already existed. B
		// must stay cutoff-protected against A's pre-existing history: a pre-claim commit is
		// excluded, a post-claim commit is included.
		GitRepo repo = activeRepo("org", "b");
		when(repos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						repo.getProvider(), repo.getRepositoryId(), projectId, repo.getCreatedAt()))
				.thenReturn(true);
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "b")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("b"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(
						new CommitSummary("a-pre-claim", "A's old commit", "2025-06-01T00:00:00Z", null, null),
						new CommitSummary("b-post-claim", "B's own commit", "2026-06-01T00:00:00Z", null, null)));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(1);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		verify(projection).upsertBatch(eq(repo), drafts.capture());
		assertThat(drafts.getValue()).extracting(CommitDraft::sha).containsExactly("b-post-claim");
	}

	@Test
	void threeCommitPages_allImported() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p1-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(2), eq(100)))
				.thenReturn(summaries(100, "p2-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(3), eq(100)))
				.thenReturn(summaries(40, "p3-"));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(240);
		verify(github, times(3)).listCommits(anyString(), anyString(), anyString(), eq("main"), anyInt(), anyInt());
	}

	@Test
	void moreThanFiftyCommits_allImported() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p1-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(2), eq(100)))
				.thenReturn(summaries(20, "p2-"));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(120);
		assertThat(job.getItemsProcessed()).isGreaterThan(50);
	}

	@Test
	void overlappingBranches_uniqueShaOnce() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main", "dev"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(
						new CommitSummary("shared-1", "m", "2026-06-01T12:00:00Z", null, null),
						new CommitSummary("main-only", "m", "2026-06-01T12:00:00Z", null, null)));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("dev"), eq(1), eq(100)))
				.thenReturn(List.of(
						new CommitSummary("shared-1", "m", "2026-06-01T12:00:00Z", null, null),
						new CommitSummary("dev-only", "m", "2026-06-01T12:00:00Z", null, null)));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		when(projection.upsertBatch(eq(repo), any()))
				.thenAnswer(inv -> {
					Object arg = inv.getArgument(1);
					return arg instanceof List<?> list ? list.size() : 0;
				});

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(3);
		verify(projection, atLeastOnce()).upsertBatch(eq(repo), drafts.capture());
		Set<String> shas = new HashSet<>();
		for (List<CommitDraft> batch : drafts.getAllValues()) {
			batch.forEach(d -> assertThat(shas.add(d.sha())).isTrue());
		}
		assertThat(shas).containsExactlyInAnyOrder("shared-1", "main-only", "dev-only");
	}

	@Test
	void featureAndDevOnlyCommits_imported() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main", "dev", "feature/x"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary("main-1", "SAGA-1", "2026-06-01T12:00:00Z", null, null)));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("dev"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary("dev-1", "SAGA-2", "2026-06-01T12:00:00Z", null, null)));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("feature/x"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary("feat-1", "SAGA-3", "2026-06-01T12:00:00Z", null, null)));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(3);
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("feature/x"), eq(1), eq(100));
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("dev"), eq(1), eq(100));
		verify(github).listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100));
	}

	@Test
	void branchListPagination_allBranchesTraversed() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		List<String> page1 = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			page1.add("b" + i);
		}
		when(github.listBranches("tok", "org", "a")).thenReturn(concat(page1, List.of("last")));
		for (String branch : concat(page1, List.of("last"))) {
			when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq(branch), eq(1), eq(100)))
					.thenReturn(List.of(new CommitSummary(branch + "-sha", "m", "2026-06-01T12:00:00Z", null, null)));
		}

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getItemsProcessed()).isEqualTo(101);
		verify(github, times(101)).listCommits(anyString(), anyString(), anyString(), anyString(), eq(1), eq(100));
	}

	@Test
	void twoActiveRepos_bothFullyTraversed() {
		GitRepo fe = activeRepo("org", "saga-fe");
		fe.setId(UUID.randomUUID());
		GitRepo be = activeRepo("org", "saga-be");
		be.setId(UUID.randomUUID());
		stubInstallationAndRepos(List.of(fe, be));
		when(github.listBranches("tok", "org", "saga-fe")).thenReturn(List.of("main"));
		when(github.listBranches("tok", "org", "saga-be")).thenReturn(List.of("main", "dev"));
		when(github.listCommits(eq("tok"), eq("org"), eq("saga-fe"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(5, "fe-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("saga-be"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(5, "be-m-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("saga-be"), eq("dev"), eq(1), eq(100)))
				.thenReturn(summaries(3, "be-d-"));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(13);
		verify(github).listBranches("tok", "org", "saga-fe");
		verify(github).listBranches("tok", "org", "saga-be");
	}

	@Test
	void oneRepoFailure_doesNotSkipLaterRepos() {
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
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listBranches("tok", "org", "b")).thenThrow(new IntegrationException(
				IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_GATEWAY, "boom"));
		when(github.listBranches("tok", "org", "c")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(10, "a-"));
		when(github.listCommits(eq("tok"), eq("org"), eq("c"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(10, "c-"));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(job.getItemsProcessed()).isEqualTo(20);
		assertThat(job.getItemsFailed()).isEqualTo(1);
		verify(projection).upsertBatch(eq(a), any());
		verify(projection, never()).upsertBatch(eq(b), any());
		verify(projection).upsertBatch(eq(c), any());
		verify(repos, atLeastOnce()).save(b);
	}

	@Test
	void providerHttpOutsideJdbcTx() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenAnswer(inv -> {
			assertThat(openTx.get()).as("branch list HTTP outside JDBC").isZero();
			return List.of("main");
		});
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenAnswer(inv -> {
					assertThat(openTx.get()).as("commit list HTTP outside JDBC").isZero();
					return summaries(2, "x-");
				});
		when(projection.upsertBatch(eq(repo), any())).thenAnswer(inv -> {
			assertThat(openTx.get()).as("upsert inside short JDBC TX").isPositive();
			Object arg = inv.getArgument(1);
			return arg instanceof List<?> list ? list.size() : 0;
		});

		assertThat(service.initialSync(projectId).getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
	}

	@Test
	void upsertBatchOncePerPage_notPerCommit() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(summaries(100, "p-"));

		service.initialSync(projectId);

		verify(projection, times(1)).upsertBatch(eq(repo), any());
	}

	@Test
	void rerunFullSync_idempotentUpserts() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary("abc", "SAGA-9", "2026-06-01T12:00:00Z", null, null)));

		assertThat(service.initialSync(projectId).getItemsProcessed()).isEqualTo(1);
		assertThat(service.initialSync(projectId).getItemsProcessed()).isEqualTo(1);
		verify(projection, times(2)).upsertBatch(eq(repo), any());
	}

	@Test
	void projectedCommitsStillPassedToUpsertForAutoLink() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("a"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of(new CommitSummary("deadbeef", "SAGA-123 implement login", "2026-06-01T12:00:00Z", 1L, "alice")));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<CommitDraft>> drafts = ArgumentCaptor.forClass(List.class);
		when(projection.upsertBatch(eq(repo), any())).thenReturn(1);

		service.initialSync(projectId);

		verify(projection).upsertBatch(eq(repo), drafts.capture());
		assertThat(drafts.getValue().getFirst().message()).contains("SAGA-123");
	}

	@Test
	void rateLimit_failsNeverFakeSuccess() {
		GitRepo repo = activeRepo("org", "a");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "a"))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.GITHUB_RATE_LIMITED, HttpStatus.BAD_GATEWAY, "rate"));

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.FAILED);
		assertThat(job.getErrorCategory()).isEqualTo("GITHUB_RATE_LIMITED");
		verify(claims, never()).markSucceeded(any(), anyInt());
	}

	@Test
	void emptyRepository_succeedsWithZero() {
		GitRepo repo = activeRepo("org", "empty");
		stubInstallationAndRepos(List.of(repo));
		when(github.listBranches("tok", "org", "empty")).thenReturn(List.of("main"));
		when(github.listCommits(eq("tok"), eq("org"), eq("empty"), eq("main"), eq(1), eq(100)))
				.thenReturn(List.of());

		SyncJobLog job = service.initialSync(projectId);

		assertThat(job.getStatus()).isEqualTo(SyncJobStatus.SUCCEEDED);
		assertThat(job.getItemsProcessed()).isEqualTo(0);
		verify(projection, never()).upsertBatch(any(), any());
	}

	private void stubInstallationAndRepos(List<GitRepo> active) {
		GithubInstallation installation = new GithubInstallation();
		installation.setInstallationId(99L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setProject(project);
		membership.setInstallation(installation);
		when(projectInstallations.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(membership));
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
		repo.setProvider(com.saga.be.entity.enums.GitProvider.GITHUB);
		repo.setRepositoryId((long) Math.abs((owner + "/" + name).hashCode()));
		// Claim start used as Option B cutoff; commits in fixtures use timestamps after this.
		repo.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
		return repo;
	}

	private static List<CommitSummary> summaries(int count, String prefix) {
		List<CommitSummary> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			list.add(new CommitSummary(prefix + i, "msg " + i, "2026-06-01T12:00:00Z", null, null));
		}
		return list;
	}

	private static List<String> concat(List<String> a, List<String> b) {
		List<String> out = new ArrayList<>(a.size() + b.size());
		out.addAll(a);
		out.addAll(b);
		return out;
	}
}

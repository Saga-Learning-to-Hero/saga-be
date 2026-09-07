package com.saga.be.service.sync;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.integration.SyncJobLog;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitSummary;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.service.projection.GitCommitProjectionService;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import com.saga.be.service.projection.ProjectionMappings;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class GitHubCommitSyncService {

	/** GitHub list commits API max per_page (enforced by GitHubOAuthClient). */
	static final int GITHUB_COMMITS_PER_PAGE_MAX = 100;

	private static final Logger log = LoggerFactory.getLogger(GitHubCommitSyncService.class);

	private final GitRepoRepository repos;
	private final GithubInstallationRepository installations;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;
	private final GitCommitProjectionService projection;
	private final SyncJobLogRepository syncJobs;
	private final IntegrationProperties properties;
	private final SyncJobClaimService claims;
	private final TransactionTemplate writes;

	public GitHubCommitSyncService(
			GitRepoRepository repos,
			GithubInstallationRepository installations,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt,
			GitCommitProjectionService projection,
			SyncJobLogRepository syncJobs,
			IntegrationProperties properties,
			SyncJobClaimService claims,
			PlatformTransactionManager transactionManager) {
		this.repos = repos;
		this.installations = installations;
		this.github = github;
		this.githubJwt = githubJwt;
		this.projection = projection;
		this.syncJobs = syncJobs;
		this.properties = properties;
		this.claims = claims;
		this.writes = new TransactionTemplate(transactionManager);
	}

	/**
	 * Bounded newest-N backfill per ACTIVE selected repo. Provider HTTP outside JDBC TX.
	 * Per-repository failures are isolated; later healthy repos still sync.
	 */
	public SyncJobLog initialSync(UUID projectId) {
		SyncJobLog job = beginJob(projectId);
		if (job == null) {
			return alreadyRunning(projectId);
		}
		try {
			GithubInstallation installation = installations.findByProject_Id(projectId).orElse(null);
			if (installation == null || installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
				return fail(job, "GITHUB_INSTALLATION_INACTIVE", "persist", 0, 0);
			}
			List<GitRepo> active = repos.findFetchedByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE);
			if (active.isEmpty()) {
				return succeed(job, 0);
			}
			String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
			int limit = Math.max(1, properties.getGithubCommitBackfillLimit());
			int processed = 0;
			int repoFailures = 0;
			for (GitRepo repo : active) {
				if (repo.getOwnerLogin() == null || repo.getName() == null) {
					continue;
				}
				try {
					processed += syncOneRepository(token, repo, limit);
				} catch (IntegrationException ex) {
					repoFailures++;
					log.warn(
							"github repo sync failed projectId={} gitRepoId={} repositoryId={} fullName={} code={}",
							projectId,
							repo.getId(),
							repo.getRepositoryId(),
							repo.getFullName(),
							ex.getCode());
					markRepoFailure(repo);
				}
			}
			if (repoFailures > 0) {
				return fail(job, "GITHUB_REPO_SYNC_PARTIAL_OR_FAILED", "provider", processed, repoFailures);
			}
			return succeed(job, processed);
		} catch (IntegrationException ex) {
			log.warn(
					"github initial sync failed projectId={} code={}",
					projectId,
					ex.getCode());
			return fail(job, "GITHUB_SYNC_FAILED", "provider", 0, 0);
		} catch (RuntimeException ex) {
			log.warn("github initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
			return fail(job, "GITHUB_SYNC_FAILED", "provider", 0, 0);
		}
	}

	private int syncOneRepository(String token, GitRepo repo, int targetLimit) {
		int collected = 0;
		int page = 1;
		while (collected < targetLimit) {
			int remaining = targetLimit - collected;
			int perPage = Math.min(GITHUB_COMMITS_PER_PAGE_MAX, remaining);
			List<CommitSummary> providerPage =
					github.listCommits(token, repo.getOwnerLogin(), repo.getName(), repo.getDefaultBranch(), page, perPage);
			if (providerPage == null || providerPage.isEmpty()) {
				break;
			}
			int take = Math.min(remaining, providerPage.size());
			List<CommitDraft> drafts = new ArrayList<>(take);
			for (int i = 0; i < take; i++) {
				CommitSummary summary = providerPage.get(i);
				drafts.add(new CommitDraft(
						summary.sha(),
						summary.message(),
						ProjectionMappings.parseInstant(summary.committedAt()),
						summary.authorId() == null ? null : String.valueOf(summary.authorId()),
						summary.authorLogin(),
						repo.getDefaultBranch()));
			}
			Integer upserted = writes.execute(status -> projection.upsertBatch(repo, drafts));
			int applied = upserted == null ? 0 : upserted;
			collected += applied;
			if (providerPage.size() < perPage) {
				break;
			}
			page++;
		}
		if (collected > 0) {
			writes.executeWithoutResult(status -> {
				repo.setLastSyncedAt(LocalDateTime.now());
				repo.setConsecutiveFailures(0);
				repos.save(repo);
			});
		} else {
			writes.executeWithoutResult(status -> {
				repo.setLastSyncedAt(LocalDateTime.now());
				repo.setConsecutiveFailures(0);
				repos.save(repo);
			});
		}
		return collected;
	}

	private void markRepoFailure(GitRepo repo) {
		writes.executeWithoutResult(status -> {
			repo.setConsecutiveFailures(repo.getConsecutiveFailures() == null ? 1 : repo.getConsecutiveFailures() + 1);
			repos.save(repo);
		});
	}

	private SyncJobLog beginJob(UUID projectId) {
		return claims.tryClaim("GITHUB", projectId, SyncJobType.INITIAL).orElse(null);
	}

	private SyncJobLog alreadyRunning(UUID projectId) {
		SyncJobLog job = new SyncJobLog();
		job.setTargetSystem("GITHUB");
		job.setTargetId(projectId);
		job.setJobType(SyncJobType.INITIAL);
		job.setStatus(SyncJobStatus.FAILED);
		job.setErrorCategory("GITHUB_SYNC_ALREADY_RUNNING");
		job.setFailureStage("claim");
		job.setStartedAt(LocalDateTime.now());
		job.setCompletedAt(LocalDateTime.now());
		job.setItemsProcessed(0);
		job.setItemsFailed(0);
		return writes.execute(status -> syncJobs.save(job));
	}

	private SyncJobLog succeed(SyncJobLog job, int processed) {
		return writes.execute(status -> {
			job.setStatus(SyncJobStatus.SUCCEEDED);
			job.setItemsProcessed(processed);
			job.setItemsFailed(0);
			job.setCompletedAt(LocalDateTime.now());
			return syncJobs.save(job);
		});
	}

	private SyncJobLog fail(SyncJobLog job, String category, String stage, int processed, int failed) {
		return writes.execute(status -> {
			job.setStatus(SyncJobStatus.FAILED);
			job.setErrorCategory(category);
			job.setFailureStage(stage);
			job.setItemsProcessed(processed);
			job.setItemsFailed(failed);
			job.setCompletedAt(LocalDateTime.now());
			return syncJobs.save(job);
		});
	}
}

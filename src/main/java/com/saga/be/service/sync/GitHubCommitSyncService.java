package com.saga.be.service.sync;

import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.integration.SyncJobLog;
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
import com.saga.be.service.projection.GitRepoCommitClaimCutoff;
import com.saga.be.service.projection.ProjectionMappings;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Complete multi-branch GitHub commit reconciliation for ACTIVE selected repositories.
 * Canonical commit identity is (repo_id, sha_hash). {@code headRef} is best-effort metadata only
 * (single column; first-seen branch wins within a run).
 *
 * <p>Claim cutoff (Option B): list pages with full pagination, then upsert only commits with
 * {@code committedAt >= gitRepo.createdAt}. Local {@link GitRepoCommitClaimCutoff} is authoritative.
 * Provider {@code since} is intentionally not passed — GitHub's "last updated after" semantics are
 * not proven equivalent to SAGA's canonical committedAt field. {@code createdAt} is the project's
 * first claim and is preserved on same-project reconnect; A → B → A multi-tenure windows are
 * deferred technical debt.
 */
@Service
@Profile("!test")
public class GitHubCommitSyncService {

	/** GitHub list commits API max per_page (enforced by GitHubOAuthClient). */
	static final int GITHUB_COMMITS_PER_PAGE_MAX = 100;

	/**
	 * Defensive only against malformed infinite pagination — not a product data ceiling.
	 * ~100 pages × 100 commits = 10_000 commits per branch before FAIL.
	 */
	static final int MAX_COMMIT_PAGES_PER_BRANCH = 10_000;

	private static final Logger log = LoggerFactory.getLogger(GitHubCommitSyncService.class);

	private final GitRepoRepository repos;
	private final GithubProjectInstallationRepository projectInstallations;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;
	private final GitCommitProjectionService projection;
	private final SyncJobLogRepository syncJobs;
	private final SyncJobClaimService claims;
	private final TransactionTemplate writes;
	private final ProjectRealtimePublisher realtime;

	public GitHubCommitSyncService(
			GitRepoRepository repos,
			GithubProjectInstallationRepository projectInstallations,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt,
			GitCommitProjectionService projection,
			SyncJobLogRepository syncJobs,
			SyncJobClaimService claims,
			PlatformTransactionManager transactionManager,
			ProjectRealtimePublisher realtime) {
		this.repos = repos;
		this.projectInstallations = projectInstallations;
		this.github = github;
		this.githubJwt = githubJwt;
		this.projection = projection;
		this.syncJobs = syncJobs;
		this.claims = claims;
		this.writes = new TransactionTemplate(transactionManager);
		this.realtime = realtime;
	}

	/**
	 * Full reconciliation: all enumerable branches × full commit pagination per branch.
	 * Provider HTTP outside JDBC TX. Per-repository failures are isolated.
	 */
	public SyncJobLog initialSync(UUID projectId) {
		SyncJobLog job = beginJob(projectId);
		if (job == null) {
			return alreadyRunning(projectId);
		}
		boolean finalized = false;
		try {
			GithubInstallation installation = currentInstallation(projectId);
			if (installation == null || installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
				finalized = true;
				return fail(job, "GITHUB_INSTALLATION_INACTIVE", "persist", 0, 0);
			}
			List<GitRepo> active = repos.findFetchedByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE);
			if (active.isEmpty()) {
				finalized = true;
				return claims.markSucceeded(job, 0);
			}
			String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
			int processed = 0;
			int repoFailures = 0;
			for (GitRepo repo : active) {
				if (repo.getOwnerLogin() == null || repo.getName() == null) {
					continue;
				}
				try {
					processed += syncOneRepository(token, repo);
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
					if (ex.getCode() == IntegrationErrorCode.GITHUB_RATE_LIMITED) {
						finalized = true;
						return fail(job, ex.getCode().name(), "provider", processed, repoFailures);
					}
				}
			}
			if (repoFailures > 0) {
				finalized = true;
				return fail(job, "GITHUB_REPO_SYNC_PARTIAL_OR_FAILED", "provider", processed, repoFailures);
			}
			final int processedCount = processed;
			writes.executeWithoutResult(status -> {
				realtime.publish(ProjectRealtimeEventType.SYNC_STATUS_CHANGED, projectId);
				if (processedCount > 0) {
					realtime.publish(ProjectRealtimeEventType.COMMITS_CHANGED, projectId);
					realtime.publish(ProjectRealtimeEventType.TASK_LINKS_CHANGED, projectId);
				}
			});
			finalized = true;
			return claims.markSucceeded(job, processed);
		} catch (IntegrationException ex) {
			log.warn("github initial sync failed projectId={} code={}", projectId, ex.getCode());
			finalized = true;
			return fail(job, ex.getCode().name(), "provider", 0, 0);
		} catch (RuntimeException ex) {
			log.warn("github initial sync failed projectId={} type={}", projectId, ex.getClass().getSimpleName());
			finalized = true;
			return fail(job, "GITHUB_SYNC_FAILED", "provider", 0, 0);
		} finally {
			if (!finalized) {
				claims.markFailed(job, "SYNC_JOB_ABORTED", "finalize");
			}
		}
	}

	private int syncOneRepository(String token, GitRepo repo) {
		List<String> branches = orderBranches(github.listBranches(token, repo.getOwnerLogin(), repo.getName()), repo);
		if (branches.isEmpty() && repo.getDefaultBranch() != null && !repo.getDefaultBranch().isBlank()) {
			branches = List.of(repo.getDefaultBranch());
		}
		Set<String> seenShas = new HashSet<>();
		int uniqueUpserted = 0;
		for (String branch : branches) {
			uniqueUpserted += syncBranch(token, repo, branch, seenShas);
		}
		writes.executeWithoutResult(status -> {
			repo.setLastSyncedAt(LocalDateTime.now());
			repo.setConsecutiveFailures(0);
			repos.save(repo);
		});
		return uniqueUpserted;
	}

	private int syncBranch(String token, GitRepo repo, String branch, Set<String> seenShas) {
		int page = 1;
		int upserted = 0;
		while (page <= MAX_COMMIT_PAGES_PER_BRANCH) {
			// Do not pass provider-side `since`: GitHub documents it as "last updated after", which
			// is not proven equivalent to SAGA's canonical committedAt claim cutoff.
			List<CommitSummary> providerPage = github.listCommits(
					token, repo.getOwnerLogin(), repo.getName(), branch, page, GITHUB_COMMITS_PER_PAGE_MAX);
			if (providerPage == null || providerPage.isEmpty()) {
				break;
			}
			List<CommitDraft> drafts = new ArrayList<>(providerPage.size());
			for (CommitSummary summary : providerPage) {
				if (summary == null || summary.sha() == null || summary.sha().isBlank()) {
					continue;
				}
				if (!seenShas.add(summary.sha())) {
					continue; // already reconciled via another branch in this run
				}
				CommitDraft draft = new CommitDraft(
						summary.sha(),
						summary.message(),
						ProjectionMappings.parseInstant(summary.committedAt()),
						summary.authorId() == null ? null : String.valueOf(summary.authorId()),
						summary.authorLogin(),
						branch);
				if (!GitRepoCommitClaimCutoff.isEligible(draft, repo)) {
					continue;
				}
				drafts.add(draft);
			}
			if (!drafts.isEmpty()) {
				Integer applied = writes.execute(status -> projection.upsertBatch(repo, drafts));
				upserted += applied == null ? 0 : applied;
			}
			if (providerPage.size() < GITHUB_COMMITS_PER_PAGE_MAX) {
				break;
			}
			page++;
		}
		if (page > MAX_COMMIT_PAGES_PER_BRANCH) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_SYNC_INCOMPLETE,
					HttpStatus.BAD_GATEWAY,
					"GitHub commit pagination exceeded defensive guard for a branch.");
		}
		return upserted;
	}

	/** Prefer default branch first so headRef metadata favors the stored default when SHAs overlap. */
	static List<String> orderBranches(List<String> branches, GitRepo repo) {
		if (branches == null || branches.isEmpty()) {
			return List.of();
		}
		String defaults = repo.getDefaultBranch();
		LinkedHashSet<String> ordered = new LinkedHashSet<>();
		if (defaults != null && !defaults.isBlank()) {
			for (String name : branches) {
				if (defaults.equals(name)) {
					ordered.add(name);
					break;
				}
			}
		}
		ordered.addAll(branches);
		return List.copyOf(ordered);
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

	private SyncJobLog fail(SyncJobLog job, String category, String stage, int processed, int failed) {
		return writes.execute(status -> {
			SyncJobLog row = job.getId() == null ? job : syncJobs.findById(job.getId()).orElse(job);
			row.setStatus(SyncJobStatus.FAILED);
			row.setErrorCategory(category);
			row.setFailureStage(stage);
			row.setItemsProcessed(processed);
			row.setItemsFailed(failed);
			row.setCompletedAt(LocalDateTime.now());
			return syncJobs.save(row);
		});
	}

	private GithubInstallation currentInstallation(UUID projectId) {
		List<GithubProjectInstallation> memberships = projectInstallations.findByProject_IdWithInstallation(projectId);
		if (memberships.isEmpty()) {
			return null;
		}
		return memberships.getFirst().getInstallation();
	}
}

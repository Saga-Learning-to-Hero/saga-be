package com.saga.be.service.ai;

import com.saga.be.repository.AiAnalysisRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * A commit pushed while saga-ai or SAGA is redeploying (or while the provider is briefly down) gets an
 * automatic review that fails for no reason of its own. Once the outage is likely over, review it again,
 * once: the retry is a new attempt, and a retry that fails again is left for the user to retry by hand.
 */
@Component
@Profile("!test")
public class CommitReviewTransientRetryJob {

	/** Leave a redeploy time to finish before retrying. */
	static final Duration WAIT_BEFORE_RETRY = Duration.ofMinutes(1);
	/** Older failures are not retried automatically any more. */
	static final Duration RETRY_WINDOW = Duration.ofMinutes(30);
	static final int BATCH = 10;

	private static final Logger log = LoggerFactory.getLogger(CommitReviewTransientRetryJob.class);

	private final AiAnalysisRunRepository runs;
	private final AiAnalysisSubmissionService submissions;
	private final Executor background;
	private final Clock clock;
	/** Failed runs already handed to a retry by this instance: each is retried at most once. */
	private final Set<UUID> retried = ConcurrentHashMap.newKeySet();

	@Autowired
	public CommitReviewTransientRetryJob(
			AiAnalysisRunRepository runs,
			AiAnalysisSubmissionService submissions,
			@Qualifier("aiAutomationExecutor") Executor background) {
		this(runs, submissions, background, Clock.systemDefaultZone());
	}

	CommitReviewTransientRetryJob(AiAnalysisRunRepository runs, AiAnalysisSubmissionService submissions, Executor background, Clock clock) {
		this.runs = runs;
		this.submissions = submissions;
		this.background = background;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${saga.ai.commit-review-retry-delay:PT1M}")
	public void retryTransientFailures() {
		LocalDateTime now = LocalDateTime.now(clock);
		List<Object[]> rows = runs.findTransientlyFailedCommitReviews(
				AiRetryLineage.TRANSIENT_FAILURES, now.minus(RETRY_WINDOW), now.minus(WAIT_BEFORE_RETRY), PageRequest.of(0, BATCH));
		for (Object[] row : rows) {
			UUID projectId = (UUID) row[0];
			UUID commitId = (UUID) row[1];
			UUID failedRunId = (UUID) row[2];
			if (projectId == null || commitId == null || !retried.add(failedRunId)) continue;
			try {
				background.execute(() -> retry(projectId, commitId));
			} catch (RejectedExecutionException ex) {
				retried.remove(failedRunId);
				log.warn("commit review retry queue full projectId={} gitCommitId={}", projectId, commitId);
				return;
			}
		}
	}

	private void retry(UUID projectId, UUID commitId) {
		try {
			submissions.submitAutomatic(projectId, commitId)
					.ifPresent(submission -> log.info("commit review retried after a transient failure projectId={} gitCommitId={} created={}",
							projectId, commitId, submission.created()));
		} catch (RuntimeException ex) {
			log.warn("commit review retry failed projectId={} gitCommitId={} type={}", projectId, commitId, ex.getClass().getSimpleName());
		}
	}
}

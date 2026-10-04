package com.saga.be.service.ai;

import com.saga.be.dto.ai.CommitAiReviewDtos.BackfillResult;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * When a leader first saves a team key, history can already be huge. Queue only the newest
 * non-merge commits that have no review yet, on the automation thread, after the key is committed.
 */
@Component
@Profile("!test")
public class TeamKeyCommitReviewKickoff {

	static final int RECENT_ON_FIRST_KEY = 15;

	private static final Logger log = LoggerFactory.getLogger(TeamKeyCommitReviewKickoff.class);

	private final CommitAiReviewService reviews;
	private final Executor background;

	public TeamKeyCommitReviewKickoff(
			CommitAiReviewService reviews,
			@Qualifier("aiAutomationExecutor") Executor background) {
		this.reviews = reviews;
		this.background = background;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onFirstKey(TeamAiCredentialService.TeamKeyFirstSaved event) {
		try {
			background.execute(() -> reviewRecent(event));
		} catch (RejectedExecutionException ex) {
			log.warn("first-key commit review queue full projectId={}", event.projectId());
		}
	}

	private void reviewRecent(TeamAiCredentialService.TeamKeyFirstSaved event) {
		try {
			BackfillResult result = reviews.backfill(
					event.userId(), event.projectId(), RECENT_ON_FIRST_KEY);
			log.info(
					"first-key commit review queued projectId={} queued={} skipped={} failed={}",
					event.projectId(), result.queued(), result.skipped(), result.failed());
		} catch (RuntimeException ex) {
			log.warn(
					"first-key commit review failed projectId={} type={}",
					event.projectId(), ex.getClass().getSimpleName());
		}
	}
}

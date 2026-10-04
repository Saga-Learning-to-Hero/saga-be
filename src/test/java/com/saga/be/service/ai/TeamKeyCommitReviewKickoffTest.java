package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.dto.ai.CommitAiReviewDtos.BackfillResult;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** The first team key reviews only the newest commits, on the background thread. */
class TeamKeyCommitReviewKickoffTest {

	private final UUID userId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();

	@Test
	void theFirstKeyQueuesAReviewOfTheNewestCommits_offTheRequestThread() {
		CommitAiReviewService reviews = mock(CommitAiReviewService.class);
		when(reviews.backfill(any(), any(), anyInt())).thenReturn(new BackfillResult(3, 1, 0));
		List<Runnable> queued = new ArrayList<>();

		new TeamKeyCommitReviewKickoff(reviews, queued::add).onFirstKey(new TeamAiCredentialService.TeamKeyFirstSaved(userId, projectId));

		verifyNoInteractions(reviews);
		queued.getFirst().run();
		verify(reviews).backfill(userId, projectId, TeamKeyCommitReviewKickoff.RECENT_ON_FIRST_KEY);
	}

	@Test
	void aFullQueueOrAFailingBackfillNeverBreaksSavingTheKey() {
		CommitAiReviewService reviews = mock(CommitAiReviewService.class);
		when(reviews.backfill(any(), any(), anyInt())).thenThrow(new RuntimeException("github down"));
		var event = new TeamAiCredentialService.TeamKeyFirstSaved(userId, projectId);

		assertThatCode(() -> new TeamKeyCommitReviewKickoff(reviews, task -> { throw new RejectedExecutionException("full"); }).onFirstKey(event))
				.doesNotThrowAnyException();
		assertThatCode(() -> new TeamKeyCommitReviewKickoff(reviews, Runnable::run).onFirstKey(event))
				.doesNotThrowAnyException();
	}
}

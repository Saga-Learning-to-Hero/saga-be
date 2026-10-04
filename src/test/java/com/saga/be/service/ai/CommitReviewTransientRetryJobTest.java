package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** An automatic commit review that failed because something was briefly down is retried once. */
class CommitReviewTransientRetryJobTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID commitId = UUID.randomUUID();
	private final UUID failedRunId = UUID.randomUUID();
	private final AiAnalysisRunRepository runs = mock(AiAnalysisRunRepository.class);
	private final AiAnalysisSubmissionService submissions = mock(AiAnalysisSubmissionService.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-10-05T03:00:00Z"), ZoneId.of("Asia/Ho_Chi_Minh"));

	private List<Object[]> oneFailure() {
		List<Object[]> rows = new ArrayList<>();
		rows.add(new Object[] {projectId, commitId, failedRunId});
		return rows;
	}

	@Test
	void aTransientFailureFromOneToThirtyMinutesAgoIsReviewedAgain_offTheSchedulerThread() {
		when(runs.findTransientlyFailedCommitReviews(any(), any(), any(), any())).thenReturn(oneFailure());
		when(submissions.submitAutomatic(projectId, commitId)).thenReturn(Optional.empty());
		List<Runnable> queued = new ArrayList<>();

		new CommitReviewTransientRetryJob(runs, submissions, queued::add, clock).retryTransientFailures();

		LocalDateTime now = LocalDateTime.now(clock);
		verify(runs).findTransientlyFailedCommitReviews(eq(AiRetryLineage.TRANSIENT_FAILURES),
				eq(now.minusMinutes(30)), eq(now.minusMinutes(1)), any());
		verify(submissions, never()).submitAutomatic(any(), any());
		queued.getFirst().run();
		verify(submissions).submitAutomatic(projectId, commitId);
	}

	@Test
	void eachFailedRunIsRetriedOnlyOnce_andAFailingRetryNeverEscapes() {
		when(runs.findTransientlyFailedCommitReviews(any(), any(), any(), any())).thenReturn(oneFailure());
		when(submissions.submitAutomatic(projectId, commitId)).thenThrow(new RuntimeException("github down"));
		CommitReviewTransientRetryJob job = new CommitReviewTransientRetryJob(runs, submissions, Runnable::run, clock);

		job.retryTransientFailures();
		job.retryTransientFailures();

		verify(submissions, times(1)).submitAutomatic(projectId, commitId);
	}

	@Test
	void aFullQueueLeavesTheFailureForTheNextRound() {
		when(runs.findTransientlyFailedCommitReviews(any(), any(), any(), any())).thenReturn(oneFailure());
		when(submissions.submitAutomatic(projectId, commitId)).thenReturn(Optional.empty());

		new CommitReviewTransientRetryJob(runs, submissions, task -> { throw new RejectedExecutionException("full"); }, clock).retryTransientFailures();
		CommitReviewTransientRetryJob job = new CommitReviewTransientRetryJob(runs, submissions, Runnable::run, clock);
		job.retryTransientFailures();

		verify(submissions).submitAutomatic(projectId, commitId);
	}

	// ---------------- the retry rule itself

	private static AiAnalysisRun failed(String code, int attempt) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setStatus(AiAnalysisStatus.FAILED);
		run.setFailureCode(code);
		run.setRetryAttempt(attempt);
		return run;
	}

	@Test
	void automationRetriesOnlyAFirstAttemptThatFailedTransiently() {
		for (String code : AiRetryLineage.TRANSIENT_FAILURES) {
			assertThat(AiRetryLineage.createsCommitReviewRetry(failed(code, 0), AiInvocationOrigin.AUTOMATION)).as(code).isTrue();
			assertThat(AiRetryLineage.createsCommitReviewRetry(failed(code, 1), AiInvocationOrigin.AUTOMATION)).as(code).isFalse();
		}
		assertThat(AiRetryLineage.createsCommitReviewRetry(failed("AI_PROVIDER_RESULT_INVALID", 0), AiInvocationOrigin.AUTOMATION)).isFalse();
		assertThat(AiRetryLineage.createsCommitReviewRetry(failed("AI_PROVIDER_AUTH_FAILED", 0), AiInvocationOrigin.AUTOMATION)).isFalse();
		assertThat(AiRetryLineage.createsCommitReviewRetry(failed(null, 0), AiInvocationOrigin.AUTOMATION)).isFalse();
	}

	@Test
	void aUserMayRetryAnyFailedRun_andNothingRetriesARunThatDidNotFail() {
		assertThat(AiRetryLineage.createsCommitReviewRetry(failed("AI_PROVIDER_RESULT_INVALID", 3), AiInvocationOrigin.USER_REQUEST)).isTrue();
		AiAnalysisRun done = failed(null, 0);
		done.setStatus(AiAnalysisStatus.COMPLETED);
		assertThat(AiRetryLineage.createsCommitReviewRetry(done, AiInvocationOrigin.USER_REQUEST)).isFalse();
		assertThat(AiRetryLineage.createsCommitReviewRetry(done, AiInvocationOrigin.AUTOMATION)).isFalse();
		assertThat(AiRetryLineage.createsCommitReviewRetry(null, AiInvocationOrigin.AUTOMATION)).isFalse();
	}
}

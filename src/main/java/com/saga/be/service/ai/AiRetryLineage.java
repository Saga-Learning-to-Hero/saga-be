package com.saga.be.service.ai;

import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.util.Optional;
import java.util.Set;

/** Canonical identity groups immutable physical execution attempts. */
final class AiRetryLineage {
	private AiRetryLineage() {}

	static AiAnalysisRun effective(AiAnalysisRunRepository runs, String canonicalIdentityKey) {
		return runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(canonicalIdentityKey).orElse(null);
	}

	static boolean createsRetry(AiAnalysisRun effective, AiInvocationOrigin origin) {
		return effective != null && effective.getStatus() == AiAnalysisStatus.FAILED && origin == AiInvocationOrigin.USER_REQUEST;
	}

	/** Failures that say nothing about the commit or the key: the provider, saga-ai or SAGA itself was
	 * briefly unavailable (typically a redeploy). An automatic commit review retries those once. */
	static final Set<String> TRANSIENT_FAILURES = Set.of(
			"AI_PROVIDER_UNAVAILABLE",
			"AI_PROVIDER_TIMEOUT",
			"AI_PROVIDER_RATE_LIMITED",
			"AI_RUNTIME_UNAVAILABLE",
			"AI_RUNNING_STALE_RECOVERED");

	/** Commit review: a user may always retry a failed run; automation retries only a first attempt
	 * that failed transiently, so a duplicate webhook never re-runs a real failure. */
	static boolean createsCommitReviewRetry(AiAnalysisRun effective, AiInvocationOrigin origin) {
		if (createsRetry(effective, origin)) return true;
		return effective != null
				&& origin == AiInvocationOrigin.AUTOMATION
				&& effective.getStatus() == AiAnalysisStatus.FAILED
				&& (effective.getRetryAttempt() == null || effective.getRetryAttempt() == 0)
				&& effective.getFailureCode() != null // Set.of(...).contains(null) throws
				&& TRANSIENT_FAILURES.contains(effective.getFailureCode());
	}

	static int nextAttempt(AiAnalysisRun effective) {
		return effective == null ? 0 : (effective.getRetryAttempt() == null ? 1 : effective.getRetryAttempt() + 1);
	}

	static String physicalIdempotencyKey(String canonicalIdentityKey, int retryAttempt) {
		return retryAttempt == 0
				? canonicalIdentityKey
				: AiHashes.sha256(canonicalIdentityKey + "|retry-attempt|" + retryAttempt);
	}

	static void initialize(AiAnalysisRun run, String canonicalIdentityKey, int retryAttempt) {
		run.setCanonicalIdentityKey(canonicalIdentityKey);
		run.setRetryAttempt(retryAttempt);
		run.setIdempotencyKey(physicalIdempotencyKey(canonicalIdentityKey, retryAttempt));
	}

	static AiAnalysisRun concurrentWinner(AiAnalysisRunRepository runs, String canonicalIdentityKey, int retryAttempt, RuntimeException cause) {
		return runs.findByCanonicalIdentityKeyAndRetryAttempt(canonicalIdentityKey, retryAttempt).orElseThrow(() -> cause);
	}

	static RetryAttemptConflict conflict(String canonicalIdentityKey, int retryAttempt, RuntimeException cause) {
		return new RetryAttemptConflict(canonicalIdentityKey, retryAttempt, cause);
	}

	static final class RetryAttemptConflict extends RuntimeException {
		private final String canonicalIdentityKey;
		private final int retryAttempt;

		private RetryAttemptConflict(String canonicalIdentityKey, int retryAttempt, RuntimeException cause) {
			super(cause);
			this.canonicalIdentityKey = canonicalIdentityKey;
			this.retryAttempt = retryAttempt;
		}

		AiAnalysisRun winner(AiAnalysisRunRepository runs) {
			return concurrentWinner(runs, canonicalIdentityKey, retryAttempt, this);
		}
	}

	static boolean shouldEnqueueExisting(AiAnalysisRun run) {
		return run.getStatus() == AiAnalysisStatus.QUEUED;
	}
}

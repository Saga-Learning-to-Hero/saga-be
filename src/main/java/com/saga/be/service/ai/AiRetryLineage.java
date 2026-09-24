package com.saga.be.service.ai;

import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.repository.AiAnalysisRunRepository;
import java.util.Optional;

/** Canonical identity groups immutable physical execution attempts. */
final class AiRetryLineage {
	private AiRetryLineage() {}

	static AiAnalysisRun effective(AiAnalysisRunRepository runs, String canonicalIdentityKey) {
		return runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(canonicalIdentityKey).orElse(null);
	}

	static boolean createsRetry(AiAnalysisRun effective, AiInvocationOrigin origin) {
		return effective != null && effective.getStatus() == AiAnalysisStatus.FAILED && origin == AiInvocationOrigin.USER_REQUEST;
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

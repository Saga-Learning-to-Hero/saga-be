package com.saga.be.service.ai;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Submission services persist their AiAnalysisRun in their own transaction. Automatic triggers
 * call them from an {@code afterCommit} / {@code AFTER_COMMIT} callback, where the finished
 * ingestion transaction is still bound: a default REQUIRED template would join that completed
 * transaction ("No active transaction" on flush) and any {@code afterCommit} the submission
 * registers to enqueue the run would never fire. REQUIRES_NEW always opens a fresh transaction;
 * with no outer transaction (manual requests) it behaves exactly like REQUIRED.
 */
final class AiSubmissionTransactions {

	private AiSubmissionTransactions() {}

	static TransactionTemplate requiresNew(PlatformTransactionManager manager) {
		TransactionTemplate template = new TransactionTemplate(manager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}
}

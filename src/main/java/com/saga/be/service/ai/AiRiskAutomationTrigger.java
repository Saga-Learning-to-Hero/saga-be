package com.saga.be.service.ai;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Minimal automatic RISK_ANALYSIS trigger (section XII.C): fires once a Task Intelligence run for
 * a task has genuinely completed (not a late/discarded response -- see the {@code finalized}
 * guard at the call site in {@code AiTaskIntelligenceFinalizationService}). Bounded and idempotent
 * the same way every other automatic submission is: {@code AiRiskAnalysisSubmissionService}'s own
 * evidence-hash-based idempotency key means re-triggering for a task whose Task Intelligence
 * result and prior-signal evidence has not actually changed never creates a second paid run.
 */
@Component @Profile("!test")
public class AiRiskAutomationTrigger {
	private static final Logger log = LoggerFactory.getLogger(AiRiskAutomationTrigger.class);
	private final AiRiskAnalysisSubmissionService submissions;

	public AiRiskAutomationTrigger(AiRiskAnalysisSubmissionService submissions) { this.submissions = submissions; }

	public void afterTaskIntelligenceCompleted(UUID projectId, UUID taskId) {
		if (projectId == null || taskId == null) return;
		Runnable trigger = () -> {
			try {
				submissions.submitTaskAutomatic(projectId, taskId);
			} catch (Exception ex) {
				log.warn("automatic risk analysis submission failed projectId={} taskId={} error={}", projectId, taskId, ex.toString());
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { trigger.run(); } });
		} else {
			trigger.run();
		}
	}
}

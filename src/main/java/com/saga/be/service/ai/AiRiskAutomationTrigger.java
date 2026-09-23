package com.saga.be.service.ai;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Minimal automatic RISK_ANALYSIS trigger (section XII.C): fires once a Task Intelligence run for
 * a task has genuinely completed and committed. Bounded and idempotent
 * the same way every other automatic submission is: {@code AiRiskAnalysisSubmissionService}'s own
 * evidence-hash-based idempotency key means re-triggering for a task whose Task Intelligence
 * result and prior-signal evidence has not actually changed never creates a second paid run.
 */
@Component @Profile("!test")
public class AiRiskAutomationTrigger {
	private static final Logger log = LoggerFactory.getLogger(AiRiskAutomationTrigger.class);
	private final AiRiskAnalysisSubmissionService submissions;

	public AiRiskAutomationTrigger(AiRiskAnalysisSubmissionService submissions) { this.submissions = submissions; }

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void afterTaskIntelligenceCompleted(TaskIntelligenceCompletedEvent event) {
		if (event == null) return;
		UUID projectId = event.projectId();
		UUID taskId = event.taskId();
		if (projectId == null || taskId == null) return;
		try {
			submissions.submitTaskAutomatic(projectId, taskId);
		} catch (Exception ex) {
			log.warn("automatic risk analysis submission failed projectId={} taskId={} error={}", projectId, taskId, ex.toString());
		}
	}
}

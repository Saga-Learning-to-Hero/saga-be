package com.saga.be.service.ai;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Minimal automatic TASK_INTELLIGENCE trigger (section XII.B). Called by {@code
 * JiraTaskProjectionService} right after a batch of tasks persists (an authoritative revision
 * change -- status/assignee/dueDate/etc. -- is exactly what changes {@code artifactRevision} and
 * therefore the submission's idempotency key, so re-submitting an unchanged task is already a
 * guaranteed no-duplicate-call no-op there; this trigger does not need its own dedupe). Same
 * fail-safe shape as {@link AiCommitAutomationTrigger}: one task's failure never blocks another,
 * and never breaks Jira sync.
 */
@Component @Profile("!test")
public class AiTaskAutomationTrigger {
	private static final Logger log = LoggerFactory.getLogger(AiTaskAutomationTrigger.class);
	private final AiTaskIntelligenceSubmissionService submissions;

	public AiTaskAutomationTrigger(AiTaskIntelligenceSubmissionService submissions) { this.submissions = submissions; }

	public void afterTasksPersisted(UUID projectId, List<UUID> taskIds) {
		if (projectId == null || taskIds == null || taskIds.isEmpty()) return;
		Runnable trigger = () -> {
			for (UUID taskId : taskIds) {
				try {
					submissions.submitAutomatic(projectId, taskId);
				} catch (Exception ex) {
					log.warn("automatic task intelligence submission failed projectId={} taskId={} error={}", projectId, taskId, ex.toString());
				}
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() { @Override public void afterCommit() { trigger.run(); } });
		} else {
			trigger.run();
		}
	}
}

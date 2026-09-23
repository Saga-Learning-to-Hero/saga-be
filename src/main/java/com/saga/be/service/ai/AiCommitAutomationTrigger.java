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
 * Minimal automatic COMMIT_INTELLIGENCE trigger (section XII.A). Called by {@code
 * GitCommitProjectionService} right after a batch of commits persists; each candidate commit is
 * submitted via {@link AiAnalysisSubmissionService#submitAutomatic} only after the enclosing
 * ingestion transaction commits, and only once that submission's own course-credential/automation
 * gate passes (silently a no-op otherwise -- section XIII). A failure analyzing one commit is
 * caught and logged, never propagated: GitHub sync must keep working regardless of AI state.
 */
@Component @Profile("!test")
public class AiCommitAutomationTrigger {
	private static final Logger log = LoggerFactory.getLogger(AiCommitAutomationTrigger.class);
	private final AiAnalysisSubmissionService submissions;

	public AiCommitAutomationTrigger(AiAnalysisSubmissionService submissions) { this.submissions = submissions; }

	public void afterCommitsPersisted(UUID projectId, List<UUID> gitCommitIds) {
		if (projectId == null || gitCommitIds == null || gitCommitIds.isEmpty()) return;
		Runnable trigger = () -> {
			for (UUID commitId : gitCommitIds) {
				try {
					submissions.submitAutomatic(projectId, commitId);
				} catch (Exception ex) {
					log.warn("automatic commit intelligence submission failed projectId={} gitCommitId={} error={}", projectId, commitId, ex.toString());
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

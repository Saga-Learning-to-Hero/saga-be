package com.saga.be.service.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The three automation triggers (section XII/XVIII) share one critical safety property: a
 * failure submitting AI analysis for one commit/task must never block another, and must never
 * propagate up into the git/Jira ingestion call site that invoked the trigger (which would break
 * sync itself). These tests run the triggers with no active Spring transaction, so the
 * "afterCommit" branch is skipped and the submission call happens synchronously and observably.
 */
class AiAutomationTriggerTest {

	@Test
	void commitTriggerSubmitsEveryPersistedCommitExactlyOnce() {
		AiAnalysisSubmissionService submissions = mock(AiAnalysisSubmissionService.class);
		UUID projectId = UUID.randomUUID();
		UUID commitA = UUID.randomUUID(), commitB = UUID.randomUUID();

		new AiCommitAutomationTrigger(submissions).afterCommitsPersisted(projectId, List.of(commitA, commitB));

		verify(submissions, times(1)).submitAutomatic(projectId, commitA);
		verify(submissions, times(1)).submitAutomatic(projectId, commitB);
	}

	@Test
	void commitTriggerIsolatesOneCommitsFailureFromTheOthers() {
		AiAnalysisSubmissionService submissions = mock(AiAnalysisSubmissionService.class);
		UUID projectId = UUID.randomUUID();
		UUID failing = UUID.randomUUID(), succeeding = UUID.randomUUID();
		when(submissions.submitAutomatic(projectId, failing)).thenThrow(new RuntimeException("boom"));

		AiCommitAutomationTrigger trigger = new AiCommitAutomationTrigger(submissions);
		trigger.afterCommitsPersisted(projectId, List.of(failing, succeeding)); // must not throw

		verify(submissions).submitAutomatic(projectId, succeeding);
	}

	@Test
	void commitTriggerIsANoOpForNullOrEmptyInput() {
		AiAnalysisSubmissionService submissions = mock(AiAnalysisSubmissionService.class);
		AiCommitAutomationTrigger trigger = new AiCommitAutomationTrigger(submissions);
		trigger.afterCommitsPersisted(null, List.of(UUID.randomUUID()));
		trigger.afterCommitsPersisted(UUID.randomUUID(), List.of());
		trigger.afterCommitsPersisted(UUID.randomUUID(), null);
		verifyNoInteractions(submissions);
	}

	@Test
	void taskTriggerSubmitsEveryPersistedTaskExactlyOnce() {
		AiTaskIntelligenceSubmissionService submissions = mock(AiTaskIntelligenceSubmissionService.class);
		UUID projectId = UUID.randomUUID();
		UUID taskA = UUID.randomUUID(), taskB = UUID.randomUUID();

		new AiTaskAutomationTrigger(submissions).afterTasksPersisted(projectId, List.of(taskA, taskB));

		verify(submissions, times(1)).submitAutomatic(projectId, taskA);
		verify(submissions, times(1)).submitAutomatic(projectId, taskB);
	}

	@Test
	void taskTriggerIsolatesOneTasksFailureFromTheOthers() {
		AiTaskIntelligenceSubmissionService submissions = mock(AiTaskIntelligenceSubmissionService.class);
		UUID projectId = UUID.randomUUID();
		UUID failing = UUID.randomUUID(), succeeding = UUID.randomUUID();
		when(submissions.submitAutomatic(projectId, failing)).thenThrow(new RuntimeException("boom"));

		new AiTaskAutomationTrigger(submissions).afterTasksPersisted(projectId, List.of(failing, succeeding));

		verify(submissions).submitAutomatic(projectId, succeeding);
	}

	@Test
	void riskTriggerSubmitsExactlyOnceForTheCompletedTask() {
		AiRiskAnalysisSubmissionService submissions = mock(AiRiskAnalysisSubmissionService.class);
		UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID();

		new AiRiskAutomationTrigger(submissions).afterTaskIntelligenceCompleted(projectId, taskId);

		verify(submissions, times(1)).submitTaskAutomatic(projectId, taskId);
	}

	@Test
	void riskTriggerNeverThrowsWhenTheUnderlyingSubmissionFails() {
		AiRiskAnalysisSubmissionService submissions = mock(AiRiskAnalysisSubmissionService.class);
		UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID();
		when(submissions.submitTaskAutomatic(projectId, taskId)).thenThrow(new RuntimeException("boom"));

		new AiRiskAutomationTrigger(submissions).afterTaskIntelligenceCompleted(projectId, taskId); // must not throw

		verify(submissions).submitTaskAutomatic(projectId, taskId);
	}

	@Test
	void riskTriggerIsANoOpWhenProjectOrTaskIsMissing() {
		AiRiskAnalysisSubmissionService submissions = mock(AiRiskAnalysisSubmissionService.class);
		AiRiskAutomationTrigger trigger = new AiRiskAutomationTrigger(submissions);
		trigger.afterTaskIntelligenceCompleted(null, UUID.randomUUID());
		trigger.afterTaskIntelligenceCompleted(UUID.randomUUID(), null);
		verifyNoInteractions(submissions);
	}
}

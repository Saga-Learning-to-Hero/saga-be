package com.saga.be.service.attribution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.AttributionConfidence;
import com.saga.be.entity.enums.AttributionRiskSignal;
import com.saga.be.entity.enums.SagaCompletionState;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.WarningSeverity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AttributionAndCompletionTest {

	@Test
	void doneBeforeDueDateIsOnTime() {
		LocalDateTime due = LocalDateTime.of(2026, 8, 20, 12, 0);
		assertEquals(
				SagaCompletionState.COMPLETED_ON_TIME,
				TaskCompletionEvaluator.evaluate(TaskStatus.DONE, "done", due, due.minusHours(2), due));
	}

	@Test
	void doneAfterDueDateIsLate() {
		LocalDateTime due = LocalDateTime.of(2026, 8, 20, 12, 0);
		assertEquals(
				SagaCompletionState.COMPLETED_LATE,
				TaskCompletionEvaluator.evaluate(TaskStatus.DONE, "done", due, due.plusHours(3), due.plusHours(4)));
	}

	@Test
	void openAfterDueDateIsOverdue() {
		LocalDateTime due = LocalDateTime.of(2026, 8, 20, 12, 0);
		assertEquals(
				SagaCompletionState.OVERDUE,
				TaskCompletionEvaluator.evaluate(TaskStatus.IN_PROGRESS, "indeterminate", due, null, due.plusDays(1)));
	}

	@Test
	void noDueDateDoesNotBecomeLate() {
		assertEquals(
				SagaCompletionState.COMPLETED_NO_DUE_DATE,
				TaskCompletionEvaluator.evaluate(
						TaskStatus.DONE, "done", null, LocalDateTime.now(), LocalDateTime.now()));
	}

	@Test
	void commitDoesNotChangeJiraStatusSemantics() {
		assertEquals(
				SagaCompletionState.IN_PROGRESS,
				TaskCompletionEvaluator.evaluate(
						TaskStatus.IN_PROGRESS, "indeterminate", LocalDateTime.now().plusDays(2), null, LocalDateTime.now()));
	}

	@Test
	void assigneeMismatchIsSignalNotCheatingVerdict() {
		AttributionConfidenceEvaluator.Result result = AttributionConfidenceEvaluator.evaluate(
				new AttributionConfidenceEvaluator.Evidence(
						true, true, false, false, true, true, true, false, false));
		assertTrue(result.riskSignals().contains(AttributionRiskSignal.JIRA_ASSIGNEE_GITHUB_ACTOR_MISMATCH));
		assertFalse(result.riskSignals().contains(AttributionRiskSignal.UNUSUAL_ACTIVITY_PATTERN));
	}

	@Test
	void missingCommitSignatureAloneDoesNotFlag() {
		AttributionConfidenceEvaluator.Result result = AttributionConfidenceEvaluator.evaluate(
				new AttributionConfidenceEvaluator.Evidence(
						true, true, true, false, true, true, true, false, false));
		assertEquals(AttributionConfidence.HIGH, result.confidence());
	}

	@Test
	void identityChangeNearDeadlineFlags() {
		AttributionConfidenceEvaluator.Result result = AttributionConfidenceEvaluator.evaluate(
				new AttributionConfidenceEvaluator.Evidence(
						true, true, true, true, true, true, true, true, false));
		assertEquals(AttributionConfidence.FLAGGED, result.confidence());
		assertTrue(result.riskSignals().contains(AttributionRiskSignal.IDENTITY_CHANGED_NEAR_DEADLINE));
	}

	@Test
	void highSeverityEmailsLecturerAndMediumDoesNot() {
		assertTrue(AttributionWarningRouter.forSeverity(WarningSeverity.HIGH).emailLecturer());
		assertTrue(AttributionWarningRouter.forSeverity(WarningSeverity.CRITICAL).emailLecturer());
		assertFalse(AttributionWarningRouter.forSeverity(WarningSeverity.MEDIUM).emailLecturer());
	}
}

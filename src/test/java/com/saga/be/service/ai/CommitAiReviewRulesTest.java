package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.ai.AiCodeVerdict;
import com.saga.be.ai.AiCommitMessageVerdict;
import com.saga.be.ai.AiOverallDecision;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.ai.AiTaskAlignmentVerdict;
import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.dto.ai.CommitAiReviewDtos.Finding;
import com.saga.be.dto.ai.CommitAiReviewDtos.MessageReview;
import com.saga.be.dto.ai.CommitAiReviewDtos.Reason;
import com.saga.be.entity.enums.AiAnalysisStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class CommitAiReviewRulesTest {

	private static AiStructuredResult result(AiCommitMessageVerdict message, AiCodeVerdict code, AiTaskAlignmentVerdict task) {
		return new AiStructuredResult(
				new AiStructuredResult.CommitMessageAssessment(message, 70, "s", null, List.of()),
				new AiStructuredResult.CodeAssessment(code, 0.8, List.of(), List.of()),
				List.of(), task, List.of(), AiOverallDecision.INFORMATIONAL, false);
	}

	private static List<String> codes(CommitAiReviewRules.Outcome outcome) {
		return outcome.reasons().stream().map(Reason::code).toList();
	}

	@Test
	void goodMessageGoodCodeMatchingTaskPasses() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.PASS);
		assertThat(outcome.reasons()).isEmpty();
		assertThat(outcome.label()).isEqualTo("Đạt");
		assertThat(outcome.headline()).isEqualTo("Tên commit, code và task đều ổn.");
	}

	@Test
	void goodMessageButBadCode_warnsAboutTheCodeOnly() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.CONCERNS, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_CODE);
		assertThat(outcome.headline()).isEqualTo("Cần xem lại: Code có vấn đề.");
	}

	@Test
	void goodCodeButPoorMessage_warnsAboutTheMessageOnly() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.POOR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_MESSAGE);
	}

	@Test
	void everyProblemIsListed_messageCodeAndTaskMismatch() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.POOR, AiCodeVerdict.CONCERNS, AiTaskAlignmentVerdict.MISMATCH), true, true);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_MESSAGE, CommitAiReviewDtos.REASON_CODE, CommitAiReviewDtos.REASON_TASK_MISMATCH);
		assertThat(outcome.headline()).isEqualTo("Cần xem lại: Tên commit chưa rõ, Code có vấn đề, Không khớp task.");
	}

	@Test
	void partlyMatchingTaskIsAWarningToo() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.ADEQUATE, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.PARTIALLY_ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_MESSAGE, CommitAiReviewDtos.REASON_TASK_PARTIAL);
	}

	@Test
	void anAdequateMessageIsAWarning_notAPass() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.ADEQUATE, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_MESSAGE);
		assertThat(outcome.label()).isEqualTo("Cảnh báo");
		assertThat(outcome.headline()).isEqualTo("Cần xem lại: Tên commit chưa rõ.");
	}

	private static AiStructuredResult adequateWith(String... findingCodes) {
		List<com.saga.be.ai.AiFinding> findings = java.util.Arrays.stream(findingCodes)
				.map(code -> new com.saga.be.ai.AiFinding(code, "x", List.of())).toList();
		return new AiStructuredResult(
				new AiStructuredResult.CommitMessageAssessment(AiCommitMessageVerdict.ADEQUATE, 80, "s", null, findings),
				new AiStructuredResult.CodeAssessment(AiCodeVerdict.POSITIVE, 0.8, List.of(), List.of()),
				List.of(), AiTaskAlignmentVerdict.ALIGNS, List.of(), AiOverallDecision.INFORMATIONAL, false);
	}

	@Test
	void anAdequateMessageWhoseOnlyComplaintIsAKeyTheBranchHas_passes_soBadgeAndPanelAgree() {
		var context = new CommitAiReviewRules.MessageContext("feat: [FE][SG-06] refine graph evidence filters",
				"feat/SAGA-102-Refine-flows-UI/UX-FE", List.of("SAGA-102"));
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, adequateWith("MISSING_TASK_KEY"), true, true, context);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.PASS);
	}

	@Test
	void anAdequateMessageMissingAKeyNowhere_orWithOtherComplaints_warns() {
		var noKeyAnywhere = new CommitAiReviewRules.MessageContext("feat: refine filters", "feat/refine-filters", List.of("SAGA-102"));
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, adequateWith("MISSING_TASK_KEY"), true, true, noKeyAnywhere).status())
				.isEqualTo(CommitAiReviewDtos.WARNING);
		var keyOnBranch = new CommitAiReviewRules.MessageContext("feat: refine", "feat/SAGA-102-x", List.of("SAGA-102"));
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, adequateWith("MISSING_TASK_KEY", "VAGUE_MESSAGE"), true, true, keyOnBranch).status())
				.isEqualTo(CommitAiReviewDtos.WARNING);
	}

	@Test
	void aMissingTaskKeyFindingIsDroppedWhenTheBranchAlreadyHasTheLinkedKey() {
		var raw = new MessageReview("ADEQUATE", "Tạm ổn", 80, "Tên nên nhắc SAGA-102.",
				"feat: [FE][SG-06] refine graph evidence filters",
				List.of(new Finding("MISSING_TASK_KEY", "Tên/nhánh không chứa SAGA-102", List.of()),
						new Finding("VAGUE_MESSAGE", "Nên nói rõ phần nào của bộ lọc", List.of())));
		var shown = CommitAiReviewRules.presentMessage(raw,
				"feat: [FE][SG-06] refine graph evidence filters",
				"feat/SAGA-102-Refine-flows-UI/UX-FE",
				List.of("SAGA-102"));
		assertThat(shown.findings()).extracting(Finding::code).containsExactly("VAGUE_MESSAGE");
		assertThat(shown.suggestedMessage()).isNull();
		assertThat(shown.summary()).isEqualTo("Khóa SAGA-102 đã có trên tên commit hoặc tên nhánh.");
	}

	@Test
	void aRealMissingTaskKeyFindingStaysWhenNeitherMessageNorBranchHasTheKey() {
		var raw = new MessageReview("ADEQUATE", "Tạm ổn", 60, "Thiếu khóa.", "feat: SAGA-102 refine filters",
				List.of(new Finding("MISSING_TASK_KEY", "Không thấy khóa", List.of())));
		var shown = CommitAiReviewRules.presentMessage(raw, "feat: refine filters", "feat/refine-filters", List.of("SAGA-102"));
		assertThat(shown.findings()).extracting(Finding::code).containsExactly("MISSING_TASK_KEY");
		assertThat(shown.suggestedMessage()).isEqualTo("feat: SAGA-102 refine filters");
	}

	@Test
	void noTaskIsAWarningReadFromLiveLinks_notFromTheAiResult() {
		// reviewed before any task: the AI said NO_LINKED_TASK
		var unlinked = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.NO_LINKED_TASK), false, true);
		assertThat(unlinked.status()).isEqualTo(CommitAiReviewDtos.WARNING);
		assertThat(codes(unlinked)).containsExactly(CommitAiReviewDtos.REASON_NO_TASK);

		// a task attached by hand afterwards clears it at once
		var attached = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.NO_LINKED_TASK), true, true);
		assertThat(attached.status()).isEqualTo(CommitAiReviewDtos.PASS);
	}

	@Test
	void anOldMismatchIsDroppedOnceTheTaskLinkIsGone_noTaskTakesOver() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.POSITIVE, AiTaskAlignmentVerdict.MISMATCH), false, true);
		assertThat(codes(outcome)).containsExactly(CommitAiReviewDtos.REASON_NO_TASK);
	}

	@Test
	void mergeCommitsAreNeverReviewed_whateverElseIsTrue() {
		var outcome = CommitAiReviewRules.evaluate(true, AiAnalysisStatus.FAILED, null, false, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.SKIPPED_MERGE);
		assertThat(outcome.reasons()).isEmpty();
		assertThat(outcome.label()).isEqualTo("Merge – không đánh giá");
	}

	@Test
	void unknownParentCountIsTreatedAsANormalCommit() {
		assertThat(CommitAiReviewRules.evaluate(null, null, null, true, true).status()).isEqualTo(CommitAiReviewDtos.NOT_REVIEWED);
	}

	@Test
	void neverReviewed_dependsOnWhetherAKeyCouldReviewIt_andStillShowsNoTask() {
		var withKey = CommitAiReviewRules.evaluate(false, null, null, false, true);
		assertThat(withKey.status()).isEqualTo(CommitAiReviewDtos.NOT_REVIEWED);
		assertThat(codes(withKey)).containsExactly(CommitAiReviewDtos.REASON_NO_TASK);
		var withoutKey = CommitAiReviewRules.evaluate(false, null, null, true, false);
		assertThat(withoutKey.status()).isEqualTo(CommitAiReviewDtos.NO_KEY);
		assertThat(withoutKey.headline()).contains("chưa nhập key AI");
	}

	@Test
	void queuedOrRunningIsPending_failedOrCancelledIsFailed() {
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.QUEUED, null, true, true).status()).isEqualTo(CommitAiReviewDtos.PENDING);
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.RUNNING, null, true, true).status()).isEqualTo(CommitAiReviewDtos.PENDING);
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.FAILED, null, true, true).status()).isEqualTo(CommitAiReviewDtos.FAILED);
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.CANCELLED, null, true, true).status()).isEqualTo(CommitAiReviewDtos.FAILED);
		var failedNoTask = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.FAILED, null, false, true);
		assertThat(failedNoTask.status()).isEqualTo(CommitAiReviewDtos.FAILED);
		assertThat(codes(failedNoTask)).isEmpty();
		assertThat(failedNoTask.headline()).doesNotContain("Chưa gắn task");
	}

	@Test
	void nothingAssessable_isInsufficientData_notAPass() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.INSUFFICIENT_EVIDENCE, AiCodeVerdict.NOT_ASSESSABLE, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.INSUFFICIENT_DATA);
	}

	@Test
	void docsOnlyCommitWithAClearMessagePasses_codeNotAssessableIsNeutral() {
		var outcome = CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED,
				result(AiCommitMessageVerdict.CLEAR, AiCodeVerdict.NOT_ASSESSABLE, AiTaskAlignmentVerdict.ALIGNS), true, true);
		assertThat(outcome.status()).isEqualTo(CommitAiReviewDtos.PASS);
	}

	@Test
	void unreadableCompletedResult_isInsufficientData() {
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, null, true, true).status()).isEqualTo(CommitAiReviewDtos.INSUFFICIENT_DATA);
		assertThat(CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, null, false, true).status()).isEqualTo(CommitAiReviewDtos.WARNING);
	}

	@Test
	void verdictLabelsAreVietnamese() {
		assertThat(CommitAiReviewRules.messageVerdictLabel(AiCommitMessageVerdict.POOR)).isEqualTo("Chưa rõ");
		assertThat(CommitAiReviewRules.codeVerdictLabel(AiCodeVerdict.CONCERNS)).isEqualTo("Có vấn đề");
		assertThat(CommitAiReviewRules.taskVerdictLabel(AiTaskAlignmentVerdict.MISMATCH)).isEqualTo("Không khớp");
		assertThat(CommitAiReviewRules.messageVerdictLabel(null)).isNull();
	}
}

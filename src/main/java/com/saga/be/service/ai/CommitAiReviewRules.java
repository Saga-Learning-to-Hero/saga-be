package com.saga.be.service.ai;

import com.saga.be.ai.AiCodeVerdict;
import com.saga.be.ai.AiCommitMessageVerdict;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.ai.AiTaskAlignmentVerdict;
import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.dto.ai.CommitAiReviewDtos.Reason;
import com.saga.be.entity.enums.AiAnalysisStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The fixed rule that turns the AI's three verdicts into one commit status. The AI never decides
 * the status itself:
 *
 * <ul>
 *   <li>merge commit: SKIPPED_MERGE, nothing else is looked at;
 *   <li>a poor message, code with concerns, a task that does not (or only partly) match, or no task
 *       at all: WARNING with one reason each;
 *   <li>message and code both beyond the AI's evidence: INSUFFICIENT_DATA;
 *   <li>otherwise PASS.
 * </ul>
 *
 * "No task" is read from the live links (automatic or manual), not from the AI result, so attaching
 * a task by hand clears it immediately.
 */
public final class CommitAiReviewRules {

	private CommitAiReviewRules() {}

	public record Outcome(String status, List<Reason> reasons) {
		public String label() { return CommitAiReviewRules.label(status, reasons); }
		public String headline() { return CommitAiReviewRules.headline(status, reasons); }
	}

	/**
	 * @param merge          the commit is a merge (null = unknown, treated as a normal commit)
	 * @param runStatus      status of the latest review run, null when never reviewed
	 * @param result         parsed AI result of a COMPLETED run, null otherwise (or unreadable)
	 * @param taskLinked     the commit is attached to at least one live task right now
	 * @param keyAvailable   a team key or an allowed course key could review it
	 */
	public static Outcome evaluate(Boolean merge, AiAnalysisStatus runStatus, AiStructuredResult result, boolean taskLinked, boolean keyAvailable) {
		if (Boolean.TRUE.equals(merge)) return new Outcome(CommitAiReviewDtos.SKIPPED_MERGE, List.of());
		List<Reason> reasons = new ArrayList<>();
		if (runStatus == null) {
			if (!taskLinked) reasons.add(reason(CommitAiReviewDtos.REASON_NO_TASK));
			return new Outcome(keyAvailable ? CommitAiReviewDtos.NOT_REVIEWED : CommitAiReviewDtos.NO_KEY, List.copyOf(reasons));
		}
		switch (runStatus) {
			case QUEUED, RUNNING -> {
				if (!taskLinked) reasons.add(reason(CommitAiReviewDtos.REASON_NO_TASK));
				return new Outcome(CommitAiReviewDtos.PENDING, List.copyOf(reasons));
			}
			case FAILED, CANCELLED -> {
				if (!taskLinked) reasons.add(reason(CommitAiReviewDtos.REASON_NO_TASK));
				return new Outcome(CommitAiReviewDtos.FAILED, List.copyOf(reasons));
			}
			default -> { }
		}
		if (result == null || result.commitMessageAssessment() == null || result.codeAssessment() == null) {
			if (!taskLinked) reasons.add(reason(CommitAiReviewDtos.REASON_NO_TASK));
			return new Outcome(reasons.isEmpty() ? CommitAiReviewDtos.INSUFFICIENT_DATA : CommitAiReviewDtos.WARNING, List.copyOf(reasons));
		}
		AiCommitMessageVerdict message = result.commitMessageAssessment().verdict();
		AiCodeVerdict code = result.codeAssessment().verdict();
		if (message == AiCommitMessageVerdict.POOR) reasons.add(reason(CommitAiReviewDtos.REASON_MESSAGE));
		if (code == AiCodeVerdict.CONCERNS) reasons.add(reason(CommitAiReviewDtos.REASON_CODE));
		if (!taskLinked) {
			reasons.add(reason(CommitAiReviewDtos.REASON_NO_TASK));
		} else if (result.taskAlignmentSummary() == AiTaskAlignmentVerdict.MISMATCH) {
			reasons.add(reason(CommitAiReviewDtos.REASON_TASK_MISMATCH));
		} else if (result.taskAlignmentSummary() == AiTaskAlignmentVerdict.PARTIALLY_ALIGNS) {
			reasons.add(reason(CommitAiReviewDtos.REASON_TASK_PARTIAL));
		}
		if (!reasons.isEmpty()) return new Outcome(CommitAiReviewDtos.WARNING, List.copyOf(reasons));
		boolean messageUnknown = message == null || message == AiCommitMessageVerdict.INSUFFICIENT_EVIDENCE;
		boolean codeUnknown = code == null || code == AiCodeVerdict.NOT_ASSESSABLE || code == AiCodeVerdict.INSUFFICIENT_EVIDENCE;
		if (messageUnknown && codeUnknown) return new Outcome(CommitAiReviewDtos.INSUFFICIENT_DATA, List.of());
		return new Outcome(CommitAiReviewDtos.PASS, List.of());
	}

	public static Reason reason(String code) {
		return new Reason(code, switch (code) {
			case CommitAiReviewDtos.REASON_MESSAGE -> "Tên commit chưa rõ";
			case CommitAiReviewDtos.REASON_CODE -> "Code có vấn đề";
			case CommitAiReviewDtos.REASON_TASK_MISMATCH -> "Không khớp task";
			case CommitAiReviewDtos.REASON_TASK_PARTIAL -> "Khớp task một phần";
			case CommitAiReviewDtos.REASON_NO_TASK -> "Chưa gắn task";
			default -> code;
		});
	}

	static String label(String status, List<Reason> reasons) {
		return switch (status) {
			case CommitAiReviewDtos.PASS -> "Đạt";
			case CommitAiReviewDtos.WARNING -> "Cảnh báo";
			case CommitAiReviewDtos.PENDING -> "Đang đánh giá";
			case CommitAiReviewDtos.FAILED -> "Lỗi AI";
			case CommitAiReviewDtos.SKIPPED_MERGE -> "Merge – không đánh giá";
			case CommitAiReviewDtos.NO_KEY -> "Chưa có key AI";
			case CommitAiReviewDtos.NOT_REVIEWED -> "Chưa đánh giá";
			case CommitAiReviewDtos.INSUFFICIENT_DATA -> "Không đủ dữ liệu";
			default -> status;
		};
	}

	static String headline(String status, List<Reason> reasons) {
		String list = reasons.stream().map(Reason::label).collect(Collectors.joining(", "));
		return switch (status) {
			case CommitAiReviewDtos.PASS -> "Tên commit, code và task đều ổn.";
			case CommitAiReviewDtos.WARNING -> "Cần xem lại: " + list + ".";
			case CommitAiReviewDtos.PENDING -> "AI đang đánh giá commit này.";
			case CommitAiReviewDtos.FAILED -> "Lần đánh giá gần nhất bị lỗi.";
			case CommitAiReviewDtos.SKIPPED_MERGE -> "Merge commit chỉ gộp code đã có nên SAGA không đánh giá.";
			case CommitAiReviewDtos.NO_KEY -> "Nhóm chưa nhập key AI và lớp chưa cho dùng key của giảng viên.";
			case CommitAiReviewDtos.NOT_REVIEWED -> "Commit này chưa được AI đánh giá.";
			case CommitAiReviewDtos.INSUFFICIENT_DATA -> "AI không đủ dữ liệu để kết luận về tên commit và code.";
			default -> "";
		} + (reasons.isEmpty() || CommitAiReviewDtos.WARNING.equals(status) ? "" : " Lưu ý: " + list + ".");
	}

	static String messageVerdictLabel(AiCommitMessageVerdict verdict) {
		if (verdict == null) return null;
		return switch (verdict) {
			case CLEAR -> "Rõ ràng";
			case ADEQUATE -> "Tạm ổn";
			case POOR -> "Chưa rõ";
			case INSUFFICIENT_EVIDENCE -> "Không đủ dữ liệu";
		};
	}

	static String codeVerdictLabel(AiCodeVerdict verdict) {
		if (verdict == null) return null;
		return switch (verdict) {
			case POSITIVE -> "Tốt";
			case CONCERNS -> "Có vấn đề";
			case NOT_ASSESSABLE -> "Không đánh giá được";
			case INSUFFICIENT_EVIDENCE -> "Không đủ dữ liệu";
		};
	}

	static String taskVerdictLabel(AiTaskAlignmentVerdict verdict) {
		if (verdict == null) return null;
		return switch (verdict) {
			case ALIGNS -> "Khớp task";
			case PARTIALLY_ALIGNS -> "Khớp một phần";
			case MISMATCH -> "Không khớp";
			case NO_LINKED_TASK -> "Chưa gắn task";
			case INSUFFICIENT_EVIDENCE -> "Không đủ dữ liệu";
		};
	}
}

package com.saga.be.service.delay;

import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Verification;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.DelayCauseCategory;
import com.saga.be.entity.enums.TaskStatus;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * The pure rules of a delay case (no I/O): checking an explanation against the system's facts, and
 * deciding who looks at the case next. The agreed policy:
 * <ul>
 *   <li>The team leader confirms first. A case whose assignee is the leader goes straight to the lecturer.
 *   <li>The lecturer sees only: objective causes, OTHER, cases the leader disagrees with, and cases
 *       whose explanation the data contradicts. A subjective cause the leader agrees with and the data
 *       does not contradict is closed without the lecturer.
 * </ul>
 */
public final class DelayCaseRules {

	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/** Facts about the task the assignee says blocked them; null when no task was named or found. */
	public record BlockerFacts(String key, TaskStatus status, LocalDateTime completedAt) {}

	public record Check(Verification verification, String note) {}

	private DelayCaseRules() {}

	/**
	 * Compares the explanation with what SAGA can see. MISMATCH only when the data clearly
	 * contradicts it; anything the data cannot settle is UNVERIFIABLE (a person judges it).
	 */
	public static Check verify(DelayCauseCategory category, DelaySignals signals, BlockerFacts blocker, LocalDateTime dueDate) {
		return switch (category) {
			case BLOCKED_BY_TASK -> verifyBlocker(blocker, dueDate);
			case SCOPE_CHANGED -> signals.storyPointIncreased()
					? new Check(Verification.CONSISTENT, "Story point đã tăng sau khi task bắt đầu.")
					: unverifiable("Hệ thống không thấy story point tăng sau khi task bắt đầu (lịch sử chỉ có từ khi tính năng được bật).");
			case REASSIGNED_LATE -> signals.reassignedNearDue()
					? new Check(Verification.CONSISTENT, "Task được giao cho người này trong 3 ngày trước hạn chót.")
					: unverifiable("Hệ thống không thấy lần giao task nào sát hạn chót (lịch sử chỉ có từ khi tính năng được bật).");
			case SCHEDULE_CHANGED -> signals.dueDateChanged()
					? new Check(Verification.CONSISTENT, "Hạn chót đã bị đổi sau khi task bắt đầu.")
					: unverifiable("Hệ thống không thấy hạn chót bị đổi (lịch sử chỉ có từ khi tính năng được bật).");
			case TECHNICAL_ISSUE, PERSONAL_EMERGENCY -> unverifiable("Hệ thống không tự đối chiếu được, cần xem ghi chú và minh chứng.");
			case OTHER -> unverifiable("Nguyên nhân khác: giảng viên quyết định.");
			case STARTED_LATE, UNDERESTIMATED, NO_PROGRESS -> new Check(Verification.CONSISTENT, "Nguyên nhân chủ quan do người làm tự nhận.");
		};
	}

	private static Check verifyBlocker(BlockerFacts blocker, LocalDateTime dueDate) {
		if (blocker == null) {
			return new Check(Verification.MISMATCH, "Không tìm thấy task chặn trong project.");
		}
		boolean done = blocker.status() == TaskStatus.DONE;
		if (!done) {
			return new Check(Verification.CONSISTENT, "Task " + blocker.key() + " vẫn chưa xong.");
		}
		if (blocker.completedAt() == null) {
			return unverifiable("Task " + blocker.key() + " đã xong nhưng không rõ thời điểm hoàn thành.");
		}
		// Blocking only matters if the blocker was still unfinished during this task's due day.
		if (blocker.completedAt().toLocalDate().isBefore(dueDate.toLocalDate())) {
			return new Check(
					Verification.MISMATCH,
					"Task " + blocker.key() + " đã xong ngày " + blocker.completedAt().format(DAY)
							+ ", trước hạn chót của task này (" + dueDate.format(DAY) + ").");
		}
		return new Check(
				Verification.CONSISTENT,
				"Task " + blocker.key() + " xong ngày " + blocker.completedAt().format(DAY)
						+ ", không sớm hơn hạn chót của task này (" + dueDate.format(DAY) + ").");
	}

	private static Check unverifiable(String note) {
		return new Check(Verification.UNVERIFIABLE, note);
	}

	/** Next step once the assignee explains. */
	public static DelayCaseStatus afterExplanation(boolean assigneeIsLeader) {
		return assigneeIsLeader ? DelayCaseStatus.AWAITING_LECTURER : DelayCaseStatus.AWAITING_LEADER;
	}

	/**
	 * Next step once the leader decides: closed as subjective only when the cause is subjective,
	 * the leader agrees and the data does not contradict the explanation; otherwise the lecturer.
	 */
	public static DelayCaseStatus afterLeader(DelayCauseCategory category, Verification verification, LeaderDecision decision) {
		boolean closeNow = decision == LeaderDecision.AGREE
				&& category.group() == DelayCauseCategory.Group.SUBJECTIVE
				&& verification != Verification.MISMATCH;
		return closeNow ? DelayCaseStatus.CLOSED_SUBJECTIVE : DelayCaseStatus.AWAITING_LECTURER;
	}
}

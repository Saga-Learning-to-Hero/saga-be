package com.saga.be.dto.delay;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Outcome;
import com.saga.be.entity.enums.DelayCauseCategory;
import com.saga.be.service.delay.DelaySignals;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the delay case API. */
public final class DelayCaseDtos {

	private DelayCaseDtos() {}

	/**
	 * The assignee's explanation. {@code note} is required for OTHER, TECHNICAL_ISSUE and
	 * PERSONAL_EMERGENCY; {@code blockingTaskId} is required for BLOCKED_BY_TASK (another task of
	 * the same project); {@code evidenceUrl} is an optional http(s) link to proof.
	 */
	public record ExplainRequest(
			@NotNull DelayCauseCategory category,
			@Size(max = 1000) String note,
			UUID blockingTaskId,
			@Size(max = 2048) String evidenceUrl) {}

	/** {@code comment} is required when the leader disagrees. */
	public record LeaderReviewRequest(@NotNull LeaderDecision decision, @Size(max = 1000) String comment) {}

	public record LecturerReviewRequest(@NotNull Outcome outcome, @Size(max = 1000) String comment) {}

	public record TaskRef(UUID id, String externalKey, String title) {}

	public record StudentRef(UUID studentProfileId, UUID userId, String fullName, String studentCode) {}

	/** Where the case belongs, so a cross-course list (the lecturer queue) can label each row. */
	public record ProjectContext(
			String projectName,
			UUID teamId,
			Integer teamNo,
			String teamName,
			UUID courseId,
			String courseCode,
			String courseName) {}

	/** What the viewer may do now; the FE shows only the matching buttons. */
	public record Permissions(boolean canExplain, boolean canLeaderReview, boolean canLecturerReview, boolean canReopen) {}

	/**
	 * One delay case. {@code explanationNote}, {@code evidenceUrl} and the reviewers' comments are
	 * returned only to the assignee, the team leader and the lecturer (null for other members).
	 * Timestamps carry the Vietnam offset ({@code +07:00}); {@code dueDate} is a calendar day.
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record DelayCaseResponse(
			UUID id,
			UUID projectId,
			ProjectContext context,
			TaskRef task,
			StudentRef student,
			LocalDate dueDate,
			OffsetDateTime openedAt,
			OffsetDateTime explanationDueAt,
			String status,
			String category,
			/** OBJECTIVE / SUBJECTIVE / OTHER, from the category. */
			String categoryGroup,
			String explanationNote,
			TaskRef blockingTask,
			String evidenceUrl,
			OffsetDateTime explainedAt,
			DelaySignals signals,
			String verification,
			String verificationNote,
			String leaderDecision,
			String leaderComment,
			OffsetDateTime leaderReviewedAt,
			String lecturerOutcome,
			String lecturerComment,
			OffsetDateTime lecturerReviewedAt,
			OffsetDateTime closedAt,
			String closeReason,
			Permissions permissions) {}

	/**
	 * On-time rate of one member. A task counts once its due day is over or it is done; it is late
	 * when finished (or still open) after its due day. Late tasks whose delay case was accepted as
	 * objective are excused: they count as on time. {@code onTimeRate} is null when nothing counts yet.
	 */
	public record MemberOnTimeRate(
			UUID studentProfileId,
			String fullName,
			String studentCode,
			int evaluatedTasks,
			int onTimeTasks,
			int lateTasks,
			int excusedLateTasks,
			BigDecimal onTimeRate) {}

	public record OnTimeRateResponse(UUID projectId, LocalDate asOf, List<MemberOnTimeRate> members) {}
}

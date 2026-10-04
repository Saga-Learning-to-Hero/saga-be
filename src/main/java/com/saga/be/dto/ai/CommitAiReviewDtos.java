package com.saga.be.dto.ai;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * AI review of one commit, as shown next to it in the commit list (summary) and in the detail
 * panel. The status is decided by SAGA from the AI's three verdicts with a fixed rule, never by the
 * AI itself. Display only: contribution scoring never reads it.
 */
public final class CommitAiReviewDtos {

	private CommitAiReviewDtos() {}

	public static final String PASS = "PASS";
	public static final String WARNING = "WARNING";
	public static final String PENDING = "PENDING";
	public static final String FAILED = "FAILED";
	public static final String SKIPPED_MERGE = "SKIPPED_MERGE";
	public static final String NO_KEY = "NO_KEY";
	public static final String NOT_REVIEWED = "NOT_REVIEWED";
	public static final String INSUFFICIENT_DATA = "INSUFFICIENT_DATA";

	public static final String REASON_MESSAGE = "MESSAGE";
	public static final String REASON_CODE = "CODE";
	public static final String REASON_TASK_MISMATCH = "TASK_MISMATCH";
	public static final String REASON_TASK_PARTIAL = "TASK_PARTIAL";
	public static final String REASON_NO_TASK = "NO_TASK";

	@Schema(description = "One reason a commit needs attention")
	public record Reason(
			@Schema(description = "MESSAGE | CODE | TASK_MISMATCH | TASK_PARTIAL | NO_TASK") String code,
			@Schema(description = "Short Vietnamese label, e.g. 'Tên commit chưa rõ'") String label) {}

	@Schema(description = "Badge data for the commit list")
	public record Summary(
			@Schema(description = "PASS | WARNING | PENDING | FAILED | SKIPPED_MERGE | NO_KEY | NOT_REVIEWED | INSUFFICIENT_DATA") String status,
			@Schema(description = "Short Vietnamese badge text") String label,
			List<Reason> reasons,
			@Schema(description = "The commit is attached to at least one task (automatically or by hand)") boolean taskLinked) {}

	public record Location(
			@Schema(description = "DIFF_HUNK | COMMIT_MESSAGE | TASK_FIELD | SYLLABUS | OTHER") String kind,
			@Schema(description = "File path for code locations") String path,
			@Schema(description = "Hunk id within the file, e.g. H2") String hunkId,
			@Schema(description = "The exact diff text the finding points at (may be cut to 4000 characters)") String snippet,
			@Schema(description = "Readable label: file, task key or syllabus item") String label) {}

	public record Finding(
			@Schema(description = "Machine code from the AI, e.g. VAGUE_MESSAGE") String code,
			@Schema(description = "Vietnamese explanation: what is wrong, where, and how to fix it") String message,
			List<Location> locations) {}

	public record MessageReview(
			@Schema(description = "CLEAR | ADEQUATE | POOR | INSUFFICIENT_EVIDENCE") String verdict,
			String verdictLabel,
			Integer score,
			String summary,
			@Schema(description = "A better commit message the AI proposes, when it has one") String suggestedMessage,
			List<Finding> findings) {}

	public record Coverage(Integer filesTotal, Integer filesAnalyzed, Integer filesOmitted, boolean complete) {}

	public record CodeReview(
			@Schema(description = "POSITIVE | CONCERNS | NOT_ASSESSABLE | INSUFFICIENT_EVIDENCE") String verdict,
			String verdictLabel,
			Double confidence,
			List<Finding> findings,
			Coverage coverage) {}

	public record TaskAlignment(
			UUID taskId,
			String externalKey,
			String title,
			@Schema(description = "ALIGNS | PARTIALLY_ALIGNS | MISMATCH | INSUFFICIENT_EVIDENCE") String verdict,
			String verdictLabel,
			Double confidence,
			String summary) {}

	public record LinkedTask(
			UUID taskId,
			String externalKey,
			String title,
			String status,
			@Schema(description = "AUTO (Jira key in the commit/branch) | MANUAL (attached by hand: display only, never scored)") String source,
			@Schema(description = "The caller may detach it (manual links only)") boolean canUnlink) {}

	public record TaskReview(
			@Schema(description = "ALIGNS | PARTIALLY_ALIGNS | MISMATCH | NO_LINKED_TASK | INSUFFICIENT_EVIDENCE, null before a review") String verdict,
			String verdictLabel,
			List<TaskAlignment> alignments,
			List<LinkedTask> linkedTasks) {}

	public record Detail(
			UUID commitId,
			String sha,
			String message,
			boolean merge,
			String status,
			String label,
			String headline,
			List<Reason> reasons,
			@Schema(description = "The caller can (re)request the AI review now") boolean canRequestReview,
			@Schema(description = "Why not: MERGE (merge commits are never reviewed) | READ_ONLY (lecturer: reads only) | NOT_ALLOWED (a member, not their task's commit) | NO_KEY (no team key and no course key allowed)") String reviewBlockedReason,
			@Schema(description = "Key the next review would use: TEAM | COURSE | NONE") String keySource,
			@Schema(description = "The caller may attach/detach tasks by hand (commit author or team leader)") boolean canManageLinks,
			UUID analysisId,
			@Schema(description = "When the review finished, with its +07:00 offset (Vietnam time)") OffsetDateTime reviewedAt,
			String provider,
			String modelId,
			MessageReview messageReview,
			CodeReview codeReview,
			TaskReview taskReview,
			@Schema(description = "Readable failure when status = FAILED") AiFailureResponse failure) {}

	public record BackfillResult(
			@Schema(description = "Reviews queued now") int queued,
			@Schema(description = "Commits already reviewed / in progress / merge") int skipped,
			@Schema(description = "Commits that could not be queued") int failed) {}

	public record ManualLinkRequest(@Schema(description = "Task of the same project") UUID taskId) {}
}

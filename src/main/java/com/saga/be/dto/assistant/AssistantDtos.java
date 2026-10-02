package com.saga.be.dto.assistant;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies of the project assistant (chatbox) API. */
public final class AssistantDtos {

	public static final int MAX_QUESTION_LENGTH = 1000;

	private AssistantDtos() {}

	public record AskRequest(@NotBlank @Size(max = MAX_QUESTION_LENGTH) String question) {}

	public record FeedbackRequest(@NotNull Boolean helpful, @Size(max = 500) String comment) {}

	/**
	 * Whether the assistant can answer with AI in this project right now. {@code keySource}: COURSE
	 * (the class's own key), PLATFORM (system key, allowed by the lecturer) or UNAVAILABLE (answers
	 * fall back to backend-built summaries of the data).
	 */
	public record StatusResponse(
			boolean enabled, boolean aiConfigured, String keySource, int dailyLimit, long usedToday, long remainingToday) {}

	public record ConversationResponse(
			UUID id, UUID projectId, String title, OffsetDateTime createdAt, OffsetDateTime lastMessageAt) {}

	/**
	 * A fact the answer relies on. kind: PROJECT, SPRINT, TASK, MEMBER (id = studentProfileId),
	 * DELAY_CASE (taskId = its task), COMMIT (id = gitCommitId, sha = full sha).
	 */
	public record Citation(String kind, UUID id, String label, UUID taskId, String sha) {}

	public record Feedback(Boolean helpful, String comment, OffsetDateTime at) {}

	/**
	 * One message. Fields after {@code createdAt} are set on ASSISTANT messages only.
	 * answerSource: AI (written by the model from the facts) or FALLBACK (built by the backend because
	 * the AI was unavailable; fallbackReason says why). verified: every citation matched a fact the
	 * backend supplied for this question (removedCitationCount citations did not and were dropped).
	 */
	@JsonInclude(JsonInclude.Include.ALWAYS)
	public record MessageResponse(
			UUID id,
			UUID conversationId,
			String role,
			String content,
			OffsetDateTime createdAt,
			String answerSource,
			Boolean insufficientData,
			Boolean outOfScope,
			Boolean verified,
			Integer removedCitationCount,
			List<Citation> citations,
			List<String> followUpQuestions,
			String fallbackReason,
			Feedback feedback) {}

	public record AskResponse(MessageResponse question, MessageResponse answer) {}
}

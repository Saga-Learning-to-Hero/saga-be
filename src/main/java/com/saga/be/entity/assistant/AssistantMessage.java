package com.saga.be.entity.assistant;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One turn of a conversation: the user's question, or the assistant's answer. An answer keeps the
 * citations that survived the backend's check against the facts it sent, and how it was produced.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
		name = "assistant_message",
		indexes = {
			@Index(name = "ix_assistant_message_conversation", columnList = "conversation_id, created_at"),
			@Index(name = "ix_assistant_message_role_created", columnList = "role, created_at")
		})
public class AssistantMessage extends BaseEntity {

	public enum Role { USER, ASSISTANT }

	/** AI: written by the model from the facts. FALLBACK: built by the backend when AI is unavailable. */
	public enum AnswerSource { AI, FALLBACK }

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "conversation_id", nullable = false)
	private AssistantConversation conversation;

	@Enumerated(EnumType.STRING)
	@Column(name = "role", length = 16, nullable = false)
	private Role role;

	@Column(name = "content", columnDefinition = "TEXT", nullable = false)
	private String content;

	@Enumerated(EnumType.STRING)
	@Column(name = "answer_source", length = 16)
	private AnswerSource answerSource;

	@Column(name = "insufficient_data")
	private Boolean insufficientData;

	@Column(name = "out_of_scope")
	private Boolean outOfScope;

	/** Every citation pointed at a fact the backend sent for this question. */
	@Column(name = "verified")
	private Boolean verified;

	/** Citations the model returned that matched no supplied fact (dropped). */
	@Column(name = "removed_citation_count")
	private Integer removedCitationCount;

	@Column(name = "citations_json", columnDefinition = "TEXT")
	private String citationsJson;

	@Column(name = "follow_ups_json", columnDefinition = "TEXT")
	private String followUpsJson;

	/** Safe code of why the AI could not answer (FALLBACK only). */
	@Column(name = "fallback_reason", length = 64)
	private String fallbackReason;

	/** COURSE or PLATFORM: whose AI key answered (AI only). */
	@Column(name = "credential_source", length = 16)
	private String credentialSource;

	@Column(name = "provider_key", length = 64)
	private String providerKey;

	@Column(name = "model_id", length = 128)
	private String modelId;

	@Column(name = "latency_ms")
	private Long latencyMs;

	@Column(name = "feedback_helpful")
	private Boolean feedbackHelpful;

	@Column(name = "feedback_comment", length = 500)
	private String feedbackComment;

	@Column(name = "feedback_at")
	private LocalDateTime feedbackAt;
}

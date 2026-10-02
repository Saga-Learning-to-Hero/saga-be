package com.saga.be.service.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.assistant.AssistantDtos;
import com.saga.be.dto.assistant.AssistantDtos.AskResponse;
import com.saga.be.dto.assistant.AssistantDtos.Citation;
import com.saga.be.dto.assistant.AssistantDtos.ConversationResponse;
import com.saga.be.dto.assistant.AssistantDtos.Feedback;
import com.saga.be.dto.assistant.AssistantDtos.MessageResponse;
import com.saga.be.dto.assistant.AssistantDtos.StatusResponse;
import com.saga.be.entity.assistant.AssistantConversation;
import com.saga.be.entity.assistant.AssistantMessage;
import com.saga.be.entity.assistant.AssistantMessage.AnswerSource;
import com.saga.be.entity.assistant.AssistantMessage.Role;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AssistantConversationRepository;
import com.saga.be.repository.AssistantMessageRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.ai.AiAssistantClient;
import com.saga.be.service.assistant.AssistantFacts.Fact;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The project assistant: answers one user's questions about one project from facts the backend
 * selects for that user's permissions ({@link AssistantFactsBuilder}). It only reads -- it never
 * grades, closes a delay case or edits a task. Every AI citation is checked against the facts sent
 * for that question; when the AI is unavailable the backend answers with a summary of the data.
 *
 * <p>The AI call runs outside any database transaction (it may take seconds).
 */
@Service
@Profile("!test")
public class AssistantService {

	static final int CONVERSATION_LIST_LIMIT = 20;
	static final int TITLE_LENGTH = 80;
	static final int HISTORY_TEXT_LIMIT = 1000;
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");

	private final ProjectDataAuthorization authorization;
	private final AssistantConversationRepository conversations;
	private final AssistantMessageRepository messages;
	private final ProjectRepository projects;
	private final UserAccountRepository users;
	private final AssistantFactsBuilder factsBuilder;
	private final AiAssistantClient ai;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final ZoneId zone;
	private final boolean enabled;
	private final int dailyLimit;
	private final int historyMessages;
	private final ObjectMapper json = new ObjectMapper();

	@Autowired
	public AssistantService(
			ProjectDataAuthorization authorization,
			AssistantConversationRepository conversations,
			AssistantMessageRepository messages,
			ProjectRepository projects,
			UserAccountRepository users,
			AssistantFactsBuilder factsBuilder,
			AiAssistantClient ai,
			PlatformTransactionManager transactionManager,
			@Value("${saga.assistant.enabled:true}") boolean enabled,
			@Value("${saga.assistant.daily-limit:50}") int dailyLimit,
			@Value("${saga.assistant.history-messages:6}") int historyMessages,
			@Value("${saga.assistant.zone:Asia/Ho_Chi_Minh}") String zone) {
		this(authorization, conversations, messages, projects, users, factsBuilder, ai, new TransactionTemplate(transactionManager),
				Clock.systemDefaultZone(), ZoneId.of(zone), enabled, dailyLimit, historyMessages);
	}

	AssistantService(
			ProjectDataAuthorization authorization,
			AssistantConversationRepository conversations,
			AssistantMessageRepository messages,
			ProjectRepository projects,
			UserAccountRepository users,
			AssistantFactsBuilder factsBuilder,
			AiAssistantClient ai,
			TransactionTemplate tx,
			Clock clock,
			ZoneId zone,
			boolean enabled,
			int dailyLimit,
			int historyMessages) {
		this.authorization = authorization;
		this.conversations = conversations;
		this.messages = messages;
		this.projects = projects;
		this.users = users;
		this.factsBuilder = factsBuilder;
		this.ai = ai;
		this.tx = tx;
		this.clock = clock;
		this.zone = zone;
		this.enabled = enabled;
		this.dailyLimit = dailyLimit;
		this.historyMessages = historyMessages;
	}

	// ------------------------------------------------------------------ reads

	public StatusResponse status(UUID userId, UUID projectId) {
		return tx.execute(status -> {
			authorization.requireReader(userId, projectId);
			Project project = projects.findById(projectId).orElseThrow();
			long used = messages.countQuestionsSince(userId, now().minusHours(24));
			UUID courseId = project.getCourse() == null ? null : project.getCourse().getId();
			return new StatusResponse(enabled, ai.runtimeConfigured(), ai.keySource(courseId), dailyLimit, used,
					Math.max(0, dailyLimit - used));
		});
	}

	public List<ConversationResponse> conversations(UUID userId, UUID projectId) {
		return tx.execute(status -> {
			authorization.requireReader(userId, projectId);
			return conversations.findOwnedByProject(projectId, userId).stream()
					.limit(CONVERSATION_LIST_LIMIT)
					.map(this::toConversation)
					.toList();
		});
	}

	public List<MessageResponse> messages(UUID userId, UUID projectId, UUID conversationId) {
		return tx.execute(status -> {
			authorization.requireReader(userId, projectId);
			AssistantConversation conversation = requireConversation(userId, projectId, conversationId);
			return messages.findByConversation(conversation.getId()).stream().map(this::toMessage).toList();
		});
	}

	// ------------------------------------------------------------------ writes

	public ConversationResponse startConversation(UUID userId, UUID projectId) {
		requireEnabled();
		return tx.execute(status -> {
			authorization.requireReader(userId, projectId);
			AssistantConversation conversation = new AssistantConversation();
			conversation.setProject(projects.getReferenceById(projectId));
			conversation.setUserAccount(users.getReferenceById(userId));
			return toConversation(conversations.save(conversation));
		});
	}

	public AskResponse ask(UUID userId, UUID projectId, UUID conversationId, String rawQuestion) {
		requireEnabled();
		String question = rawQuestion == null ? "" : rawQuestion.strip();
		if (question.isEmpty() || question.length() > AssistantDtos.MAX_QUESTION_LENGTH) {
			throw new IntegrationException(IntegrationErrorCode.ASSISTANT_INPUT_INVALID, HttpStatus.BAD_REQUEST,
					"A question must have 1 to " + AssistantDtos.MAX_QUESTION_LENGTH + " characters.");
		}
		Prepared prepared = tx.execute(status -> prepare(userId, projectId, conversationId, question));

		Outcome outcome;
		try {
			AiAssistantClient.Answer answer = ai.ask("chat:" + prepared.questionId(), prepared.courseId(),
					evidence(prepared.facts()), context(prepared.facts(), question, prepared.history()));
			outcome = fromAi(answer, prepared.facts());
		} catch (AiAssistantClient.Unavailable ex) {
			outcome = fallback(prepared.facts(), ex.code());
		}

		Outcome finalOutcome = outcome;
		MessageResponse saved = tx.execute(status -> {
			AssistantConversation conversation = conversations.findById(conversationId).orElseThrow();
			AssistantMessage answer = new AssistantMessage();
			answer.setConversation(conversation);
			answer.setRole(Role.ASSISTANT);
			answer.setContent(finalOutcome.answer());
			answer.setAnswerSource(finalOutcome.source());
			answer.setInsufficientData(finalOutcome.insufficientData());
			answer.setOutOfScope(finalOutcome.outOfScope());
			answer.setVerified(finalOutcome.verified());
			answer.setRemovedCitationCount(finalOutcome.removedCitations());
			answer.setCitationsJson(write(finalOutcome.citations()));
			answer.setFollowUpsJson(write(finalOutcome.followUps()));
			answer.setFallbackReason(finalOutcome.fallbackReason());
			answer.setCredentialSource(finalOutcome.credentialSource());
			answer.setProviderKey(finalOutcome.providerKey());
			answer.setModelId(finalOutcome.modelId());
			answer.setLatencyMs(finalOutcome.latencyMs());
			AssistantMessage stored = messages.save(answer);
			conversation.setLastMessageAt(now());
			conversations.save(conversation);
			return toMessage(stored);
		});
		return new AskResponse(prepared.question(), saved);
	}

	public MessageResponse feedback(UUID userId, UUID projectId, UUID messageId, boolean helpful, String comment) {
		return tx.execute(status -> {
			authorization.requireReader(userId, projectId);
			AssistantMessage answer = messages.findOwnedAnswer(messageId, projectId, userId)
					.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.ASSISTANT_MESSAGE_NOT_FOUND,
							HttpStatus.NOT_FOUND, "Assistant answer was not found."));
			answer.setFeedbackHelpful(helpful);
			answer.setFeedbackComment(comment == null || comment.isBlank() ? null : comment.strip());
			answer.setFeedbackAt(now());
			return toMessage(messages.save(answer));
		});
	}

	// ------------------------------------------------------------------ ask steps

	record Prepared(UUID questionId, MessageResponse question, AssistantFacts facts, List<Map<String, Object>> history, UUID courseId) {}

	private Prepared prepare(UUID userId, UUID projectId, UUID conversationId, String question) {
		authorization.requireReader(userId, projectId);
		AssistantConversation conversation = requireConversation(userId, projectId, conversationId);
		if (messages.countQuestionsSince(userId, now().minusHours(24)) >= dailyLimit) {
			throw new IntegrationException(IntegrationErrorCode.ASSISTANT_RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS,
					"Daily assistant limit reached (" + dailyLimit + " questions in 24 hours).");
		}
		List<AssistantMessage> earlier = messages.findByConversation(conversation.getId());
		List<Map<String, Object>> history = new ArrayList<>();
		for (AssistantMessage message : earlier.subList(Math.max(0, earlier.size() - historyMessages), earlier.size())) {
			Map<String, Object> turn = new LinkedHashMap<>();
			turn.put("role", message.getRole() == Role.USER ? "user" : "assistant");
			turn.put("content", truncate(message.getContent(), HISTORY_TEXT_LIMIT));
			history.add(turn);
		}
		AssistantMessage stored = new AssistantMessage();
		stored.setConversation(conversation);
		stored.setRole(Role.USER);
		stored.setContent(question);
		stored = messages.save(stored);
		if (conversation.getTitle() == null) {
			conversation.setTitle(truncate(question, TITLE_LENGTH));
		}
		conversation.setLastMessageAt(now());
		conversations.save(conversation);
		AssistantFacts facts = factsBuilder.build(userId, projectId, question);
		Project project = conversation.getProject();
		UUID courseId = project.getCourse() == null ? null : project.getCourse().getId();
		return new Prepared(stored.getId(), toMessage(stored), facts, history, courseId);
	}

	static List<AiAssistantClient.Evidence> evidence(AssistantFacts facts) {
		return facts.items().stream()
				.map(fact -> new AiAssistantClient.Evidence(fact.id(), fact.kind(), fact.kind().toLowerCase(java.util.Locale.ROOT) + ":" + fact.id(), fact.payload()))
				.toList();
	}

	static Map<String, Object> context(AssistantFacts facts, String question, List<Map<String, Object>> history) {
		Map<String, Object> viewer = new LinkedHashMap<>();
		viewer.put("role", facts.viewer().role());
		viewer.put("teamRole", facts.viewer().teamRole());
		viewer.put("name", facts.viewer().name());
		viewer.put("memberId", facts.viewer().memberId() == null ? null : facts.viewer().memberId().toString());
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("question", question);
		context.put("viewer", viewer);
		context.put("today", facts.today().toString());
		context.put("history", history);
		return context;
	}

	/** What an answer becomes once stored. */
	record Outcome(
			String answer,
			AnswerSource source,
			boolean insufficientData,
			boolean outOfScope,
			boolean verified,
			int removedCitations,
			List<Citation> citations,
			List<String> followUps,
			String fallbackReason,
			String credentialSource,
			String providerKey,
			String modelId,
			Long latencyMs) {}

	/**
	 * Keeps only citations that point at a fact sent for this question. The answer counts as verified
	 * when none had to be dropped and it either cites something or says it cannot answer.
	 */
	static Outcome fromAi(AiAssistantClient.Answer answer, AssistantFacts facts) {
		List<Citation> citations = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		int removed = 0;
		for (AiAssistantClient.Citation citation : answer.citations()) {
			Fact fact = facts.find(citation.kind(), citation.id()).orElse(null);
			if (fact == null) {
				removed++;
			} else if (seen.add(fact.kind() + ":" + fact.id())) {
				citations.add(citation(fact));
			}
		}
		boolean verified = removed == 0 && (!citations.isEmpty() || answer.insufficientData() || answer.outOfScope());
		return new Outcome(answer.answer(), AnswerSource.AI, answer.insufficientData(), answer.outOfScope(), verified, removed,
				citations, answer.followUpQuestions().stream().limit(3).toList(), null, answer.credentialSource(),
				answer.providerKey(), answer.modelId(), answer.latencyMs());
	}

	/** A backend-built answer (no AI): the sprint, overdue tasks and anything the question named. */
	static Outcome fallback(AssistantFacts facts, String reason) {
		StringBuilder text = new StringBuilder("Trợ lý AI chưa trả lời được lúc này (")
				.append(reasonText(reason))
				.append("). Dữ liệu hệ thống liên quan:");
		List<Citation> citations = new ArrayList<>();
		AssistantFacts.Hints hints = facts.hints();
		facts.find(AssistantFacts.PROJECT, hints.projectId()).ifPresent(project -> {
			Map<String, Object> p = project.payload();
			text.append("\n- Dự án có ").append(p.get("taskCount")).append(" task, ")
					.append(p.get("overdueTaskCount")).append(" task trễ hạn, ")
					.append(p.get("dueSoonTaskCount")).append(" task đến hạn trong 3 ngày tới.");
			citations.add(citation(project));
		});
		for (UUID sprintId : hints.sprintIds()) {
			facts.find(AssistantFacts.SPRINT, sprintId).ifPresent(sprint -> {
				Map<String, Object> p = sprint.payload();
				text.append("\n- ").append(sprint.label()).append(": ")
						.append(count(p.get("taskStatusCounts"), "DONE")).append("/").append(p.get("taskCount"))
						.append(" task đã hoàn thành.");
				citations.add(citation(sprint));
			});
		}
		for (UUID taskId : hints.matchedTaskIds().stream().limit(3).toList()) {
			facts.find(AssistantFacts.TASK, taskId).ifPresent(task -> {
				text.append("\n- ").append(taskLine(task));
				citations.add(citation(task));
			});
		}
		for (UUID memberId : hints.matchedMemberIds().stream().limit(3).toList()) {
			facts.find(AssistantFacts.MEMBER, memberId).ifPresent(member -> {
				Map<String, Object> p = member.payload();
				long open = openCount(p.get("assignedTaskStatusCounts"));
				text.append("\n- ").append(member.label()).append(": ").append(open).append(" task chưa xong, ")
						.append(p.get("overdueTaskCount")).append(" task trễ hạn, ")
						.append(p.get("authoredCommitCount")).append(" commit.");
				citations.add(citation(member));
			});
		}
		List<Fact> overdue = hints.overdueTaskIds().stream()
				.filter(id -> !hints.matchedTaskIds().contains(id))
				.map(id -> facts.find(AssistantFacts.TASK, id).orElse(null))
				.filter(java.util.Objects::nonNull)
				.limit(5)
				.toList();
		if (!overdue.isEmpty()) {
			text.append("\n- Task trễ hạn:");
			for (Fact task : overdue) {
				text.append("\n  • ").append(taskLine(task));
				citations.add(citation(task));
			}
		}
		return new Outcome(text.toString(), AnswerSource.FALLBACK, false, false, true, 0, citations, List.of(), reason,
				null, null, null, null);
	}

	static String reasonText(String code) {
		if (code == null) {
			return "AI đang gặp lỗi";
		}
		return switch (code) {
			case "AI_RUNTIME_NOT_CONFIGURED", "AI_RUNTIME_DISABLED", "AI_RUNTIME_UNAVAILABLE" -> "hệ thống chưa bật AI";
			case "AI_CREDENTIAL_UNAVAILABLE" -> "lớp chưa có key AI và chưa được phép dùng key hệ thống";
			case "AI_PROVIDER_AUTH_FAILED" -> "key AI của lớp không hợp lệ";
			case "AI_PROVIDER_QUOTA_EXHAUSTED", "AI_PROVIDER_RATE_LIMITED" -> "key AI đã hết hạn mức hoặc đang bị giới hạn";
			case "AI_PROVIDER_TIMEOUT" -> "AI phản hồi quá lâu";
			case "AI_PROVIDER_RESULT_INVALID" -> "AI trả kết quả không hợp lệ";
			default -> "AI đang gặp lỗi";
		};
	}

	private static String taskLine(Fact task) {
		Map<String, Object> p = task.payload();
		Object assignee = p.get("assignee");
		Object due = p.get("dueDate");
		return task.label() + ": " + statusText(p.get("status"))
				+ ", người làm " + (assignee == null ? "chưa giao" : assignee)
				+ ", hạn " + (due == null ? "chưa đặt" : LocalDate.parse(due.toString()).format(DAY)) + ".";
	}

	static String statusText(Object status) {
		if (status == null) {
			return "không rõ trạng thái";
		}
		return switch (status.toString()) {
			case "TODO" -> "cần làm";
			case "IN_PROGRESS" -> "đang làm";
			case "IN_REVIEW" -> "đang review";
			case "BLOCKED" -> "bị chặn";
			case "DONE" -> "đã hoàn thành";
			default -> status.toString();
		};
	}

	private static long count(Object counts, String status) {
		if (counts instanceof Map<?, ?> map && map.get(status) instanceof Number number) {
			return number.longValue();
		}
		return 0;
	}

	private static long openCount(Object counts) {
		long open = 0;
		if (counts instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> entry : map.entrySet()) {
				if (!"DONE".equals(entry.getKey()) && entry.getValue() instanceof Number number) {
					open += number.longValue();
				}
			}
		}
		return open;
	}

	static Citation citation(Fact fact) {
		return new Citation(fact.kind(), fact.id(), fact.label(), fact.taskId(), fact.sha());
	}

	// ------------------------------------------------------------------ helpers

	private void requireEnabled() {
		if (!enabled) {
			throw new IntegrationException(IntegrationErrorCode.ASSISTANT_DISABLED, HttpStatus.SERVICE_UNAVAILABLE,
					"The project assistant is switched off.");
		}
	}

	private AssistantConversation requireConversation(UUID userId, UUID projectId, UUID conversationId) {
		return conversations.findOwned(conversationId, projectId, userId)
				.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.ASSISTANT_CONVERSATION_NOT_FOUND,
						HttpStatus.NOT_FOUND, "Assistant conversation was not found."));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	/** Stored server wall-clock time shown in the students' zone with its offset. */
	private OffsetDateTime display(LocalDateTime stored) {
		return stored == null ? null : stored.atZone(clock.getZone()).withZoneSameInstant(zone).toOffsetDateTime();
	}

	private ConversationResponse toConversation(AssistantConversation conversation) {
		return new ConversationResponse(conversation.getId(), conversation.getProject().getId(), conversation.getTitle(),
				display(conversation.getCreatedAt()), display(conversation.getLastMessageAt()));
	}

	private MessageResponse toMessage(AssistantMessage message) {
		boolean answer = message.getRole() == Role.ASSISTANT;
		Feedback feedback = message.getFeedbackHelpful() == null
				? null
				: new Feedback(message.getFeedbackHelpful(), message.getFeedbackComment(), display(message.getFeedbackAt()));
		return new MessageResponse(
				message.getId(),
				message.getConversation().getId(),
				message.getRole().name(),
				message.getContent(),
				display(message.getCreatedAt()),
				message.getAnswerSource() == null ? null : message.getAnswerSource().name(),
				message.getInsufficientData(),
				message.getOutOfScope(),
				message.getVerified(),
				message.getRemovedCitationCount(),
				answer ? read(message.getCitationsJson(), new TypeReference<List<Citation>>() {}) : null,
				answer ? read(message.getFollowUpsJson(), new TypeReference<List<String>>() {}) : null,
				message.getFallbackReason(),
				feedback);
	}

	private String write(Object value) {
		try {
			return json.writeValueAsString(value);
		} catch (Exception ex) {
			throw new IllegalStateException("assistant message could not be serialized", ex);
		}
	}

	private <T> List<T> read(String value, TypeReference<List<T>> type) {
		if (value == null || value.isBlank()) {
			return List.of();
		}
		try {
			return json.readValue(value, type);
		} catch (Exception ex) {
			return List.of();
		}
	}

	private static String truncate(String value, int limit) {
		if (value == null) {
			return null;
		}
		return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
	}
}

package com.saga.be.controller;

import com.saga.be.dto.assistant.AssistantDtos.AskRequest;
import com.saga.be.dto.assistant.AssistantDtos.AskResponse;
import com.saga.be.dto.assistant.AssistantDtos.ConversationResponse;
import com.saga.be.dto.assistant.AssistantDtos.FeedbackRequest;
import com.saga.be.dto.assistant.AssistantDtos.MessageResponse;
import com.saga.be.dto.assistant.AssistantDtos.StatusResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.assistant.AssistantService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/projects/{projectId}/assistant")
@Tag(
		name = "Project assistant",
		description = "Read-only questions about one project, answered from the asker's own project data with checked "
				+ "citations. Team members and the assigned lecturer. Never grades, edits tasks or decides delay cases.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectAssistantController {

	private final AssistantService assistant;

	public ProjectAssistantController(AssistantService assistant) {
		this.assistant = assistant;
	}

	@GetMapping("/status")
	@Operation(
			summary = "Whether the assistant answers with AI here, which key it uses and today's remaining questions",
			description = "keySource: COURSE (the class's key), PLATFORM (system key, allowed by the lecturer) or "
					+ "UNAVAILABLE (answers are backend-built summaries of the data).")
	public StatusResponse status(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return assistant.status(principal.getUserId(), projectId);
	}

	@PostMapping("/conversations")
	@ResponseStatus(HttpStatus.CREATED)
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Start a conversation (private to the caller)")
	public ConversationResponse start(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return assistant.startConversation(principal.getUserId(), projectId);
	}

	@GetMapping("/conversations")
	@Operation(summary = "The caller's conversations in this project, most recently used first (at most 20)")
	public List<ConversationResponse> conversations(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return assistant.conversations(principal.getUserId(), projectId);
	}

	@GetMapping("/conversations/{conversationId}/messages")
	@Operation(summary = "Messages of one of the caller's conversations, oldest first")
	public List<MessageResponse> messages(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID conversationId) {
		return assistant.messages(principal.getUserId(), projectId, conversationId);
	}

	@PostMapping("/conversations/{conversationId}/messages")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Ask a question (1-1000 characters); returns the stored question and the answer",
			description = "May take several seconds while the AI answers. Every citation is checked against the facts "
					+ "sent for this question (verified, removedCitationCount). When the AI is unavailable the answer is a "
					+ "backend-built summary (answerSource FALLBACK, fallbackReason). 429 ASSISTANT_RATE_LIMITED past the "
					+ "daily limit.")
	public AskResponse ask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID conversationId,
			@Valid @RequestBody AskRequest request) {
		return assistant.ask(principal.getUserId(), projectId, conversationId, request.question());
	}

	@PostMapping("/messages/{messageId}/feedback")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Rate one of the caller's assistant answers (helpful or not, optional comment)")
	public MessageResponse feedback(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID messageId,
			@Valid @RequestBody FeedbackRequest request) {
		return assistant.feedback(principal.getUserId(), projectId, messageId, request.helpful(), request.comment());
	}
}

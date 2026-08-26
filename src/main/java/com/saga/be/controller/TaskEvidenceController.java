package com.saga.be.controller;

import com.saga.be.auth.StepUpAuthenticationService;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.evidence.TaskEvidenceService;
import jakarta.servlet.http.HttpSession;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/tasks/{taskId}")
public class TaskEvidenceController {

	private final TaskEvidenceService evidence;

	public TaskEvidenceController(TaskEvidenceService evidence) {
		this.evidence = evidence;
	}

	@PostMapping("/work-sessions/start")
	public Map<String, Object> start(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID taskId) {
		TaskWorkSession session = evidence.start(principal.getUserId(), taskId);
		return Map.of("id", session.getId(), "status", session.getStatus().name());
	}

	@PostMapping("/work-sessions/{sessionId}/stop")
	public Map<String, Object> stop(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@PathVariable UUID sessionId) {
		TaskWorkSession session = evidence.stop(principal.getUserId(), taskId, sessionId);
		return Map.of("id", session.getId(), "status", session.getStatus().name());
	}

	@PostMapping("/contribution-confirmations")
	public ResponseEntity<Map<String, Object>> confirm(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@RequestBody Map<String, List<String>> body,
			HttpSession httpSession) {
		Instant stepUp = (Instant) httpSession.getAttribute(StepUpAuthenticationService.SESSION_ATTR);
		ContributionConfirmation row = evidence.confirm(
				principal.getUserId(),
				taskId,
				stepUp,
				body.getOrDefault("commitShas", List.of()),
				body.getOrDefault("pullRequests", List.of()));
		return ResponseEntity.status(201)
				.body(Map.of("id", row.getId(), "evidenceHash", row.getEvidenceHash(), "state", row.getEventState().name()));
	}
}

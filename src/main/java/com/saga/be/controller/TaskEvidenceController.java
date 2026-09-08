package com.saga.be.controller;

import com.saga.be.auth.StepUpAuthenticationService;
import com.saga.be.dto.task.CreateTaskWebLinkRequest;
import com.saga.be.dto.task.TaskFileResponse;
import com.saga.be.dto.task.TaskWebLinkResponse;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.evidence.TaskEvidenceService;
import com.saga.be.service.evidence.TaskFileService;
import com.saga.be.service.evidence.TaskWebLinkService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@Profile("!test")
@RequestMapping("/api/tasks/{taskId}")
@Tag(name = "Task evidence", description = "Work sessions, contribution confirmations, and student-submitted task links and files.")
@SecurityRequirement(name = "SAGA_SESSION")
public class TaskEvidenceController {

	private final TaskEvidenceService evidence;
	private final TaskWebLinkService webLinks;
	private final TaskFileService files;

	public TaskEvidenceController(TaskEvidenceService evidence, TaskWebLinkService webLinks, TaskFileService files) {
		this.evidence = evidence;
		this.webLinks = webLinks;
		this.files = files;
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

	@GetMapping("/web-links")
	@Operation(summary = "List URLs attached to a task")
	public List<TaskWebLinkResponse> listLinks(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID taskId) {
		return webLinks.list(principal.getUserId(), taskId);
	}

	@PostMapping("/web-links")
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Attach an http(s) URL to a task as DOCUMENT/RESEARCH evidence")
	public TaskWebLinkResponse addLink(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@Valid @RequestBody CreateTaskWebLinkRequest request) {
		return webLinks.add(principal.getUserId(), taskId, request);
	}

	@DeleteMapping("/web-links/{linkId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove a URL from a task")
	public void deleteLink(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@PathVariable UUID linkId) {
		webLinks.delete(principal.getUserId(), taskId, linkId);
	}

	@GetMapping("/files")
	@Operation(summary = "List files students uploaded to a task")
	public List<TaskFileResponse> listFiles(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID taskId) {
		return files.list(principal.getUserId(), taskId);
	}

	@PostMapping(value = "/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Upload a document or image as DOCUMENT/RESEARCH evidence")
	public TaskFileResponse addFile(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@RequestPart("file") MultipartFile file) {
		return files.add(principal.getUserId(), taskId, file);
	}

	@GetMapping("/files/{fileId}")
	@Operation(summary = "Download a student-uploaded task file")
	public ResponseEntity<byte[]> downloadFile(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@PathVariable UUID fileId) {
		TaskFileService.StoredFile stored = files.download(principal.getUserId(), taskId, fileId);
		return ResponseEntity.ok()
				.contentType(MediaType.parseMediaType(stored.mimeType()))
				.header(
						HttpHeaders.CONTENT_DISPOSITION,
						ContentDisposition.attachment()
								.filename(stored.filename(), StandardCharsets.UTF_8)
								.build()
								.toString())
				.body(stored.content());
	}

	@DeleteMapping("/files/{fileId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Remove a student-uploaded file from a task")
	public void deleteFile(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID taskId,
			@PathVariable UUID fileId) {
		files.delete(principal.getUserId(), taskId, fileId);
	}
}

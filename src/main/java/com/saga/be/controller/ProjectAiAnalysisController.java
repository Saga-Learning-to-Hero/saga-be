package com.saga.be.controller;

import com.saga.be.dto.ai.*;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.*;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.constraints.Max;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/projects/{projectId}/ai") @SecurityRequirement(name = "SAGA_SESSION")
public class ProjectAiAnalysisController {
	private final AiAnalysisSubmissionService submissions; private final AiAcademicSubmissionService academicSubmissions; private final AiAnalysisReadService reads;
	public ProjectAiAnalysisController(AiAnalysisSubmissionService submissions, AiAcademicSubmissionService academicSubmissions, AiAnalysisReadService reads) { this.submissions = submissions; this.academicSubmissions=academicSubmissions; this.reads = reads; }
	@PostMapping("/tasks/{taskId}/academic-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTaskAcademic(@AuthenticationPrincipal SagaUserPrincipal principal,@PathVariable UUID projectId,@PathVariable UUID taskId){ var run=academicSubmissions.submitTask(principal.getUserId(),projectId,taskId);return ResponseEntity.status(HttpStatus.ACCEPTED).body(reads.get(principal.getUserId(),projectId,run.getId())); }
	@PostMapping("/commits/{gitCommitId}/academic-analyses")
	public ResponseEntity<AiAnalysisResponse> submitCommitAcademic(@AuthenticationPrincipal SagaUserPrincipal principal,@PathVariable UUID projectId,@PathVariable UUID gitCommitId){ var run=academicSubmissions.submitCommit(principal.getUserId(),projectId,gitCommitId);return ResponseEntity.status(HttpStatus.ACCEPTED).body(reads.get(principal.getUserId(),projectId,run.getId())); }
	@PostMapping("/commits/{gitCommitId}/analyses")
	public ResponseEntity<AiAnalysisResponse> submit(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID gitCommitId) {
		AiAnalysisSubmissionService.Submission submission = submissions.submit(principal.getUserId(), projectId, gitCommitId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@GetMapping("/analyses/{analysisId}")
	public AiAnalysisResponse get(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID analysisId) { return reads.get(principal.getUserId(), projectId, analysisId); }
	@GetMapping("/commits/{gitCommitId}/analyses")
	public AiAnalysisPageResponse history(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID gitCommitId, @RequestParam(required = false) Integer page, @RequestParam(required = false) @Max(100) Integer size) { return reads.history(principal.getUserId(), projectId, gitCommitId, page, size); }
}

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
	private final AiAnalysisSubmissionService submissions; private final AiAcademicSubmissionService academicSubmissions; private final AiAnalysisReadService reads; private final AiTaskIntelligenceSubmissionService taskIntelligenceSubmissions; private final AiRiskAnalysisSubmissionService riskAnalysisSubmissions; private final AiProgressNarrativeSubmissionService progressSubmissions; private final AiProgressReportExportService exportService;
	public ProjectAiAnalysisController(AiAnalysisSubmissionService submissions, AiAcademicSubmissionService academicSubmissions, AiAnalysisReadService reads, AiTaskIntelligenceSubmissionService taskIntelligenceSubmissions, AiRiskAnalysisSubmissionService riskAnalysisSubmissions, AiProgressNarrativeSubmissionService progressSubmissions, AiProgressReportExportService exportService) { this.submissions = submissions; this.academicSubmissions=academicSubmissions; this.reads = reads; this.taskIntelligenceSubmissions = taskIntelligenceSubmissions; this.riskAnalysisSubmissions = riskAnalysisSubmissions; this.progressSubmissions = progressSubmissions; this.exportService = exportService; }
	@GetMapping("/analyses/{analysisId}/export.docx")
	public ResponseEntity<byte[]> exportProgressReport(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID analysisId) {
		var export = exportService.exportForProject(principal.getUserId(), projectId, analysisId);
		return ResponseEntity.ok()
				.contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
				.body(export.bytes());
	}
	@PostMapping("/students/{studentId}/progress-analyses")
	public ResponseEntity<AiAnalysisResponse> submitStudentProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID studentId) {
		var submission = progressSubmissions.submitStudent(principal.getUserId(), projectId, studentId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/team/progress-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTeamProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		var submission = progressSubmissions.submitTeam(principal.getUserId(), projectId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/students/{studentId}/risk-analyses")
	public ResponseEntity<AiAnalysisResponse> submitStudentRisk(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID studentId) {
		var submission = riskAnalysisSubmissions.submitStudent(principal.getUserId(), projectId, studentId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/team/risk-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTeamRisk(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		var submission = riskAnalysisSubmissions.submitTeam(principal.getUserId(), projectId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/tasks/{taskId}/intelligence-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTaskIntelligence(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID taskId) {
		var submission = taskIntelligenceSubmissions.submit(principal.getUserId(), projectId, taskId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/tasks/{taskId}/risk-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTaskRiskAnalysis(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID taskId) {
		var submission = riskAnalysisSubmissions.submitTask(principal.getUserId(), projectId, taskId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@PostMapping("/tasks/{taskId}/academic-analyses")
	public ResponseEntity<AiAnalysisResponse> submitTaskAcademic(@AuthenticationPrincipal SagaUserPrincipal principal,@PathVariable UUID projectId,@PathVariable UUID taskId){ var submission=academicSubmissions.submitTask(principal.getUserId(),projectId,taskId);return ResponseEntity.status(submission.created()?HttpStatus.ACCEPTED:HttpStatus.OK).body(reads.get(principal.getUserId(),projectId,submission.run().getId())); }
	@PostMapping("/commits/{gitCommitId}/academic-analyses")
	public ResponseEntity<AiAnalysisResponse> submitCommitAcademic(@AuthenticationPrincipal SagaUserPrincipal principal,@PathVariable UUID projectId,@PathVariable UUID gitCommitId){ var submission=academicSubmissions.submitCommit(principal.getUserId(),projectId,gitCommitId);return ResponseEntity.status(submission.created()?HttpStatus.ACCEPTED:HttpStatus.OK).body(reads.get(principal.getUserId(),projectId,submission.run().getId())); }
	@PostMapping("/commits/{gitCommitId}/analyses")
	public ResponseEntity<AiAnalysisResponse> submit(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID gitCommitId) {
		AiAnalysisSubmissionService.Submission submission = submissions.submit(principal.getUserId(), projectId, gitCommitId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(principal.getUserId(), projectId, submission.run().getId()));
	}
	@GetMapping("/analyses/{analysisId}")
	public AiAnalysisResponse get(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID analysisId) { return reads.get(principal.getUserId(), projectId, analysisId); }
	@GetMapping("/analyses/{analysisId}/adjudication")
	public AiAdjudicationResponse adjudication(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID analysisId) { return reads.adjudication(principal.getUserId(), projectId, analysisId); }
	@GetMapping("/commits/{gitCommitId}/analyses")
	public AiAnalysisPageResponse history(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID gitCommitId, @RequestParam(required = false) Integer page, @RequestParam(required = false) @Max(100) Integer size) { return reads.history(principal.getUserId(), projectId, gitCommitId, page, size); }

	// Drill-down: latest persisted result per artifact/analysis-type, or a clear NOT_ANALYZED
	// marker. DB-only reads; never submit an analysis as a side effect of a GET.
	@GetMapping("/tasks/{taskId}/intelligence-analyses/latest")
	public AiLatestAnalysisResponse latestTaskIntelligence(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID taskId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.TASK, taskId, com.saga.be.entity.enums.AiAnalysisType.TASK_INTELLIGENCE);
	}
	@GetMapping("/tasks/{taskId}/risk-analyses/latest")
	public AiLatestAnalysisResponse latestTaskRisk(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID taskId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.TASK, taskId, com.saga.be.entity.enums.AiAnalysisType.RISK_ANALYSIS);
	}
	@GetMapping("/students/{studentId}/risk-analyses/latest")
	public AiLatestAnalysisResponse latestStudentRisk(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID studentId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.STUDENT, studentId, com.saga.be.entity.enums.AiAnalysisType.RISK_ANALYSIS);
	}
	@GetMapping("/students/{studentId}/progress-analyses/latest")
	public AiLatestAnalysisResponse latestStudentProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID studentId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.STUDENT, studentId, com.saga.be.entity.enums.AiAnalysisType.PROGRESS_NARRATIVE);
	}
	@GetMapping("/team/risk-analyses/latest")
	public AiLatestAnalysisResponse latestTeamRisk(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.TEAM, projectId, com.saga.be.entity.enums.AiAnalysisType.RISK_ANALYSIS);
	}
	@GetMapping("/team/progress-analyses/latest")
	public AiLatestAnalysisResponse latestTeamProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return reads.latest(principal.getUserId(), projectId, com.saga.be.entity.enums.AiArtifactType.TEAM, projectId, com.saga.be.entity.enums.AiAnalysisType.PROGRESS_NARRATIVE);
	}
}

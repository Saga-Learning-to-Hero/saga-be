package com.saga.be.controller;

import com.saga.be.dto.ai.AiAnalysisResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.AiCourseAnalysisReadService;
import com.saga.be.service.ai.AiProgressNarrativeSubmissionService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** COURSE-scope progress narrative: authorized by course (assigned lecturer, or ADMIN per the
 * existing {@link com.saga.be.service.lecturer.LecturerCourseAuthorization} convention already
 * used by the lecturer course dashboard), not by project — a course has no single owning project. */
@RestController @Profile("!test") @RequestMapping("/api/lecturer/courses/{courseId}/ai") @SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseAiController {
	private final AiProgressNarrativeSubmissionService submissions;
	private final AiCourseAnalysisReadService reads;
	private final com.saga.be.service.ai.AiProgressReportExportService exportService;
	private final UserAccountRepository users;

	public LecturerCourseAiController(AiProgressNarrativeSubmissionService submissions, AiCourseAnalysisReadService reads, com.saga.be.service.ai.AiProgressReportExportService exportService, UserAccountRepository users) {
		this.submissions = submissions; this.reads = reads; this.exportService = exportService; this.users = users;
	}

	@GetMapping("/analyses/{analysisId}/export.docx")
	public ResponseEntity<byte[]> exportCourseProgressReport(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable UUID analysisId) {
		var export = exportService.exportForCourse(actor(principal), courseId, analysisId);
		return ResponseEntity.ok()
				.contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
				.header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.filename() + "\"")
				.body(export.bytes());
	}

	@PostMapping("/progress-analyses")
	public ResponseEntity<AiAnalysisResponse> submitCourseProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		UserAccount actor = actor(principal);
		var submission = submissions.submitCourse(actor, courseId);
		return ResponseEntity.status(submission.created() ? HttpStatus.ACCEPTED : HttpStatus.OK).body(reads.get(actor, courseId, submission.run().getId()));
	}

	@GetMapping("/analyses/{analysisId}")
	public AiAnalysisResponse get(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable UUID analysisId) {
		return reads.get(actor(principal), courseId, analysisId);
	}

	@GetMapping("/progress-analyses/latest")
	public com.saga.be.dto.ai.AiLatestAnalysisResponse latestCourseProgress(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return reads.latest(actor(principal), courseId, com.saga.be.entity.enums.AiArtifactType.COURSE, courseId, com.saga.be.entity.enums.AiAnalysisType.PROGRESS_NARRATIVE);
	}

	private UserAccount actor(SagaUserPrincipal principal) { return users.findById(principal.getUserId()).orElseThrow(); }
}

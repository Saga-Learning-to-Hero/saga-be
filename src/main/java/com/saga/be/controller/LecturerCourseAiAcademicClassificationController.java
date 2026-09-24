package com.saga.be.controller;

import com.saga.be.dto.ai.LecturerCourseAcademicClassificationPageResponse;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.LecturerCourseAcademicClassificationReadService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/lecturer/courses/{courseId}/ai")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseAiAcademicClassificationController {

	private final LecturerCourseAcademicClassificationReadService reads;
	private final UserAccountRepository users;

	public LecturerCourseAiAcademicClassificationController(
			LecturerCourseAcademicClassificationReadService reads, UserAccountRepository users) {
		this.reads = reads;
		this.users = users;
	}

	@GetMapping("/academic-classifications")
	@Workload(WorkloadClass.INTERACTIVE_NORMAL)
	@Operation(
			summary = "Course-wide Academic Classification history (TASK + COMMIT). Assigned lecturer only.",
			description = "Read-only view of persisted Academic Classification rows across every project/team of"
					+ " the course. Only the lecturer assigned to this course may read it; ADMIN is not granted, same"
					+ " as the project-level Academic Classification read. Reads MySQL only: it never triggers AI"
					+ " inference, calls saga-ai or any provider, or resolves course AI credentials.\n\n"
					+ "Rows are history, identical in meaning to GET /api/projects/{projectId}/ai/tasks/{taskId}/"
					+ "academic-classifications and .../commits/{id}/academic-classifications: an AI proposal"
					+ " (provenance=AI) is not authoritative until reviewed. status=CONFIRMED on an AI row, or any"
					+ " provenance=HUMAN row (a lecturer correction, linked to the corrected AI row by"
					+ " sourceClassificationId), is authoritative (see `authoritative`). PROPOSED, REJECTED and"
					+ " CORRECTED AI rows are not.\n\n"
					+ "Sorted by createdAt desc, id desc. page default 0, size default 50, size max 200; out of"
					+ " range returns 400. Filters are optional and combine with AND; a projectId/teamId outside this"
					+ " course returns an empty page. artifactType values other than TASK/COMMIT return an empty page.")
	public LecturerCourseAcademicClassificationPageResponse list(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Parameter(description = "TASK or COMMIT.") @RequestParam(required = false) AiArtifactType artifactType,
			@Parameter(description = "PROPOSED, CONFIRMED, REJECTED or CORRECTED.")
					@RequestParam(required = false) AiAcademicClassificationStatus status,
			@RequestParam(required = false) UUID projectId,
			@RequestParam(required = false) UUID teamId,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return reads.list(
				users.findById(principal.getUserId()).orElseThrow(),
				courseId,
				artifactType,
				status,
				projectId,
				teamId,
				page,
				size);
	}
}

package com.saga.be.controller;

import com.saga.be.dto.ai.CourseAiTeamAccessDtos;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.CourseAiTeamAccessService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/lecturer/courses/{courseId}/ai/team-access")
@Tag(
		name = "Course AI team access",
		description = "Teams run AI with the key their leader entered. The lecturer picks which teams may fall back to the "
				+ "course key. Assigned lecturer only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseAiTeamAccessController {

	private final CourseAiTeamAccessService access;
	private final UserAccountRepository users;

	public LecturerCourseAiTeamAccessController(CourseAiTeamAccessService access, UserAccountRepository users) {
		this.access = access;
		this.users = users;
	}

	@GetMapping
	@Operation(summary = "Every team of the course: its own key status, whether it may use the course key, and the key in effect")
	public CourseAiTeamAccessDtos.Response list(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return access.list(users.findById(principal.getUserId()).orElseThrow(), courseId);
	}

	@PutMapping("/{projectId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Allow or stop one team falling back to the course key")
	public CourseAiTeamAccessDtos.Response update(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID projectId,
			@RequestBody CourseAiTeamAccessDtos.UpdateRequest request) {
		return access.update(users.findById(principal.getUserId()).orElseThrow(), courseId, projectId, request);
	}
}

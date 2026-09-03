package com.saga.be.controller;

import com.saga.be.dto.project.CreateStudentProjectRequest;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.student.StudentProjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/api/student/courses/{courseId}/project")
@Tag(name = "Student project", description = "Team Leader creates the course team project; members may read it.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentCourseProjectController {

	private final StudentProjectService projects;

	public StudentCourseProjectController(StudentProjectService projects) {
		this.projects = projects;
	}

	@GetMapping
	@Operation(summary = "Get the current student's team project in a course")
	public StudentProjectResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return projects.getProject(principal.getUserId(), courseId);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create the team project. Team Leader only.")
	public StudentProjectResponse create(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@Valid @RequestBody CreateStudentProjectRequest request,
			HttpServletRequest http) {
		return projects.create(principal.getUserId(), courseId, request, audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}

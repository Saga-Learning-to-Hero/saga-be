package com.saga.be.controller;

import com.saga.be.dto.project.PatchProjectRequest;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.student.StudentProjectService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Workload(WorkloadClass.INTERACTIVE_WRITE)
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Student project", description = "Team Leader updates editable project metadata.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentProjectController {

	private final StudentProjectService projects;

	public StudentProjectController(StudentProjectService projects) {
		this.projects = projects;
	}

	@PatchMapping
	@Operation(summary = "Update project name and/or description. Active Team Leader only.")
	public StudentProjectResponse patch(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody(required = false) PatchProjectRequest request,
			HttpServletRequest http) {
		return projects.patch(principal.getUserId(), projectId, request, audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}

package com.saga.be.controller;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectProjectionReadService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project projections", description = "Projected Jira tasks and GitHub commits for a SAGA project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectProjectionController {

	private final ProjectProjectionReadService projections;

	public ProjectProjectionController(ProjectProjectionReadService projections) {
		this.projections = projections;
	}

	@GetMapping("/tasks")
	@Operation(summary = "List projected Jira tasks for the project. Team members and ADMIN.")
	public List<ProjectTaskResponse> tasks(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return projections.listTasks(principal.getUserId(), projectId);
	}

	@GetMapping("/commits")
	@Operation(summary = "List projected GitHub commits for the project. Team members and ADMIN.")
	public List<ProjectCommitResponse> commits(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return projections.listCommits(principal.getUserId(), projectId);
	}
}

package com.saga.be.controller;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectProjectionReadService;
import com.saga.be.service.sync.ProjectManualSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project projections", description = "Projected Jira tasks and GitHub commits for a SAGA project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectProjectionController {

	private final ProjectProjectionReadService projections;
	private final ProjectManualSyncService manualSync;

	public ProjectProjectionController(
			ProjectProjectionReadService projections, ProjectManualSyncService manualSync) {
		this.projections = projections;
		this.manualSync = manualSync;
	}

	@GetMapping("/tasks")
	@Operation(summary = "List projected Jira tasks. Owning team students and assigned lecturer.")
	public List<ProjectTaskResponse> tasks(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return projections.listTasks(principal.getUserId(), projectId);
	}

	@GetMapping("/tasks/{taskId}/commits")
	@Operation(summary = "List commits linked to a projected task via task_git_commit_link.")
	public List<ProjectCommitResponse> taskCommits(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		return projections.listTaskCommits(principal.getUserId(), projectId, taskId);
	}

	@GetMapping("/commits")
	@Operation(summary = "List projected GitHub commits. Owning team students and assigned lecturer.")
	public List<ProjectCommitResponse> commits(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return projections.listCommits(principal.getUserId(), projectId);
	}

	@PostMapping("/sync")
	@Operation(summary = "Team Leader recovery enqueue for Jira/GitHub backfill. Does not wait for provider HTTP.")
	public ResponseEntity<ProjectSyncEnqueueResponse> sync(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(manualSync.enqueue(principal.getUserId(), projectId));
	}

	@GetMapping("/sync-status")
	@Operation(summary = "Latest sync_job_log status per provider for the project.")
	public List<ProjectSyncStatusResponse> syncStatus(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return manualSync.latestStatus(principal.getUserId(), projectId);
	}
}

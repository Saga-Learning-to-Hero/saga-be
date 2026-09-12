package com.saga.be.controller;

import com.saga.be.dto.project.CreateProjectSprintRequest;
import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectSprintRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectMemberProgressResponse;
import com.saga.be.dto.project.ProjectProgressResponse;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectJiraSprintCommandService;
import com.saga.be.service.projection.ProjectJiraTaskCommandService;
import com.saga.be.service.projection.ProjectProgressService;
import com.saga.be.service.projection.ProjectProjectionReadService;
import com.saga.be.service.sync.ProjectManualSyncService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project projections", description = "Projected Jira tasks/sprints and GitHub commits for a SAGA project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectProjectionController {

	private final ProjectProjectionReadService projections;
	private final ProjectJiraTaskCommandService taskCommands;
	private final ProjectJiraSprintCommandService sprintCommands;
	private final ProjectManualSyncService manualSync;
	private final ProjectProgressService progress;

	public ProjectProjectionController(
			ProjectProjectionReadService projections,
			ProjectJiraTaskCommandService taskCommands,
			ProjectJiraSprintCommandService sprintCommands,
			ProjectManualSyncService manualSync,
			ProjectProgressService progress) {
		this.projections = projections;
		this.taskCommands = taskCommands;
		this.sprintCommands = sprintCommands;
		this.manualSync = manualSync;
		this.progress = progress;
	}

	@GetMapping("/tasks")
	@Operation(summary = "List projected Jira tasks. Owning team students and assigned lecturer.")
	public List<ProjectTaskResponse> tasks(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return projections.listTasks(principal.getUserId(), projectId);
	}

	@GetMapping("/tasks/options")
	@Operation(summary = "Provider-backed create/edit options (issue types, priorities, assignable users, estimation, sprints).")
	public ProjectTaskOptionsResponse taskOptions(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return taskCommands.options(principal.getUserId(), projectId);
	}

	@GetMapping("/tasks/{taskId}")
	@Operation(summary = "Get one projected Jira task.")
	public ProjectTaskResponse task(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		return projections.getTask(principal.getUserId(), projectId, taskId);
	}

	@PostMapping("/tasks")
	@Operation(summary = "Create a Jira issue then reconcile local Task projection. Team Leader only.")
	public ResponseEntity<ProjectTaskResponse> createTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody CreateProjectTaskRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(taskCommands.create(principal.getUserId(), projectId, body));
	}

	@PatchMapping("/tasks/{taskId}")
	@Operation(summary = "Update Jira issue fields then reconcile projection. Team Leader only. Status via /transition.")
	public ProjectTaskResponse patchTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@Valid @RequestBody PatchProjectTaskRequest body) {
		return taskCommands.patch(principal.getUserId(), projectId, taskId, body);
	}

	@PutMapping("/tasks/{taskId}/sprint")
	@Operation(summary = "Move task to a Jira sprint or backlog (sprintId=null). Team Leader only.")
	public ProjectTaskResponse putTaskSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@RequestBody PutProjectTaskSprintRequest body) {
		return taskCommands.moveSprint(principal.getUserId(), projectId, taskId, body);
	}

	@PostMapping("/tasks/{taskId}/transition")
	@Operation(summary = "Transition Jira issue status via available transitions. Team Leader only.")
	public ProjectTaskResponse transitionTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@Valid @RequestBody TransitionProjectTaskRequest body) {
		return taskCommands.transition(principal.getUserId(), projectId, taskId, body);
	}

	@GetMapping("/tasks/{taskId}/transitions")
	@Operation(summary = "List available Jira transitions for a task. Team Leader only.")
	public List<TransitionOption> taskTransitions(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		return taskCommands.listTransitions(principal.getUserId(), projectId, taskId);
	}

	@DeleteMapping("/tasks/{taskId}")
	@Operation(summary = "Delete Jira issue then soft-delete local projection. Team Leader only.")
	public ResponseEntity<Void> deleteTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		taskCommands.delete(principal.getUserId(), projectId, taskId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/tasks/{taskId}/commits")
	@Operation(summary = "List commits linked to a projected task via task_git_commit_link.")
	public List<ProjectCommitResponse> taskCommits(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		return projections.listTaskCommits(principal.getUserId(), projectId, taskId);
	}

	@GetMapping("/sprints")
	@Operation(summary = "List/reconcile Jira Software sprints for the project's board.")
	public List<ProjectSprintResponse> sprints(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return sprintCommands.syncAndList(principal.getUserId(), projectId);
	}

	@GetMapping("/sprints/{sprintId}")
	@Operation(summary = "Get one projected sprint.")
	public ProjectSprintResponse sprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId) {
		return projections.getSprint(principal.getUserId(), projectId, sprintId);
	}

	@PostMapping("/sprints")
	@Operation(summary = "Create a Jira Software sprint then reconcile projection. Team Leader only.")
	public ResponseEntity<ProjectSprintResponse> createSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody CreateProjectSprintRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(sprintCommands.create(principal.getUserId(), projectId, body));
	}

	@PatchMapping("/sprints/{sprintId}")
	@Operation(summary = "Update a Jira Software sprint then reconcile projection. Team Leader only.")
	public ProjectSprintResponse patchSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId,
			@Valid @RequestBody PatchProjectSprintRequest body) {
		return sprintCommands.patch(principal.getUserId(), projectId, sprintId, body);
	}

	@DeleteMapping("/sprints/{sprintId}")
	@Operation(summary = "Delete a Jira Software sprint then soft-delete projection. Team Leader only.")
	public ResponseEntity<Void> deleteSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId) {
		sprintCommands.delete(principal.getUserId(), projectId, sprintId);
		return ResponseEntity.noContent().build();
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

	@GetMapping("/progress")
	@Operation(
			summary = "Factual Task/Sprint/Commit/evidence progress dashboard for the project.",
			description =
					"""
					Read-only aggregation of canonical DB projections — no live Jira/GitHub call, no
					grading/contribution formula (see GET /api/teams/{teamId}/contribution-evaluation
					for that). Same reader authorization as every other endpoint on this controller.
					""")
	public ProjectProgressResponse projectProgress(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return progress.getProjectProgress(principal.getUserId(), projectId);
	}

	@GetMapping("/progress/members/{studentId}")
	@Operation(summary = "Factual per-member progress drill-down (assigned tasks, commit/evidence attribution).")
	public ProjectMemberProgressResponse memberProgress(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID studentId) {
		return progress.getMemberProgress(principal.getUserId(), projectId, studentId);
	}
}

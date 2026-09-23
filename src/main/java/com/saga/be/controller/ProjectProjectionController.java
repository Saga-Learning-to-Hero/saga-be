package com.saga.be.controller;

import com.saga.be.dto.project.CreateProjectSprintRequest;
import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectSprintRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectCommitDetailResponse;
import com.saga.be.dto.project.ProjectCommitPageResponse;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectGitBranchListResponse;
import com.saga.be.dto.project.ProjectMemberProgressResponse;
import com.saga.be.dto.project.ProjectProgressResponse;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectSyncStatusResponse;
import com.saga.be.dto.project.ProjectTaskCommitLinksResponse;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.SprintActivityResponse;
import com.saga.be.dto.project.TaskEvidenceResponse;
import com.saga.be.dto.project.TaskParentOptionsResponse;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectCommitDetailReadService;
import com.saga.be.service.projection.ProjectGitBranchReadService;
import com.saga.be.service.projection.ProjectJiraSprintCommandService;
import com.saga.be.service.projection.ProjectJiraTaskCommandService;
import com.saga.be.service.projection.ProjectProgressService;
import com.saga.be.service.projection.ProjectProjectionReadService;
import com.saga.be.service.projection.ProjectTaskCommitLinkReadService;
import com.saga.be.service.projection.ProjectTaskEvidenceReadService;
import com.saga.be.service.projection.SprintActivityAnalyticsService;
import com.saga.be.service.projection.TaskWorkSessionTimelineService;
import com.saga.be.service.sync.ProjectManualSyncService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/projects/{projectId}")
@Tag(name = "Project projections", description = "Projected Jira tasks/sprints and GitHub commits for a SAGA project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectProjectionController {

	private final ProjectProjectionReadService projections;
	private final ProjectJiraTaskCommandService taskCommands;
	private final ProjectJiraSprintCommandService sprintCommands;
	private final ProjectManualSyncService manualSync;
	private final ProjectProgressService progress;
	private final SprintActivityAnalyticsService sprintActivityAnalytics;
	private final ProjectGitBranchReadService branches;
	private final ProjectCommitDetailReadService commitDetails;
	private final ProjectTaskCommitLinkReadService linkReads;
	private final ProjectTaskEvidenceReadService evidence;
	private final TaskWorkSessionTimelineService workSessionTimeline;

	public ProjectProjectionController(
			ProjectProjectionReadService projections,
			ProjectJiraTaskCommandService taskCommands,
			ProjectJiraSprintCommandService sprintCommands,
			ProjectManualSyncService manualSync,
			ProjectProgressService progress,
			SprintActivityAnalyticsService sprintActivityAnalytics,
			ProjectGitBranchReadService branches,
			ProjectCommitDetailReadService commitDetails,
			ProjectTaskCommitLinkReadService linkReads,
			ProjectTaskEvidenceReadService evidence,
			TaskWorkSessionTimelineService workSessionTimeline) {
		this.projections = projections;
		this.taskCommands = taskCommands;
		this.sprintCommands = sprintCommands;
		this.manualSync = manualSync;
		this.progress = progress;
		this.sprintActivityAnalytics = sprintActivityAnalytics;
		this.branches = branches;
		this.commitDetails = commitDetails;
		this.linkReads = linkReads;
		this.evidence = evidence;
		this.workSessionTimeline = workSessionTimeline;
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
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID jiraIntegrationId) {
		return taskCommands.options(principal.getUserId(), projectId, jiraIntegrationId);
	}

	@GetMapping("/tasks/parent-options")
	@Operation(
			summary = "Paged native parent candidates for this project. Owning team students and assigned lecturer.",
			description =
					"""
					Local DB only. page default 0, size default 20, size max 50. page < 0 or size
					outside 1..50 is 400 REQUEST_INVALID (not clamped). Order is title ASC, id ASC.
					q is prefix search on title and externalKey.
					""")
	public TaskParentOptionsResponse parentOptions(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size,
			@RequestParam(required = false) UUID excludeTaskId) {
		return projections.listParentOptions(principal.getUserId(), projectId, q, page, size, excludeTaskId);
	}

	@GetMapping("/tasks/{taskId}")
	@Operation(summary = "Get one projected Jira task.")
	public ProjectTaskResponse task(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		return projections.getTask(principal.getUserId(), projectId, taskId);
	}

	@GetMapping("/tasks/{taskId}/evidence")
	@Operation(
			summary = "Read-only Task Evidence from local commit links, files, and web links.",
			description =
					"""
					Grouped preview when type is omitted (size is LIMIT per type, page must be 0).
					Typed paging when type=COMMIT|FILE|WEB_LINK. Local DB only; no GitHub/Jira HTTP.
					""")
	public TaskEvidenceResponse taskEvidence(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@RequestParam(required = false) String type,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return evidence.list(principal.getUserId(), projectId, taskId, type, page, size);
	}

	@GetMapping("/tasks/{taskId}/work-session-timeline")
	@Workload(WorkloadClass.INTERACTIVE_NORMAL)
	@Operation(
			summary = "Task work-session history + V23 linked coding commits (independent streams).",
			description =
					"""
					Team-visible SAGA work-session presence and V23 task_git_commit_link coding commits
					for visual context only. Does not assert that a commit was produced by or occurred
					during a work session. sessionPage/sessionSize default 0/20 max 100;
					commitPage/commitSize default 0/50 max 200 (same as GET .../commits).
					Local DB only; no GitHub/Jira/Neo4j. Distinct from GET /api/tasks/{taskId}/work-sessions
					(caller-owned timer restore).
					""")
	public TaskWorkSessionTimelineResponse taskWorkSessionTimeline(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@RequestParam(required = false) Integer sessionPage,
			@RequestParam(required = false) Integer sessionSize,
			@RequestParam(required = false) Integer commitPage,
			@RequestParam(required = false) Integer commitSize) {
		return workSessionTimeline.get(
				principal.getUserId(), projectId, taskId, sessionPage, sessionSize, commitPage, commitSize);
	}

	@PostMapping("/tasks")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Create a Jira issue then reconcile local Task projection. Team Leader only.")
	public ResponseEntity<ProjectTaskResponse> createTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody CreateProjectTaskRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(taskCommands.create(principal.getUserId(), projectId, body));
	}

	@PatchMapping("/tasks/{taskId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Update Jira issue fields then reconcile projection. Team Leader only. Status via /transition.")
	public ProjectTaskResponse patchTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@Valid @RequestBody PatchProjectTaskRequest body) {
		return taskCommands.patch(principal.getUserId(), projectId, taskId, body);
	}

	@PutMapping("/tasks/{taskId}/sprint")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Move task to a Jira sprint or backlog (sprintId=null). Team Leader only.")
	public ProjectTaskResponse putTaskSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@RequestBody PutProjectTaskSprintRequest body) {
		return taskCommands.moveSprint(principal.getUserId(), projectId, taskId, body);
	}

	@PostMapping("/tasks/{taskId}/transition")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
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
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Delete Jira issue then soft-delete local projection. Team Leader only.")
	public ResponseEntity<Void> deleteTask(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId) {
		taskCommands.delete(principal.getUserId(), projectId, taskId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/tasks/{taskId}/commits")
	@Workload(WorkloadClass.INTERACTIVE_NORMAL)
	@Operation(
			summary = "Paged commits linked to a projected task via task_git_commit_link.",
			description =
					"""
					Local DB only. Canonical task_git_commit_link rows only. Excludes known merges
					(parent_count > 1); UNKNOWN, root, and normal remain. Distinct from GET /commits,
					which is raw project history and includes known merges. page default 0, size
					default 50, size max 200. Breaking wrapper: items/page/size/total.
					""")
	public ProjectCommitPageResponse taskCommits(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID taskId,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return projections.listTaskCommits(principal.getUserId(), projectId, taskId, page, size);
	}

	@GetMapping("/sprints")
	@Operation(
			summary = "List/reconcile Jira Software sprints for the project's board.",
			description =
					"Optional jiraIntegrationId live-syncs that named ACTIVE source's board, then returns only that "
							+ "source's local sprints. When omitted and multiple ACTIVE sources exist, fails with JIRA_SOURCE_REQUIRED.")
	public List<ProjectSprintResponse> sprints(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID jiraIntegrationId) {
		return sprintCommands.syncAndList(principal.getUserId(), projectId, jiraIntegrationId);
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
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Create a Jira Software sprint then reconcile projection. Team Leader only.")
	public ResponseEntity<ProjectSprintResponse> createSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody CreateProjectSprintRequest body) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(sprintCommands.create(principal.getUserId(), projectId, body));
	}

	@PatchMapping("/sprints/{sprintId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Update a Jira Software sprint then reconcile projection. Team Leader only.")
	public ProjectSprintResponse patchSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId,
			@Valid @RequestBody PatchProjectSprintRequest body) {
		return sprintCommands.patch(principal.getUserId(), projectId, sprintId, body);
	}

	@DeleteMapping("/sprints/{sprintId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Delete a Jira Software sprint then soft-delete projection. Team Leader only.")
	public ResponseEntity<Void> deleteSprint(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID sprintId) {
		sprintCommands.delete(principal.getUserId(), projectId, sprintId);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/commits")
	@Workload(WorkloadClass.INTERACTIVE_NORMAL)
	@Operation(
			summary = "Paged raw projected GitHub commits. Owning team students and assigned lecturer.",
			description =
					"""
					Local DB only. Includes normal, root, known-merge, and UNKNOWN parentCount rows.
					page default 0, size default 50, size max 200. Breaking wrapper: items/page/size/total.
					""")
	public ProjectCommitPageResponse commits(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return projections.listCommits(principal.getUserId(), projectId, page, size);
	}

	@GetMapping("/commits/{gitCommitId}")
	@Operation(
			summary = "Live GitHub commit detail for a projected commit.",
			description =
					"""
					Lookup is SAGA gitCommitId (graph node id commit:{gitCommitId}). Files are fetched
					live from GitHub Get-a-commit, paginated at 100 and capped at 300.
					filesTruncated=true means the UI is showing only the first bounded portion of a
					very large commit. Missing GitHub patch text is returned as null.
					""")
	public ProjectCommitDetailResponse commitDetail(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID gitCommitId) {
		return commitDetails.getDetail(principal.getUserId(), projectId, gitCommitId);
	}

	@GetMapping("/task-commit-links")
	@Operation(
			summary = "Canonical paged Task ↔ Commit links for the Pipeline / Audit Matrix.",
			description =
					"""
					Queries task_git_commit_link directly (auto, manual, merge, no Jira key).
					repoId is the SAGA GitRepo UUID, not GitHub's numeric repository id.
					branchName requires repoId and means REACHABLE_AT_SYNC membership from the latest
					successful FULL snapshot — not GitCommit.headRef and not commit-message parsing.
					""")
	public ProjectTaskCommitLinksResponse taskCommitLinks(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID repoId,
			@RequestParam(required = false) String branchName,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		return linkReads.list(principal.getUserId(), projectId, repoId, branchName, page, size);
	}

	@GetMapping("/repos/{repoId}/branches")
	@Operation(
			summary = "Live GitHub branch inventory for one ACTIVE project repository.",
			description =
					"""
					Authoritative source for the repository branch dropdown and current branch count --
					fetched directly from GitHub (fully paginated), not derived from GitCommit.headRef.
					headRef only records the single branch a commit was observed under during ingestion,
					not complete branch membership, so commit-by-branch filtering via
					commit.headRef == selectedBranch remains best-effort/observed-branch filtering only.
					""")
	public ProjectGitBranchListResponse repositoryBranches(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID repoId) {
		return branches.listBranches(principal.getUserId(), projectId, repoId);
	}

	@PostMapping("/sync")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Team Leader recovery enqueue for Jira/GitHub backfill. Does not wait for provider HTTP.")
	public ResponseEntity<ProjectSyncEnqueueResponse> sync(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(manualSync.enqueue(principal.getUserId(), projectId));
	}

	@GetMapping("/sync-status")
	@Workload(WorkloadClass.INTERACTIVE_LIGHT)
	@Operation(summary = "Latest sync_job_log status per provider for the project.")
	public List<ProjectSyncStatusResponse> syncStatus(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return manualSync.latestStatus(principal.getUserId(), projectId);
	}

	@GetMapping("/progress")
	@Workload(WorkloadClass.HEAVY_READ)
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
	@Workload(WorkloadClass.HEAVY_READ)
	@Operation(summary = "Factual per-member progress drill-down (assigned tasks, commit/evidence attribution).")
	public ProjectMemberProgressResponse memberProgress(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID studentId) {
		return progress.getMemberProgress(principal.getUserId(), projectId, studentId);
	}

	@GetMapping("/analytics/sprint-activity")
	@Workload(WorkloadClass.HEAVY_READ)
	@Operation(
			summary = "Per-sprint task and commit activity (personal for students; project-wide for lecturers).",
			description =
					"""
					Read-only aggregation of canonical Task/Sprint/Commit projections — no live
					Jira/GitHub call, no grading formula. STUDENT always receives their own assigned
					tasks and authored commits (studentId query is ignored). Assigned LECTURER may
					omit studentId for the whole project, or pass an ACTIVE member's studentId.
					Any ACTIVE team member or assigned lecturer may call this (unlike GET /progress).
					""")
	public SprintActivityResponse sprintActivity(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) UUID studentId) {
		return sprintActivityAnalytics.getSprintActivity(principal.getUserId(), projectId, studentId);
	}
}

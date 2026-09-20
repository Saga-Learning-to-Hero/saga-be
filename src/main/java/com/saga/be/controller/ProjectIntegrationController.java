package com.saga.be.controller;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.ApiErrorResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.JiraSourceSummary;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraBoardOption;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraProjectOption;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
import com.saga.be.dto.integration.SelectJiraIntegrationRequest;
import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.oauth.IntegrationFrontendRedirects;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.projection.ProjectJiraTaskCommandService;
import com.saga.be.service.sync.ProjectManualSyncService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
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
@RequestMapping("/api/projects/{projectId}/integrations")
@Tag(name = "Project integrations", description = "Team Leader GitHub App and Jira Cloud connections for a project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectIntegrationController {

	private final ProjectIntegrationService integrations;
	private final ProjectJiraTaskCommandService taskCommands;
	private final ProjectManualSyncService manualSync;
	private final IntegrationProperties properties;

	public ProjectIntegrationController(
			ProjectIntegrationService integrations,
			ProjectJiraTaskCommandService taskCommands,
			ProjectManualSyncService manualSync,
			IntegrationProperties properties) {
		this.integrations = integrations;
		this.taskCommands = taskCommands;
		this.manualSync = manualSync;
		this.properties = properties;
	}

	@GetMapping
	public ProjectIntegrationsResponse summary(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.summary(principal.getUserId(), projectId);
	}

	@PostMapping("/github/connect")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Start GitHub App connect/reconnect for the project. Team Leader only.",
			description =
					"""
					Default: resolve historical provenance / discover existing installations via user OAuth.
					Pass installationId to bind a chosen candidate (re-verified after OAuth).
					Pass mode=install_new to open GitHub App /installations/new (another account/org) without
					guessing among candidates — does not delete historical git_repo rows.
					""")
	public OAuthStartResponse githubConnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String returnPath,
			@RequestParam(required = false) Long installationId,
			@RequestParam(required = false) String mode) {
		return integrations.startGithub(principal.getUserId(), projectId, returnPath, installationId, mode);
	}

	@GetMapping("/github/reconnect/candidates")
	@Operation(
			summary = "List GitHub installation candidates for reconnect selection. Team Leader only.",
			description =
					"Returns OAuth-verified eligible candidates when present; otherwise historical provenance hints. "
							+ "Display fields only — no tokens.")
	public List<com.saga.be.dto.integration.GithubReconnectCandidateResponse> githubReconnectCandidates(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.listGithubReconnectCandidates(principal.getUserId(), projectId);
	}

	@GetMapping("/github/setup/callback")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	public ResponseEntity<Void> githubSetup(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String state,
			@RequestParam("installation_id") Long installationId,
			@RequestParam(value = "code", required = false) String code) {
		try {
			return IntegrationFrontendRedirects.seeOther(
					integrations.completeGithubInstallation(principal.getUserId(), state, installationId, code));
		} catch (IntegrationException ex) {
			return IntegrationFrontendRedirects.failure(properties.getFailureUrl(), ex);
		}
	}

	@GetMapping("/github/repositories")
	public List<GitHubOAuthClient.RepoSummary> githubRepos(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.listGithubRepos(principal.getUserId(), projectId);
	}

	@PutMapping("/github/repositories")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Select GitHub repositories for the project. Team Leader only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			required = true,
			content =
					@Content(
							array = @ArraySchema(schema = @Schema(implementation = SelectGitHubRepositoryRequest.class)),
							examples =
									@ExampleObject(
											name = "frontendAndBackend",
											value =
													"""
													[
													  {"repositoryId":1338790015,"role":"FRONTEND"},
													  {"repositoryId":1339720224,"role":"BACKEND"}
													]
													""")))
	@ApiResponse(responseCode = "204", description = "Repositories selected")
	@ApiResponse(
			responseCode = "400",
			description = "Missing repositoryId or invalid role",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	public ResponseEntity<Void> selectGithubRepos(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody List<@Valid SelectGitHubRepositoryRequest> body) {
		integrations.selectGithubRepos(principal.getUserId(), projectId, body);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/github")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	public ResponseEntity<Void> deleteGithub(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		integrations.disconnectGithub(principal.getUserId(), projectId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/jira/connect")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Start Jira OAuth to ADD a Jira source for the project. Team Leader only.",
			description =
					"Adds a new source (first or additional). To reconnect an existing named source use "
							+ "POST /jira-sources/{integrationId}/reconnect.")
	public OAuthStartResponse jiraConnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String returnPath) {
		return integrations.startJira(principal.getUserId(), projectId, returnPath);
	}

	@GetMapping("/jira-sources")
	@Operation(summary = "List Jira sources for the project (no secrets). Member or admin.")
	public List<JiraSourceSummary> listJiraSources(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.listJiraSources(principal.getUserId(), projectId);
	}

	@PostMapping("/jira-sources/connect")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Start Jira OAuth to ADD a new source. Team Leader only.",
			description = "Preferred ADD path when the project may already have Jira sources. Same OAuth as POST /jira/connect.")
	public OAuthStartResponse jiraSourceConnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String returnPath) {
		return integrations.startJiraSourceConnect(principal.getUserId(), projectId, returnPath);
	}

	@PostMapping("/jira-sources/{integrationId}/reconnect")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Start Jira OAuth to reconnect a named existing source. Team Leader only.",
			description = "Validates the integration belongs to the project before OAuth.")
	public OAuthStartResponse jiraSourceReconnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID integrationId,
			@RequestParam(required = false) String returnPath) {
		return integrations.startJiraSourceReconnect(principal.getUserId(), projectId, integrationId, returnPath);
	}

	@PutMapping("/jira-sources/{integrationId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Save Jira site/project/board for a named source only. Team Leader only.")
	public ResponseEntity<Void> saveJiraSource(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID integrationId,
			@Valid @RequestBody SelectJiraIntegrationRequest selection) {
		integrations.saveJiraSourceSelection(principal.getUserId(), projectId, integrationId, selection);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/jira-sources/{integrationId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Soft-disconnect a named Jira source. Team Leader only.")
	public ResponseEntity<Void> deleteJiraSource(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID integrationId) {
		integrations.disconnectJiraSource(principal.getUserId(), projectId, integrationId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/jira-sources/{integrationId}/sync")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Team Leader recovery enqueue for one Jira source. Does not wait for provider HTTP.",
			description = "Same auth as POST /api/projects/{projectId}/sync. github field is SKIPPED_NOT_CONFIGURED.")
	public ResponseEntity<ProjectSyncEnqueueResponse> syncJiraSource(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID integrationId) {
		return ResponseEntity.status(HttpStatus.ACCEPTED)
				.body(manualSync.enqueueJiraSource(principal.getUserId(), projectId, integrationId));
	}

	@GetMapping("/jira/sites")
	public List<JiraOAuthClient.AccessibleResource> jiraSites(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.listJiraSites(principal.getUserId(), projectId);
	}

	@GetMapping("/jira/projects")
	public List<JiraProjectOption> jiraProjects(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam String cloudId) {
		return integrations.listJiraProjects(principal.getUserId(), projectId, cloudId);
	}

	@GetMapping("/jira/boards")
	public List<JiraBoardOption> jiraBoards(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam String cloudId,
			@RequestParam String jiraProjectId) {
		return integrations.listJiraBoards(principal.getUserId(), projectId, cloudId, jiraProjectId);
	}

	@PutMapping("/jira")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Save the selected Jira site, project, and optional board. Team Leader only.")
	@io.swagger.v3.oas.annotations.parameters.RequestBody(
			required = true,
			content =
					@Content(
							schema = @Schema(implementation = SelectJiraIntegrationRequest.class),
							examples =
									@ExampleObject(
											name = "sagaCloudProject",
											value =
													"""
													{
													  "cloudId":"aeb21465-f2da-4923-b356-f6f1cfa4fd13",
													  "jiraProjectId":"10067",
													  "boardId":"68"
													}
													""")))
	@ApiResponse(responseCode = "204", description = "Jira integration saved")
	@ApiResponse(
			responseCode = "400",
			description = "Missing cloudId or jiraProjectId",
			content = @Content(schema = @Schema(implementation = ApiErrorResponse.class)))
	public ResponseEntity<Void> saveJira(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@Valid @RequestBody SelectJiraIntegrationRequest selection) {
		integrations.saveJiraSelection(principal.getUserId(), projectId, selection);
		return ResponseEntity.noContent().build();
	}

	@GetMapping("/jira-sources/{integrationId}/task-options")
	@Operation(summary = "Provider-backed task options for a specific Jira source on this project.")
	public ProjectTaskOptionsResponse jiraSourceTaskOptions(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID integrationId) {
		return taskCommands.optionsForIntegration(principal.getUserId(), projectId, integrationId);
	}

	@DeleteMapping("/jira")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Soft-disconnect the singular Jira source. Team Leader only.",
			description =
					"When multiple sources exist, fails with JIRA_SOURCE_REQUIRED — use DELETE /jira-sources/{integrationId}.")
	public ResponseEntity<Void> deleteJira(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		integrations.disconnectJira(principal.getUserId(), projectId);
		return ResponseEntity.noContent().build();
	}
}

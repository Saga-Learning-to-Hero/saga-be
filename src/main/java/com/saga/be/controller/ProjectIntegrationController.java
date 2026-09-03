package com.saga.be.controller;

import com.saga.be.dto.ApiErrorResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraBoardOption;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraProjectOption;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.ProjectIntegrationService;
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
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
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
@RequestMapping("/api/projects/{projectId}/integrations")
@Tag(name = "Project integrations", description = "Team Leader GitHub App and Jira Cloud connections for a project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectIntegrationController {

	private final ProjectIntegrationService integrations;

	public ProjectIntegrationController(ProjectIntegrationService integrations) {
		this.integrations = integrations;
	}

	@GetMapping
	public ProjectIntegrationsResponse summary(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.summary(principal.getUserId(), projectId);
	}

	@PostMapping("/github/connect")
	public OAuthStartResponse githubConnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String returnPath) {
		return integrations.startGithub(principal.getUserId(), projectId, returnPath);
	}

	@GetMapping("/github/setup/callback")
	public ResponseEntity<Void> githubSetup(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String state,
			@RequestParam("installation_id") Long installationId,
			@RequestParam(value = "code", required = false) String code) {
		String target = integrations.completeGithubInstallation(principal.getUserId(), state, installationId, code);
		return ResponseEntity.status(302).header("Location", target).build();
	}

	@GetMapping("/github/repositories")
	public List<GitHubOAuthClient.RepoSummary> githubRepos(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return integrations.listGithubRepos(principal.getUserId(), projectId);
	}

	@PutMapping("/github/repositories")
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
	public ResponseEntity<Void> deleteGithub(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		integrations.disconnectGithub(principal.getUserId(), projectId);
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/jira/connect")
	public OAuthStartResponse jiraConnect(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) String returnPath) {
		return integrations.startJira(principal.getUserId(), projectId, returnPath);
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
	public ResponseEntity<Void> saveJira(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestBody Map<String, String> selection) {
		integrations.saveJiraSelection(principal.getUserId(), projectId, selection);
		return ResponseEntity.noContent().build();
	}

	@DeleteMapping("/jira")
	public ResponseEntity<Void> deleteJira(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		integrations.disconnectJira(principal.getUserId(), projectId);
		return ResponseEntity.noContent().build();
	}
}

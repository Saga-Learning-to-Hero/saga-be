package com.saga.be.controller;

import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraBoardOption;
import com.saga.be.dto.integration.ProjectIntegrationsResponse.JiraProjectOption;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.ProjectIntegrationService;
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
	public ResponseEntity<Void> selectGithubRepos(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestBody List<Map<String, Object>> body) {
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

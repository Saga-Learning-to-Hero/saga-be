package com.saga.be.controller;

import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.ProjectIntegrationService;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/integrations")
public class IntegrationCallbackController {

	private final ProjectIntegrationService projects;

	public IntegrationCallbackController(ProjectIntegrationService projects) {
		this.projects = projects;
	}

	@GetMapping("/github/setup/callback")
	public ResponseEntity<Void> githubSetup(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String state,
			@RequestParam("installation_id") Long installationId,
			@RequestParam(value = "code", required = false) String code) {
		String target = projects.completeGithubInstallation(principal.getUserId(), state, installationId, code);
		return ResponseEntity.status(302).header("Location", target).build();
	}

	@GetMapping("/jira/team/callback")
	public ResponseEntity<Void> jiraTeam(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String code,
			@RequestParam String state) {
		String target = projects.completeJiraTeamCallback(principal.getUserId(), code, state);
		return ResponseEntity.status(302).header("Location", target).build();
	}
}

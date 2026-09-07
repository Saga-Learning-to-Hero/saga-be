package com.saga.be.controller;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.oauth.IntegrationFrontendRedirects;
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
	private final IntegrationProperties properties;

	public IntegrationCallbackController(ProjectIntegrationService projects, IntegrationProperties properties) {
		this.projects = projects;
		this.properties = properties;
	}

	@GetMapping("/github/setup/callback")
	public ResponseEntity<Void> githubSetup(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String state,
			@RequestParam("installation_id") Long installationId,
			@RequestParam(value = "code", required = false) String code) {
		try {
			return IntegrationFrontendRedirects.seeOther(
					projects.completeGithubInstallation(principal.getUserId(), state, installationId, code));
		} catch (IntegrationException ex) {
			return IntegrationFrontendRedirects.failure(properties.getFailureUrl(), ex);
		}
	}

	@GetMapping("/jira/team/callback")
	public ResponseEntity<Void> jiraTeam(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String code,
			@RequestParam String state) {
		try {
			return IntegrationFrontendRedirects.seeOther(
					projects.completeJiraTeamCallback(principal.getUserId(), code, state));
		} catch (IntegrationException ex) {
			return IntegrationFrontendRedirects.failure(properties.getFailureUrl(), ex);
		}
	}
}

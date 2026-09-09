package com.saga.be.controller;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.MyIntegrationsResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.oauth.IntegrationFrontendRedirects;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.PersonalIntegrationService;
import com.saga.be.service.identity.ProjectIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/integrations")
@Tag(name = "Personal integrations", description = "User-level GitHub/Jira identity links. Not team resource connections.")
public class PersonalIntegrationController {

	private final PersonalIntegrationService integrations;
	private final ProjectIntegrationService projects;
	private final OAuthStateService oauthStates;
	private final UserAccountRepository users;
	private final IntegrationProperties properties;

	public PersonalIntegrationController(
			PersonalIntegrationService integrations,
			ProjectIntegrationService projects,
			OAuthStateService oauthStates,
			UserAccountRepository users,
			IntegrationProperties properties) {
		this.integrations = integrations;
		this.projects = projects;
		this.oauthStates = oauthStates;
		this.users = users;
		this.properties = properties;
	}

	@GetMapping("/me")
	@Operation(summary = "List linked provider identities for the current user")
	public MyIntegrationsResponse me(@AuthenticationPrincipal SagaUserPrincipal principal) {
		return integrations.me(principal.getUserId());
	}

	@PostMapping("/github/link")
	public OAuthStartResponse startGithub(
			@AuthenticationPrincipal SagaUserPrincipal principal, @RequestParam(required = false) String returnPath) {
		return integrations.startGithub(principal.getUserId(), returnPath);
	}

	@GetMapping("/github/oauth/callback")
	public ResponseEntity<Void> githubCallback(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam String code,
			@RequestParam String state) {
		UserAccount actor = users.findById(principal.getUserId()).orElseThrow();
		try {
			OAuthState oauthState = oauthStates.consumeForUser(state, principal.getUserId());
			String target =
					switch (oauthState.flowType()) {
						case GITHUB_USER_LINK -> integrations.completeGithub(principal.getUserId(), code, oauthState, actor);
						case GITHUB_TEAM_RECONNECT -> projects.completeGithubReconnect(principal.getUserId(), code, oauthState);
						default -> throw new IntegrationException(
								IntegrationErrorCode.OAUTH_STATE_INVALID,
								HttpStatus.BAD_REQUEST,
								"OAuth state is invalid.");
					};
			return IntegrationFrontendRedirects.seeOther(target);
		} catch (IntegrationException ex) {
			return IntegrationFrontendRedirects.failure(properties.getFailureUrl(), ex);
		}
	}

	@PatchMapping("/github/{identityId}/primary")
	public Map<String, Boolean> githubPrimary(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID identityId) {
		integrations.setPrimary(principal.getUserId(), identityId, users.findById(principal.getUserId()).orElseThrow());
		return Map.of("primary", true);
	}

	@DeleteMapping("/github/{identityId}")
	public ResponseEntity<Void> unlinkGithub(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID identityId) {
		integrations.unlink(principal.getUserId(), identityId, users.findById(principal.getUserId()).orElseThrow());
		return ResponseEntity.noContent().build();
	}

	@PostMapping("/jira/link")
	public OAuthStartResponse startJira(
			@AuthenticationPrincipal SagaUserPrincipal principal, @RequestParam(required = false) String returnPath) {
		return integrations.startJira(principal.getUserId(), returnPath);
	}

	@GetMapping("/jira/oauth/callback")
	public ResponseEntity<Void> jiraCallback(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam(required = false) String code,
			@RequestParam(required = false) String state,
			@RequestParam(required = false) String error,
			@RequestParam(value = "error_description", required = false) String errorDescription) {
		UserAccount actor = users.findById(principal.getUserId()).orElseThrow();
		try {
			return IntegrationFrontendRedirects.seeOther(
					integrations.completeJira(principal.getUserId(), code, state, error, actor));
		} catch (IntegrationException ex) {
			return IntegrationFrontendRedirects.failure(properties.getFailureUrl(), ex);
		}
	}

	@PatchMapping("/jira/{identityId}/primary")
	public Map<String, Boolean> jiraPrimary(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID identityId) {
		integrations.setPrimary(principal.getUserId(), identityId, users.findById(principal.getUserId()).orElseThrow());
		return Map.of("primary", true);
	}

	@DeleteMapping("/jira/{identityId}")
	public ResponseEntity<Void> unlinkJira(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID identityId) {
		integrations.unlink(principal.getUserId(), identityId, users.findById(principal.getUserId()).orElseThrow());
		return ResponseEntity.noContent().build();
	}
}

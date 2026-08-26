package com.saga.be.controller;

import com.saga.be.dto.integration.MyIntegrationsResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.identity.PersonalIntegrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
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
	private final UserAccountRepository users;

	public PersonalIntegrationController(PersonalIntegrationService integrations, UserAccountRepository users) {
		this.integrations = integrations;
		this.users = users;
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
		String target = integrations.completeGithub(principal.getUserId(), code, state, actor);
		return ResponseEntity.status(302).header("Location", target).build();
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
			@RequestParam String code,
			@RequestParam String state) {
		UserAccount actor = users.findById(principal.getUserId()).orElseThrow();
		String target = integrations.completeJira(principal.getUserId(), code, state, actor);
		return ResponseEntity.status(302).header("Location", target).build();
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

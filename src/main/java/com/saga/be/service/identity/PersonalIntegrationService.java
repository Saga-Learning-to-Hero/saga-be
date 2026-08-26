package com.saga.be.service.identity;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.LinkedIdentityResponse;
import com.saga.be.dto.integration.MyIntegrationsResponse;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.WarningCategory;
import com.saga.be.entity.enums.WarningSeverity;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.Pkce;
import com.saga.be.messaging.OutboxPublisher;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.IdentityMappingHistoryRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.attribution.AttributionWarningService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

@Service
@Profile("!test")
public class PersonalIntegrationService {

	private final UserAccountRepository users;
	private final IdentityMapRepository identities;
	private final IdentityMappingHistoryRepository history;
	private final IdentityLinkingService linking;
	private final OAuthStateService oauthStates;
	private final IntegrationProperties properties;
	private final GitHubOAuthClient github;
	private final JiraOAuthClient jira;
	private final AuditService audit;
	private final AttributionWarningService warnings;
	private final OutboxPublisher outbox;

	public PersonalIntegrationService(
			UserAccountRepository users,
			IdentityMapRepository identities,
			IdentityMappingHistoryRepository history,
			OAuthStateService oauthStates,
			IntegrationProperties properties,
			GitHubOAuthClient github,
			JiraOAuthClient jira,
			AuditService audit,
			AttributionWarningService warnings,
			OutboxPublisher outbox) {
		this.users = users;
		this.identities = identities;
		this.history = history;
		this.oauthStates = oauthStates;
		this.properties = properties;
		this.github = github;
		this.jira = jira;
		this.audit = audit;
		this.warnings = warnings;
		this.outbox = outbox;
		this.linking = new IdentityLinkingService(new JpaIdentityStore(users, identities, history));
	}

	@Transactional(readOnly = true)
	public MyIntegrationsResponse me(UUID userId) {
		List<LinkedIdentityResponse> items = identities.findByUserAccount_Id(userId).stream()
				.filter(map -> map.getMappingStatus().isActiveLink())
				.map(map -> new LinkedIdentityResponse(
						map.getId(),
						map.getProvider(),
						map.getExternalAccountId(),
						map.getExternalUsername(),
						map.getProviderDisplayName(),
						map.isPrimary(),
						map.getMappingStatus().name(),
						map.getLinkedAt()))
				.toList();
		return new MyIntegrationsResponse(items);
	}

	public OAuthStartResponse startGithub(UUID userId, String returnPath) {
		requireGithub();
		String verifier = Pkce.newVerifier();
		OAuthState state = oauthStates.start(
				userId, OAuthFlowType.GITHUB_USER_LINK, returnPath, null, null, verifier);
		String url = github.authorizationUrl(
				state.state(), Pkce.challengeS256(verifier), callbackOr(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback"));
		return new OAuthStartResponse(url, state.state());
	}

	public OAuthStartResponse startJira(UUID userId, String returnPath) {
		requireJira();
		String verifier = Pkce.newVerifier();
		OAuthState state = oauthStates.start(userId, OAuthFlowType.JIRA_USER_LINK, returnPath, null, null, verifier);
		String url = jira.authorizationUrl(
				state.state(),
				Pkce.challengeS256(verifier),
				callbackOr(properties.getJira().getOauthCallbackUrl(), "/api/integrations/jira/oauth/callback"),
				false);
		return new OAuthStartResponse(url, state.state());
	}

	@Transactional
	public String completeGithub(UUID userId, String code, String rawState, UserAccount actor) {
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.GITHUB_USER_LINK);
		if (state.pkceVerifier() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.OAUTH_STATE_INVALID, HttpStatus.BAD_REQUEST, "PKCE verifier is missing.");
		}
		String token = github.exchangeUserToken(
				code,
				state.pkceVerifier(),
				callbackOr(properties.getGithub().getOauthCallbackUrl(), "/api/integrations/github/oauth/callback"));
		GitHubOAuthClient.GitHubUser profile = github.getAuthenticatedUser(token);
		return link(
				actor,
				IntegrationProvider.GITHUB,
				String.valueOf(profile.id()),
				profile.login(),
				profile.name(),
				profile.avatar_url(),
				null,
				state);
	}

	@Transactional
	public String completeJira(UUID userId, String code, String rawState, UserAccount actor) {
		OAuthState state = oauthStates.consumeForUser(rawState, userId, OAuthFlowType.JIRA_USER_LINK);
		JiraOAuthClient.TokenResponse tokens = jira.exchange(
				code,
				state.pkceVerifier(),
				callbackOr(properties.getJira().getOauthCallbackUrl(), "/api/integrations/jira/oauth/callback"));
		List<JiraOAuthClient.AccessibleResource> resources = jira.accessibleResources(tokens.accessToken());
		if (resources.isEmpty()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SITE_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "No accessible Jira site was found.");
		}
		JiraOAuthClient.AccessibleResource site = resources.getFirst();
		JiraOAuthClient.Myself me = jira.myself(tokens.accessToken(), site.id());
		return link(actor, IntegrationProvider.JIRA, me.accountId(), me.displayName(), me.displayName(), null, site.id(), state);
	}

	@Transactional
	public void setPrimary(UUID userId, UUID identityId, UserAccount actor) {
		linking.setPrimary(userId, identityId, actor, LocalDateTime.now());
		audit.record(
				actor,
				null,
				null,
				actor.getAccountRole() != null && identities.findById(identityId).map(IdentityMap::getProvider).orElse(null)
								== IntegrationProvider.GITHUB
						? "GITHUB_PRIMARY_IDENTITY_CHANGED"
						: "JIRA_PRIMARY_IDENTITY_CHANGED",
				"identity_map",
				identityId,
				Map.of(),
				Map.of("primary", true),
				Map.of(),
				AuditSource.API,
				null,
				null,
				null);
	}

	@Transactional
	public void unlink(UUID userId, UUID identityId, UserAccount actor) {
		IdentityMap before = identities.findById(identityId).orElseThrow();
		linking.unlink(userId, identityId, actor, LocalDateTime.now());
		audit.record(
				actor,
				null,
				null,
				before.getProvider() == IntegrationProvider.GITHUB ? "GITHUB_IDENTITY_UNLINKED" : "JIRA_IDENTITY_UNLINKED",
				"identity_map",
				identityId,
				Map.of("status", "ACTIVE"),
				Map.of("status", "REVOKED"),
				Map.of("providerSubject", before.getExternalAccountId()),
				AuditSource.API,
				null,
				null,
				null);
		warnings.onIdentityChanged(actor, before, LocalDateTime.now());
		outbox.publish(
				"identity_map",
				identityId,
				"IDENTITY_UNLINKED",
				Map.of("provider", before.getProvider().name()));
	}

	private String link(
			UserAccount actor,
			IntegrationProvider provider,
			String subject,
			String login,
			String displayName,
			String avatar,
			String instanceId,
			OAuthState state) {
		IdentityLinkingService.LinkResult result = linking.link(new IdentityLinkingService.LinkCommand(
				actor, actor, provider, subject, login, displayName, avatar, instanceId, LocalDateTime.now()));
		if (result.claimedConflict()) {
			warnings.onDuplicateClaim(actor, provider, subject);
			audit.record(
					actor,
					null,
					null,
					provider == IntegrationProvider.GITHUB ? "GITHUB_IDENTITY_LINK_FAILED" : "JIRA_IDENTITY_LINK_FAILED",
					"identity_map",
					result.identity().getId(),
					Map.of(),
					Map.of(),
					Map.of("reason", "EXTERNAL_IDENTITY_ALREADY_LINKED"),
					AuditSource.OAUTH,
					null,
					null,
					null);
			throw new IntegrationException(
					IntegrationErrorCode.EXTERNAL_IDENTITY_ALREADY_LINKED,
					HttpStatus.CONFLICT,
					"This provider account is already linked.");
		}
		audit.record(
				actor,
				null,
				null,
				provider == IntegrationProvider.GITHUB ? "GITHUB_IDENTITY_LINKED" : "JIRA_IDENTITY_LINKED",
				"identity_map",
				result.identity().getId(),
				Map.of(),
				Map.of("providerSubject", subject, "login", login),
				Map.of("action", result.action().name()),
				AuditSource.OAUTH,
				null,
				null,
				null);
		outbox.publish(
				"identity_map",
				result.identity().getId(),
				"IDENTITY_LINKED",
				Map.of("provider", provider.name()));
		return redirect(state.frontendReturnPath(), true);
	}

	private String redirect(String returnPath, boolean success) {
		String base = success ? properties.getSuccessUrl() : properties.getFailureUrl();
		if (returnPath != null && returnPath.startsWith("/")) {
			return UriComponentsBuilder.fromUriString(properties.getPublicBaseUrl()).path(returnPath).build().toUriString();
		}
		return base;
	}

	private String callbackOr(String configured, String path) {
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return properties.getPublicBaseUrl() + path;
	}

	private void requireGithub() {
		if (!properties.getGithub().isConfigured()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "GitHub App is not configured.");
		}
	}

	private void requireJira() {
		if (!properties.getJira().isConfigured()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Jira OAuth is not configured.");
		}
	}

	private static final class JpaIdentityStore implements IdentityLinkingService.Store {
		private final UserAccountRepository users;
		private final IdentityMapRepository identities;
		private final IdentityMappingHistoryRepository history;

		private JpaIdentityStore(
				UserAccountRepository users,
				IdentityMapRepository identities,
				IdentityMappingHistoryRepository history) {
			this.users = users;
			this.identities = identities;
			this.history = history;
		}

		@Override
		public java.util.Optional<UserAccount> lockUser(UUID userId) {
			return users.findByIdForUpdate(userId);
		}

		@Override
		public java.util.Optional<IdentityMap> findActiveByProviderSubject(
				IntegrationProvider provider, String subject) {
			return identities.findByProviderAndExternalAccountIdAndMappingStatusIn(
					provider,
					subject,
					List.of(IdentityMappingStatus.ACTIVE, IdentityMappingStatus.VERIFIED, IdentityMappingStatus.PENDING));
		}

		@Override
		public List<IdentityMap> findByUserAndProvider(UUID userId, IntegrationProvider provider) {
			return identities.findByUserAccount_IdAndProvider(userId, provider);
		}

		@Override
		public java.util.Optional<IdentityMap> findById(UUID identityId) {
			return identities.findById(identityId);
		}

		@Override
		public IdentityMap save(IdentityMap map) {
			return identities.save(map);
		}

		@Override
		public com.saga.be.entity.integration.IdentityMappingHistory saveHistory(
				com.saga.be.entity.integration.IdentityMappingHistory row) {
			return history.save(row);
		}
	}
}

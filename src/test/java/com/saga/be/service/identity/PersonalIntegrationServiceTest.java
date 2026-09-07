package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.integration.IdentityMappingHistory;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.messaging.OutboxPublisher;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.IdentityMappingHistoryRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.attribution.AttributionWarningService;
import com.saga.be.service.audit.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PersonalIntegrationServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private IdentityMappingHistoryRepository history;
	@Mock
	private OAuthStateService oauthStates;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private JiraOAuthClient jira;
	@Mock
	private AuditService audit;
	@Mock
	private AttributionWarningService warnings;
	@Mock
	private OutboxPublisher outbox;

	private IntegrationProperties properties;
	private PersonalIntegrationService service;
	private UserAccount user;

	@BeforeEach
	void setUp() {
		properties = new IntegrationProperties();
		properties.setPublicBaseUrl("https://saga-be-production.up.railway.app");
		properties.setSuccessUrl("http://localhost:3000/integrations/success");
		properties.setFailureUrl("http://localhost:3000/integrations/failure");
		configureGithub(properties.getGithub());
		configureJira(properties.getJira());
		service = new PersonalIntegrationService(
				users, identities, history, oauthStates, properties, github, jira, audit, warnings, outbox);
		user = new UserAccount();
		user.setId(UUID.randomUUID());
		user.setEmail("student@gmail.com");
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(AccountStatus.ACTIVE);
	}

	@Test
	void startGithubStoresOnlySafeReturnPath() {
		when(oauthStates.start(eq(user.getId()), eq(OAuthFlowType.GITHUB_USER_LINK), any(), isNull(), isNull(), any()))
				.thenReturn(personalState(OAuthFlowType.GITHUB_USER_LINK, "/projects"));
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize");
		service.startGithub(user.getId(), "https://evil.example/x");
		ArgumentCaptor<String> returnPath = ArgumentCaptor.forClass(String.class);
		verify(oauthStates)
				.start(
						eq(user.getId()),
						eq(OAuthFlowType.GITHUB_USER_LINK),
						returnPath.capture(),
						isNull(),
						isNull(),
						any());
		assertEquals(null, returnPath.getValue());
	}

	@Test
	void personalGithubCallbackResolvesSafeReturnPathAgainstFrontendSuccessOrigin() {
		stubPersonalGithubLink("/projects/123/integrations");
		assertEquals(
				"http://localhost:3000/projects/123/integrations",
				service.completeGithub(user.getId(), "code", "state", user));
	}

	@Test
	void personalGithubCallbackWithoutReturnPathUsesSuccessUrl() {
		stubPersonalGithubLink(null);
		assertEquals("http://localhost:3000/integrations/success", service.completeGithub(user.getId(), "code", "state", user));
	}

	@Test
	void personalGithubCallbackIgnoresUnsafeReturnPath() {
		stubPersonalGithubLink("//evil.example");
		assertEquals("http://localhost:3000/integrations/success", service.completeGithub(user.getId(), "code", "state", user));
	}

	@Test
	void personalJiraCallbackResolvesSafeReturnPathAgainstFrontendSuccessOrigin() {
		when(oauthStates.consumeForUser(eq("state"), eq(user.getId()), eq(OAuthFlowType.JIRA_USER_LINK)))
				.thenReturn(personalState(OAuthFlowType.JIRA_USER_LINK, "/projects/123/integrations"));
		when(jira.exchange(eq("code"), eq("verifier"), any()))
				.thenReturn(new JiraOAuthClient.TokenResponse("access", "refresh", 3600, "read:jira-work"));
		when(jira.accessibleResources("access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource("cloud", "https://ex.atlassian.net", "Ex")));
		when(jira.myself("access", "cloud")).thenReturn(new JiraOAuthClient.Myself("acct-1", "User", "u@ex.com", null));
		stubIdentityPersist();
		assertEquals(
				"http://localhost:3000/projects/123/integrations",
				service.completeJira(user.getId(), "code", "state", user));
	}

	private void stubPersonalGithubLink(String returnPath) {
		when(oauthStates.consumeForUser(eq("state"), eq(user.getId()), eq(OAuthFlowType.GITHUB_USER_LINK)))
				.thenReturn(personalState(OAuthFlowType.GITHUB_USER_LINK, returnPath));
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.getAuthenticatedUser("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUser(99L, "octocat", "Octo", null));
		stubIdentityPersist();
	}

	private void stubIdentityPersist() {
		when(users.findByIdForUpdate(user.getId())).thenReturn(Optional.of(user));
		when(identities.findByProviderAndExternalAccountIdAndMappingStatusIn(any(), any(), any()))
				.thenReturn(Optional.empty());
		when(identities.findByUserAccount_IdAndProvider(eq(user.getId()), any())).thenReturn(List.of());
		when(identities.save(any(IdentityMap.class))).thenAnswer(invocation -> {
			IdentityMap saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
		when(history.save(any(IdentityMappingHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
	}

	private OAuthState personalState(OAuthFlowType flow, String returnPath) {
		return new OAuthState("state", user.getId(), flow, returnPath, null, null, "verifier", Instant.now());
	}

	private static void configureGithub(IntegrationProperties.GitHub github) {
		github.setEnabled(true);
		github.setAppId("1");
		github.setClientId("client");
		github.setClientSecret("secret");
		github.setPrivateKeyBase64("key");
		github.setWebhookSecret("hook");
	}

	private static void configureJira(IntegrationProperties.Jira jira) {
		jira.setEnabled(true);
		jira.setClientId("client");
		jira.setClientSecret("secret");
	}
}

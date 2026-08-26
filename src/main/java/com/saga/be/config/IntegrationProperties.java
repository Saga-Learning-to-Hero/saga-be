package com.saga.be.config;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

@ConfigurationProperties(prefix = "saga.integration")
public class IntegrationProperties {

	private String publicBaseUrl = "http://localhost:8080";
	private String successUrl = "http://localhost:3000/integrations/success";
	private String failureUrl = "http://localhost:3000/integrations/failure";
	private String tokenEncryptionKey = "";
	private Duration oauthStateTtl = Duration.ofMinutes(10);
	private Duration installationTokenCacheTtl = Duration.ofMinutes(50);
	@DurationUnit(ChronoUnit.MINUTES)
	private Duration reauthWindow = Duration.ofMinutes(10);
	@DurationUnit(ChronoUnit.HOURS)
	private Duration identityChangeDeadlineWindow = Duration.ofHours(24);
	private Duration jiraWebhookRefreshBeforeExpiry = Duration.ofDays(7);
	private int reauthMaxFailures = 5;
	private Duration reauthLock = Duration.ofMinutes(15);
	private Duration initialSyncFallback = Duration.ofDays(90);
	private final GitHub github = new GitHub();
	private final Jira jira = new Jira();
	private final WebAuthn webauthn = new WebAuthn();

	public String getPublicBaseUrl() {
		return publicBaseUrl;
	}

	public void setPublicBaseUrl(String publicBaseUrl) {
		this.publicBaseUrl = publicBaseUrl;
	}

	public String getSuccessUrl() {
		return successUrl;
	}

	public void setSuccessUrl(String successUrl) {
		this.successUrl = successUrl;
	}

	public String getFailureUrl() {
		return failureUrl;
	}

	public void setFailureUrl(String failureUrl) {
		this.failureUrl = failureUrl;
	}

	public String getTokenEncryptionKey() {
		return tokenEncryptionKey;
	}

	public void setTokenEncryptionKey(String tokenEncryptionKey) {
		this.tokenEncryptionKey = tokenEncryptionKey;
	}

	public Duration getOauthStateTtl() {
		return oauthStateTtl;
	}

	public void setOauthStateTtl(Duration oauthStateTtl) {
		this.oauthStateTtl = oauthStateTtl;
	}

	public Duration getInstallationTokenCacheTtl() {
		return installationTokenCacheTtl;
	}

	public void setInstallationTokenCacheTtl(Duration installationTokenCacheTtl) {
		this.installationTokenCacheTtl = installationTokenCacheTtl;
	}

	public Duration getReauthWindow() {
		return reauthWindow;
	}

	public void setReauthWindow(Duration reauthWindow) {
		this.reauthWindow = reauthWindow;
	}

	public Duration getIdentityChangeDeadlineWindow() {
		return identityChangeDeadlineWindow;
	}

	public void setIdentityChangeDeadlineWindow(Duration identityChangeDeadlineWindow) {
		this.identityChangeDeadlineWindow = identityChangeDeadlineWindow;
	}

	public Duration getJiraWebhookRefreshBeforeExpiry() {
		return jiraWebhookRefreshBeforeExpiry;
	}

	public void setJiraWebhookRefreshBeforeExpiry(Duration jiraWebhookRefreshBeforeExpiry) {
		this.jiraWebhookRefreshBeforeExpiry = jiraWebhookRefreshBeforeExpiry;
	}

	public int getReauthMaxFailures() {
		return reauthMaxFailures;
	}

	public void setReauthMaxFailures(int reauthMaxFailures) {
		this.reauthMaxFailures = reauthMaxFailures;
	}

	public Duration getReauthLock() {
		return reauthLock;
	}

	public void setReauthLock(Duration reauthLock) {
		this.reauthLock = reauthLock;
	}

	public Duration getInitialSyncFallback() {
		return initialSyncFallback;
	}

	public void setInitialSyncFallback(Duration initialSyncFallback) {
		this.initialSyncFallback = initialSyncFallback;
	}

	public GitHub getGithub() {
		return github;
	}

	public Jira getJira() {
		return jira;
	}

	public WebAuthn getWebauthn() {
		return webauthn;
	}

	public static class GitHub {
		private boolean enabled = false;
		private String appId = "";
		private String appSlug = "";
		private String clientId = "";
		private String clientSecret = "";
		private String privateKeyBase64 = "";
		private String webhookSecret = "";
		private String oauthCallbackUrl = "";
		private String setupCallbackUrl = "";

		public boolean isConfigured() {
			return enabled
					&& appId != null
					&& !appId.isBlank()
					&& clientId != null
					&& !clientId.isBlank()
					&& clientSecret != null
					&& !clientSecret.isBlank()
					&& privateKeyBase64 != null
					&& !privateKeyBase64.isBlank()
					&& webhookSecret != null
					&& !webhookSecret.isBlank();
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getAppId() {
			return appId;
		}

		public void setAppId(String appId) {
			this.appId = appId;
		}

		public String getAppSlug() {
			return appSlug;
		}

		public void setAppSlug(String appSlug) {
			this.appSlug = appSlug;
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getPrivateKeyBase64() {
			return privateKeyBase64;
		}

		public void setPrivateKeyBase64(String privateKeyBase64) {
			this.privateKeyBase64 = privateKeyBase64;
		}

		public String getWebhookSecret() {
			return webhookSecret;
		}

		public void setWebhookSecret(String webhookSecret) {
			this.webhookSecret = webhookSecret;
		}

		public String getOauthCallbackUrl() {
			return oauthCallbackUrl;
		}

		public void setOauthCallbackUrl(String oauthCallbackUrl) {
			this.oauthCallbackUrl = oauthCallbackUrl;
		}

		public String getSetupCallbackUrl() {
			return setupCallbackUrl;
		}

		public void setSetupCallbackUrl(String setupCallbackUrl) {
			this.setupCallbackUrl = setupCallbackUrl;
		}
	}

	public static class Jira {
		private boolean enabled = false;
		private String clientId = "";
		private String clientSecret = "";
		private String oauthCallbackUrl = "";
		private String teamOauthCallbackUrl = "";
		private String webhookUrl = "";
		private List<String> scopes = new ArrayList<>(List.of(
				"read:me",
				"read:jira-user",
				"read:jira-work",
				"read:project:jira",
				"read:issue-details:jira",
				"read:jql:jira",
				"read:board-scope:jira-software",
				"read:sprint:jira-software",
				"manage:jira-webhook",
				"offline_access"));

		public boolean isConfigured() {
			return enabled
					&& clientId != null
					&& !clientId.isBlank()
					&& clientSecret != null
					&& !clientSecret.isBlank();
		}

		public List<String> identityScopes() {
			return scopes.stream().filter(scope -> !"offline_access".equals(scope)).toList();
		}

		public List<String> teamScopes() {
			return List.copyOf(scopes);
		}

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getClientId() {
			return clientId;
		}

		public void setClientId(String clientId) {
			this.clientId = clientId;
		}

		public String getClientSecret() {
			return clientSecret;
		}

		public void setClientSecret(String clientSecret) {
			this.clientSecret = clientSecret;
		}

		public String getOauthCallbackUrl() {
			return oauthCallbackUrl;
		}

		public void setOauthCallbackUrl(String oauthCallbackUrl) {
			this.oauthCallbackUrl = oauthCallbackUrl;
		}

		public String getTeamOauthCallbackUrl() {
			return teamOauthCallbackUrl;
		}

		public void setTeamOauthCallbackUrl(String teamOauthCallbackUrl) {
			this.teamOauthCallbackUrl = teamOauthCallbackUrl;
		}

		public String getWebhookUrl() {
			return webhookUrl;
		}

		public void setWebhookUrl(String webhookUrl) {
			this.webhookUrl = webhookUrl;
		}

		public List<String> getScopes() {
			return scopes;
		}

		public void setScopes(List<String> scopes) {
			this.scopes = scopes;
		}
	}

	public static class WebAuthn {
		private boolean enabled = false;
		private String rpId = "";
		private String rpName = "SAGA";
		private List<String> allowedOrigins = new ArrayList<>();

		public boolean isEnabled() {
			return enabled;
		}

		public void setEnabled(boolean enabled) {
			this.enabled = enabled;
		}

		public String getRpId() {
			return rpId;
		}

		public void setRpId(String rpId) {
			this.rpId = rpId;
		}

		public String getRpName() {
			return rpName;
		}

		public void setRpName(String rpName) {
			this.rpName = rpName;
		}

		public List<String> getAllowedOrigins() {
			return allowedOrigins;
		}

		public void setAllowedOrigins(List<String> allowedOrigins) {
			this.allowedOrigins = allowedOrigins;
		}
	}
}

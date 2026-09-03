package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "saga.mail")
public class SagaMailProperties {

	private boolean enabled = false;
	private String provider = "smtp";
	private String from = "";
	private String host = "";
	private int maxAttempts = 5;
	private int batchSize = 20;
	private long workerDelayMs = 15_000L;
	private Duration claimStaleAfter = Duration.ofMinutes(5);
	private Duration retryBackoff = Duration.ofSeconds(30);

	@NestedConfigurationProperty
	private GmailApi gmailApi = new GmailApi();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public MailProvider resolvedProvider() {
		return MailProvider.from(provider);
	}

	public String getFrom() {
		return from;
	}

	public void setFrom(String from) {
		this.from = from;
	}

	public String getHost() {
		return host;
	}

	public void setHost(String host) {
		this.host = host;
	}

	public GmailApi getGmailApi() {
		return gmailApi;
	}

	public void setGmailApi(GmailApi gmailApi) {
		this.gmailApi = gmailApi == null ? new GmailApi() : gmailApi;
	}

	public int getMaxAttempts() {
		return maxAttempts;
	}

	public void setMaxAttempts(int maxAttempts) {
		this.maxAttempts = maxAttempts;
	}

	public int getBatchSize() {
		return batchSize;
	}

	public void setBatchSize(int batchSize) {
		this.batchSize = batchSize;
	}

	public long getWorkerDelayMs() {
		return workerDelayMs;
	}

	public void setWorkerDelayMs(long workerDelayMs) {
		this.workerDelayMs = workerDelayMs;
	}

	public Duration getClaimStaleAfter() {
		return claimStaleAfter;
	}

	public void setClaimStaleAfter(Duration claimStaleAfter) {
		this.claimStaleAfter = claimStaleAfter;
	}

	public Duration getRetryBackoff() {
		return retryBackoff;
	}

	public void setRetryBackoff(Duration retryBackoff) {
		this.retryBackoff = retryBackoff;
	}

	public boolean isReadyToSend() {
		if (!enabled || !hasText(from)) {
			return false;
		}
		return switch (resolvedProvider()) {
			case SMTP -> hasText(host);
			case GMAIL_API -> gmailApi.isConfigured();
		};
	}

	static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	public static class GmailApi {
		private String clientId = "";
		private String clientSecret = "";
		private String refreshToken = "";

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

		public String getRefreshToken() {
			return refreshToken;
		}

		public void setRefreshToken(String refreshToken) {
			this.refreshToken = refreshToken;
		}

		public boolean isConfigured() {
			return hasText(clientId) && hasText(clientSecret) && hasText(refreshToken);
		}
	}
}

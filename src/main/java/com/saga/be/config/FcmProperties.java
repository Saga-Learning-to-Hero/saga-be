package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

@ConfigurationProperties(prefix = "saga.fcm")
public class FcmProperties {

	private boolean enabled = false;
	private String projectId = "";
	private String clientEmail = "";
	/** PKCS#8 PEM. Never log. Railway may store literal {@code \\n} instead of newlines. */
	private String privateKey = "";

	@NestedConfigurationProperty
	private Worker worker = new Worker();

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public String getProjectId() {
		return projectId;
	}

	public void setProjectId(String projectId) {
		this.projectId = projectId == null ? "" : projectId;
	}

	public String getClientEmail() {
		return clientEmail;
	}

	public void setClientEmail(String clientEmail) {
		this.clientEmail = clientEmail == null ? "" : clientEmail;
	}

	public String getPrivateKey() {
		return privateKey;
	}

	public void setPrivateKey(String privateKey) {
		this.privateKey = privateKey == null ? "" : privateKey;
	}

	public Worker getWorker() {
		return worker;
	}

	public void setWorker(Worker worker) {
		this.worker = worker == null ? new Worker() : worker;
	}

	public static class Worker {
		private int maxAttempts = 5;
		private int batchSize = 20;
		private Duration pollDelay = Duration.ofSeconds(15);
		private Duration retryDelay = Duration.ofSeconds(30);
		private Duration claimStaleAfter = Duration.ofMinutes(5);

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

		public Duration getPollDelay() {
			return pollDelay;
		}

		public void setPollDelay(Duration pollDelay) {
			this.pollDelay = pollDelay == null ? Duration.ofSeconds(15) : pollDelay;
		}

		public Duration getRetryDelay() {
			return retryDelay;
		}

		public void setRetryDelay(Duration retryDelay) {
			this.retryDelay = retryDelay == null ? Duration.ofSeconds(30) : retryDelay;
		}

		public Duration getClaimStaleAfter() {
			return claimStaleAfter;
		}

		public void setClaimStaleAfter(Duration claimStaleAfter) {
			this.claimStaleAfter = claimStaleAfter == null ? Duration.ofMinutes(5) : claimStaleAfter;
		}
	}
}

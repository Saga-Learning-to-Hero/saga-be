package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.mail")
public class SagaMailProperties {

	private boolean enabled = false;
	private String from = "";
	private String host = "";
	private int maxAttempts = 5;
	private int batchSize = 20;
	private long workerDelayMs = 15_000L;
	private Duration claimStaleAfter = Duration.ofMinutes(5);
	private Duration retryBackoff = Duration.ofSeconds(30);

	public boolean isEnabled() {
		return enabled;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
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
		return enabled && from != null && !from.isBlank() && host != null && !host.isBlank();
	}
}

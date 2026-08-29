package com.saga.be.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "saga.roster")
public class RosterProperties {

	private long maxFileBytes = 2_097_152L;
	private Duration previewTtl = Duration.ofMinutes(15);

	public long getMaxFileBytes() {
		return maxFileBytes;
	}

	public void setMaxFileBytes(long maxFileBytes) {
		this.maxFileBytes = maxFileBytes;
	}

	public Duration getPreviewTtl() {
		return previewTtl;
	}

	public void setPreviewTtl(Duration previewTtl) {
		this.previewTtl = previewTtl;
	}
}

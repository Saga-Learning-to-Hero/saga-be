package com.saga.be.config;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Admin dashboard clock. There is no platform-wide academic {@link ZoneId} in this repository;
 * persisted timestamps stay naive {@code LocalDateTime}. This property is the only zone used for
 * dashboard {@code today} / {@code now} so host/container default timezone cannot shift
 * {@code periodStatus}, {@code currentWeekIndex}, or {@code isCurrentWeek}.
 */
@ConfigurationProperties(prefix = "saga.dashboard")
public class AdminDashboardProperties {

	/** Deterministic default: UTC. Override with {@code saga.dashboard.zone} / {@code SAGA_DASHBOARD_ZONE}. */
	private ZoneId zone = ZoneOffset.UTC;

	public ZoneId getZone() {
		return zone;
	}

	public void setZone(ZoneId zone) {
		this.zone = zone == null ? ZoneOffset.UTC : zone;
	}

	public Clock clock() {
		return Clock.system(getZone());
	}
}

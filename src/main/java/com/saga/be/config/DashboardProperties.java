package com.saga.be.config;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Shared academic/display clock for dashboards. {@code saga.dashboard.zone} (default UTC) is the
 * viewer LocalDate / ISO-week identity. It is not the persistence timebase of naive {@code
 * LocalDateTime} columns.
 */
public class DashboardProperties {

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

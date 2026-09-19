package com.saga.be.service.admin.dashboard;

import java.time.Clock;

/**
 * Persistence / operational wall-clock used for naive {@code LocalDateTime} columns such as
 * {@code webhook_receipt.created_at}.
 *
 * <p>This is <strong>not</strong> {@code saga.dashboard.zone}. That property is the academic
 * presentation clock ({@code periodStatus}, {@code currentWeekIndex}, {@code isCurrentWeek},
 * {@code daysSinceCreated}).
 *
 * <p>Source of truth, from repository configuration (not inferred from production host TZ):
 * <ul>
 *   <li>{@code JpaAuditingConfig.auditingDateTimeProvider} returns {@code LocalDateTime.now()}
 *       with no {@link Clock} — JVM default zone.</li>
 *   <li>Hibernate 7.4 {@code @CreationTimestamp} default {@code source=VM} generates
 *       {@code LocalDateTime.now(clock)} from {@code Clock.systemDefaultZone()} (or
 *       {@code hibernate.testing.clock} if set; this repo does not set it).</li>
 *   <li>No {@code hibernate.jdbc.time_zone}, no JDBC {@code connectionTimeZone}, no JVM/Railway
 *       TZ override in repo config.</li>
 * </ul>
 *
 * <p>Flyway {@code DEFAULT CURRENT_TIMESTAMP(6)} is a column fallback only. Hibernate VM
 * generation writes the Java {@code LocalDateTime} on INSERT, so DB session time is not the
 * pulse source of truth and no extra SQL {@code NOW()} query is required.
 */
public final class AdminDashboardPersistenceClock {

	private AdminDashboardPersistenceClock() {}

	public static Clock wallClock() {
		return Clock.systemDefaultZone();
	}
}

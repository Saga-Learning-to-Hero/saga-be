package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.config.AdminDashboardProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AdminDashboardPulseTimebaseTest {

	@Test
	void persistenceWallClockIsJvmDefaultNotAcademicDashboardZone() {
		assertThat(AdminDashboardPersistenceClock.wallClock().getZone()).isEqualTo(ZoneId.systemDefault());
		AdminDashboardProperties academic = new AdminDashboardProperties();
		assertThat(academic.getZone()).isEqualTo(ZoneOffset.UTC);
		assertThat(academic.clock().getZone()).isEqualTo(ZoneOffset.UTC);
	}

	@Test
	void sameInstantDifferentAcademicZoneChangesLocalDateTimeButPersistenceClockDoesNotFollowIt() {
		Instant instant = Instant.parse("2026-09-19T04:00:00Z");
		Clock academicUtc = Clock.fixed(instant, ZoneOffset.UTC);
		Clock academicIct = Clock.fixed(instant, ZoneId.of("Asia/Ho_Chi_Minh"));
		Clock persistenceNy = Clock.fixed(instant, ZoneId.of("America/New_York"));
		AdminDashboardProperties utc = new AdminDashboardProperties();
		utc.setZone(ZoneOffset.UTC);
		AdminDashboardProperties ict = new AdminDashboardProperties();
		ict.setZone(ZoneId.of("Asia/Ho_Chi_Minh"));
		assertThat(utc.clock().getZone()).isEqualTo(ZoneOffset.UTC);
		assertThat(ict.clock().getZone()).isEqualTo(ZoneId.of("Asia/Ho_Chi_Minh"));
		assertThat(LocalDateTime.now(academicUtc)).isEqualTo(LocalDateTime.of(2026, 9, 19, 4, 0));
		assertThat(LocalDateTime.now(academicIct)).isEqualTo(LocalDateTime.of(2026, 9, 19, 11, 0));
		assertThat(LocalDateTime.now(persistenceNy)).isEqualTo(LocalDateTime.of(2026, 9, 19, 0, 0));
		assertThat(LocalDateTime.now(academicIct)).isNotEqualTo(LocalDateTime.now(persistenceNy));
	}
}

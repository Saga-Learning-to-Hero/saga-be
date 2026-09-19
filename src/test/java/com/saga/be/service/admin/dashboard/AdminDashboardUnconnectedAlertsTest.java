package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class AdminDashboardUnconnectedAlertsTest {

	@Test
	void classifyMatchesCanonicalComplement() {
		assertThat(AdminDashboardUnconnectedAlerts.classify(false, false, false))
				.isEqualTo(AdminDashboardMissingService.PROJECT);
		assertThat(AdminDashboardUnconnectedAlerts.classify(false, true, true))
				.isEqualTo(AdminDashboardMissingService.PROJECT);
		assertThat(AdminDashboardUnconnectedAlerts.classify(true, false, true))
				.isEqualTo(AdminDashboardMissingService.JIRA);
		assertThat(AdminDashboardUnconnectedAlerts.classify(true, true, false))
				.isEqualTo(AdminDashboardMissingService.GITHUB);
		assertThat(AdminDashboardUnconnectedAlerts.classify(true, false, false))
				.isEqualTo(AdminDashboardMissingService.BOTH);
	}

	@Test
	void daysSinceCreatedUsesWholeCalendarDaysAndFloorsAtZero() {
		LocalDateTime created = LocalDateTime.of(2026, 9, 1, 18, 0);
		assertThat(AdminDashboardUnconnectedAlerts.daysSinceCreated(created, LocalDate.of(2026, 9, 1))).isZero();
		assertThat(AdminDashboardUnconnectedAlerts.daysSinceCreated(created, LocalDate.of(2026, 9, 8))).isEqualTo(7);
		assertThat(AdminDashboardUnconnectedAlerts.daysSinceCreated(created, LocalDate.of(2026, 9, 9))).isEqualTo(8);
		assertThat(AdminDashboardUnconnectedAlerts.daysSinceCreated(created, LocalDate.of(2026, 8, 31))).isZero();
		assertThat(AdminDashboardUnconnectedAlerts.daysSinceCreated(null, LocalDate.of(2026, 9, 9))).isZero();
	}
}

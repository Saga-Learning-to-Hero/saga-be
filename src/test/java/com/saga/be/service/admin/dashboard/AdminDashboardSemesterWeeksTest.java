package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class AdminDashboardSemesterWeeksTest {

	@Test
	void totalWeeksIsCeilInclusiveDaysOverSeven_noMondaySnapOrCap() {
		assertThat(AdminDashboardSemesterWeeks.totalWeeks(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 12, 15)))
				.isEqualTo(16);
		assertThat(AdminDashboardSemesterWeeks.totalWeeks(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)))
				.isEqualTo(1);
		assertThat(AdminDashboardSemesterWeeks.totalWeeks(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8)))
				.isEqualTo(2);
	}

	@Test
	void currentWeekIndexIsNullOutsideRange_andOneBasedInside() {
		LocalDate start = LocalDate.of(2026, 9, 1);
		LocalDate end = LocalDate.of(2026, 12, 15);
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(start, end, LocalDate.of(2026, 8, 31))).isNull();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(start, end, LocalDate.of(2026, 12, 16))).isNull();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(start, end, LocalDate.of(2026, 9, 1))).isEqualTo(1);
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(start, end, LocalDate.of(2026, 9, 19))).isEqualTo(3);
	}

	@Test
	void periodStatusIsDateOnly_andNeverActive() {
		LocalDate start = LocalDate.of(2026, 9, 1);
		LocalDate end = LocalDate.of(2026, 12, 15);
		assertThat(AdminDashboardSemesterWeeks.periodStatus(start, end, LocalDate.of(2026, 8, 1)))
				.isEqualTo(SemesterPeriodStatus.UPCOMING);
		assertThat(AdminDashboardSemesterWeeks.periodStatus(start, end, LocalDate.of(2026, 9, 19)))
				.isEqualTo(SemesterPeriodStatus.IN_PROGRESS);
		assertThat(AdminDashboardSemesterWeeks.periodStatus(start, end, LocalDate.of(2026, 12, 16)))
				.isEqualTo(SemesterPeriodStatus.COMPLETED);
	}
}

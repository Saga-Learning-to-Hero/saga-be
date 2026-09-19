package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import com.saga.be.service.admin.dashboard.AdminDashboardSemesterWeeks.WeekSlice;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
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

	@Test
	void oneWeekSemester() {
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), LocalDateTime.of(2026, 9, 3, 12, 0));
		assertThat(slices).hasSize(1);
		assertThat(slices.getFirst().startDate()).isEqualTo(LocalDate.of(2026, 9, 1));
		assertThat(slices.getFirst().endDate()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(slices.getFirst().current()).isTrue();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(slices)).isEqualTo(1);
	}

	@Test
	void eightDaySemesterHasTruncatedSecondWeek() {
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 8), LocalDateTime.of(2026, 9, 8, 0, 0));
		assertThat(slices).hasSize(2);
		assertThat(slices.get(0).endDate()).isEqualTo(LocalDate.of(2026, 9, 7));
		assertThat(slices.get(1).startDate()).isEqualTo(LocalDate.of(2026, 9, 8));
		assertThat(slices.get(1).endDate()).isEqualTo(LocalDate.of(2026, 9, 8));
		assertThat(slices.get(1).current()).isTrue();
		assertThat(slices.get(0).current()).isFalse();
	}

	@Test
	void moreThanFifteenWeeksAreAllReturned() {
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(
				LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 18), LocalDateTime.of(2026, 9, 1, 0, 0));
		assertThat(AdminDashboardSemesterWeeks.totalWeeks(LocalDate.of(2026, 9, 1), LocalDate.of(2027, 1, 18)))
				.isEqualTo(20);
		assertThat(slices).hasSize(20);
	}

	@Test
	void pastAndFutureHaveNoCurrentWeek_andBoundariesAgree() {
		LocalDate start = LocalDate.of(2026, 9, 1);
		LocalDate end = LocalDate.of(2026, 12, 15);
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(
						AdminDashboardSemesterWeeks.slices(start, end, LocalDateTime.of(2025, 1, 1, 0, 0))))
				.isNull();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(
						AdminDashboardSemesterWeeks.slices(start, end, LocalDateTime.of(2027, 1, 1, 0, 0))))
				.isNull();
		List<WeekSlice> atStart =
				AdminDashboardSemesterWeeks.slices(start, end, LocalDateTime.of(2026, 9, 1, 0, 0));
		assertThat(atStart.getFirst().current()).isTrue();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(atStart)).isEqualTo(1);
		List<WeekSlice> atEndExclusive =
				AdminDashboardSemesterWeeks.slices(start, end, AdminDashboardSemesterWeeks.endExclusive(end));
		assertThat(atEndExclusive.stream().noneMatch(WeekSlice::current)).isTrue();
		assertThat(AdminDashboardSemesterWeeks.currentWeekIndex(atEndExclusive)).isNull();
	}

	@Test
	void weekLabelIsZeroPaddedAndNeutral() {
		assertThat(AdminDashboardWeeklyTimeline.weekLabel(1)).isEqualTo("Tuần 01");
		assertThat(AdminDashboardWeeklyTimeline.weekLabel(10)).isEqualTo("Tuần 10");
	}
}

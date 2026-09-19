package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Week metadata for a date-only semester interval {@code [start, end+1day)}.
 *
 * <p>No Monday snap and no 15-week cap. {@code totalWeeks} is {@code ceil(inclusiveDays / 7)}.
 */
public final class AdminDashboardSemesterWeeks {

	private AdminDashboardSemesterWeeks() {}

	public static int totalWeeks(LocalDate start, LocalDate end) {
		long inclusiveDays = ChronoUnit.DAYS.between(start, end) + 1;
		if (inclusiveDays < 1) {
			return 1;
		}
		return (int) Math.ceil(inclusiveDays / 7.0d);
	}

	/**
	 * 1-based week index inside {@code [start, end]}, or {@code null} when {@code today} is
	 * outside the selected range.
	 */
	public static Integer currentWeekIndex(LocalDate start, LocalDate end, LocalDate today) {
		if (today.isBefore(start) || today.isAfter(end)) {
			return null;
		}
		long daysSinceStart = ChronoUnit.DAYS.between(start, today);
		return (int) (daysSinceStart / 7) + 1;
	}

	public static SemesterPeriodStatus periodStatus(LocalDate start, LocalDate end, LocalDate today) {
		if (start == null || today.isBefore(start)) {
			return SemesterPeriodStatus.UPCOMING;
		}
		if (end == null || today.isAfter(end)) {
			return SemesterPeriodStatus.COMPLETED;
		}
		return SemesterPeriodStatus.IN_PROGRESS;
	}
}

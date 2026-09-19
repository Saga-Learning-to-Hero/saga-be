package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Week metadata for a date-only semester interval {@code [start, end+1day)}.
 *
 * <p>No Monday snap and no 15-week cap. {@code totalWeeks} is {@code ceil(inclusiveDays / 7)}.
 *
 * <p>Academic dates and stored {@code LocalDateTime} columns are compared as naive wall-clock
 * values. {@code now}/{@code today} come from the dashboard {@link java.time.Clock}
 * ({@code saga.dashboard.zone}, default UTC) — not {@code Clock.systemDefaultZone()}.
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

	public static LocalDateTime startInclusive(LocalDate start) {
		return start.atStartOfDay();
	}

	public static LocalDateTime endExclusive(LocalDate end) {
		return end.plusDays(1).atStartOfDay();
	}

	public static List<WeekSlice> slices(LocalDate start, LocalDate end, LocalDateTime now) {
		LocalDateTime semesterStart = startInclusive(start);
		LocalDateTime semesterEnd = endExclusive(end);
		int weeks = totalWeeks(start, end);
		List<WeekSlice> slices = new ArrayList<>(weeks);
		for (int k = 1; k <= weeks; k++) {
			LocalDateTime weekStart = semesterStart.plusDays(7L * (k - 1));
			LocalDateTime weekEnd = weekStart.plusDays(7);
			if (weekEnd.isAfter(semesterEnd)) {
				weekEnd = semesterEnd;
			}
			boolean current = !now.isBefore(weekStart) && now.isBefore(weekEnd);
			slices.add(new WeekSlice(
					k,
					weekStart,
					weekEnd,
					weekStart.toLocalDate(),
					weekEnd.minusNanos(1).toLocalDate(),
					current));
		}
		return slices;
	}

	public static Integer currentWeekIndex(List<WeekSlice> slices) {
		for (WeekSlice slice : slices) {
			if (slice.current()) {
				return slice.index();
			}
		}
		return null;
	}

	public record WeekSlice(
			int index,
			LocalDateTime startInclusive,
			LocalDateTime endExclusive,
			LocalDate startDate,
			LocalDate endDate,
			boolean current) {}

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

package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardAvailableSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.service.admin.dashboard.AdminDashboardSemesterWeeks.WeekSlice;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Re-derives time-sensitive display fields from cached aggregates using the dashboard
 * {@link Clock}. Does not touch KPIs, weekly commit/task/rate counts, alert membership, or
 * generation. {@code daysSinceCreated} is recomputed from cached {@code createdAt}.
 */
public final class AdminDashboardTemporalView {

	private AdminDashboardTemporalView() {}

	public static AdminDashboardCachedPayload decorate(AdminDashboardCachedPayload payload, Clock clock) {
		LocalDate today = LocalDate.now(clock);
		LocalDateTime now = LocalDateTime.now(clock);
		AdminDashboardSelectedSemesterResponse selected = payload.selectedSemester();
		List<WeekSlice> slices =
				AdminDashboardSemesterWeeks.slices(selected.startDate(), selected.endDate(), now);
		Integer currentWeek = AdminDashboardSemesterWeeks.currentWeekIndex(slices);
		return new AdminDashboardCachedPayload(
				payload.generation(),
				payload.cachedAt(),
				new AdminDashboardSelectedSemesterResponse(
						selected.id(),
						selected.code(),
						selected.name(),
						selected.startDate(),
						selected.endDate(),
						selected.totalWeeks(),
						currentWeek,
						selected.active()),
				payload.availableSemesters().stream()
						.map(row -> new AdminDashboardAvailableSemesterResponse(
								row.id(),
								row.code(),
								row.name(),
								row.startDate(),
								row.endDate(),
								row.active(),
								AdminDashboardSemesterWeeks.periodStatus(row.startDate(), row.endDate(), today)))
						.toList(),
				payload.kpis(),
				payload.weeklyTimeline().stream()
						.map(point -> new AdminDashboardWeeklyPointResponse(
								point.weekIndex(),
								point.weekLabel(),
								point.startDate(),
								point.endDate(),
								currentWeek != null && currentWeek == point.weekIndex(),
								point.commits(),
								point.tasksCompleted(),
								point.traceabilityRate()))
						.toList(),
				AdminDashboardUnconnectedAlerts.withToday(payload.unconnectedTeamsAlert(), today));
	}
}

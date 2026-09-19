package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.service.admin.dashboard.AdminDashboardSemesterWeeks.WeekSlice;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * In-memory week bucketing for Phase B. SQL must not loop per week; callers load two
 * lightweight projections (V23 commit id+timestamp, DONE completion timestamps) plus a
 * distinct linked-commit id set.
 */
public final class AdminDashboardWeeklyTimeline {

	private AdminDashboardWeeklyTimeline() {}

	public static List<AdminDashboardWeeklyPointResponse> bucket(
			List<WeekSlice> slices, List<CommitActivity> commits, List<LocalDateTime> completions) {
		int n = slices.size();
		long[] commitCounts = new long[n];
		long[] linkedCounts = new long[n];
		long[] doneCounts = new long[n];
		for (CommitActivity commit : commits) {
			int index = weekIndex(slices, commit.timestamp());
			if (index < 0) {
				continue;
			}
			commitCounts[index]++;
			if (commit.linked()) {
				linkedCounts[index]++;
			}
		}
		for (LocalDateTime completion : completions) {
			int index = weekIndex(slices, completion);
			if (index >= 0) {
				doneCounts[index]++;
			}
		}
		List<AdminDashboardWeeklyPointResponse> points = new ArrayList<>(n);
		for (int i = 0; i < n; i++) {
			WeekSlice slice = slices.get(i);
			points.add(new AdminDashboardWeeklyPointResponse(
					slice.index(),
					weekLabel(slice.index()),
					slice.startDate(),
					slice.endDate(),
					slice.current(),
					commitCounts[i],
					doneCounts[i],
					AdminDashboardQueryService.ratioPercent(linkedCounts[i], commitCounts[i])));
		}
		return points;
	}

	static String weekLabel(int weekIndex) {
		return "Tuần %02d".formatted(weekIndex);
	}

	static List<CommitActivity> activities(List<Object[]> rows, Set<UUID> linkedIds) {
		Set<UUID> seen = new HashSet<>();
		List<CommitActivity> activities = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			UUID id = asUuid(row[0]);
			if (id == null || !seen.add(id)) {
				continue;
			}
			LocalDateTime timestamp = asDateTime(row[1]);
			if (timestamp == null) {
				continue;
			}
			activities.add(new CommitActivity(id, timestamp, linkedIds.contains(id)));
		}
		return activities;
	}

	private static int weekIndex(List<WeekSlice> slices, LocalDateTime timestamp) {
		if (timestamp == null) {
			return -1;
		}
		for (int i = 0; i < slices.size(); i++) {
			WeekSlice slice = slices.get(i);
			if (!timestamp.isBefore(slice.startInclusive()) && timestamp.isBefore(slice.endExclusive())) {
				return i;
			}
		}
		return -1;
	}

	public record CommitActivity(UUID id, LocalDateTime timestamp, boolean linked) {}

	private static UUID asUuid(Object value) {
		if (value instanceof UUID id) {
			return id;
		}
		if (value instanceof String text) {
			return UUID.fromString(text);
		}
		return null;
	}

	private static LocalDateTime asDateTime(Object value) {
		if (value instanceof LocalDateTime timestamp) {
			return timestamp;
		}
		if (value instanceof java.sql.Timestamp timestamp) {
			return timestamp.toLocalDateTime();
		}
		return null;
	}
}

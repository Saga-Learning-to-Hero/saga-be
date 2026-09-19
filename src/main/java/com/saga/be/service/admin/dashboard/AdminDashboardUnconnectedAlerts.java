package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import com.saga.be.dto.admin.dashboard.AdminDashboardUnconnectedTeamResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Maps the Phase-C disconnected-team projection and derives {@code missingService} /
 * {@code daysSinceCreated}. Complements the Phase-A connected KPI (Project + Jira ACTIVE + EXISTS
 * GitRepo ACTIVE). A team with no Project is {@code PROJECT}, never {@code BOTH}.
 */
public final class AdminDashboardUnconnectedAlerts {

	private AdminDashboardUnconnectedAlerts() {}

	public static List<AdminDashboardUnconnectedTeamResponse> fromRows(List<Object[]> rows, LocalDate today) {
		List<AdminDashboardUnconnectedTeamResponse> alerts = new ArrayList<>(rows.size());
		for (Object[] row : rows) {
			UUID teamId = asUuid(row[0]);
			if (teamId == null) {
				continue;
			}
			LocalDateTime createdAt = asDateTime(row[6]);
			alerts.add(new AdminDashboardUnconnectedTeamResponse(
					teamId,
					asInt(row[1]),
					(String) row[2],
					(String) row[3],
					(String) row[4],
					(String) row[5],
					classify(flag(row[7]), flag(row[8]), flag(row[9])),
					createdAt,
					daysSinceCreated(createdAt, today)));
		}
		return alerts;
	}

	public static List<AdminDashboardUnconnectedTeamResponse> withToday(
			List<AdminDashboardUnconnectedTeamResponse> alerts, LocalDate today) {
		if (alerts == null || alerts.isEmpty()) {
			return List.of();
		}
		return alerts.stream()
				.map(alert -> new AdminDashboardUnconnectedTeamResponse(
						alert.teamId(),
						alert.teamNo(),
						alert.teamName(),
						alert.courseCode(),
						alert.lecturerName(),
						alert.lecturerEmail(),
						alert.missingService(),
						alert.createdAt(),
						daysSinceCreated(alert.createdAt(), today)))
				.toList();
	}

	static AdminDashboardMissingService classify(boolean hasProject, boolean jiraActive, boolean githubActive) {
		if (!hasProject) {
			return AdminDashboardMissingService.PROJECT;
		}
		if (!jiraActive && githubActive) {
			return AdminDashboardMissingService.JIRA;
		}
		if (jiraActive && !githubActive) {
			return AdminDashboardMissingService.GITHUB;
		}
		return AdminDashboardMissingService.BOTH;
	}

	static int daysSinceCreated(LocalDateTime createdAt, LocalDate today) {
		if (createdAt == null || today == null) {
			return 0;
		}
		long days = ChronoUnit.DAYS.between(createdAt.toLocalDate(), today);
		if (days < 0) {
			return 0;
		}
		return days > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) days;
	}

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

	private static int asInt(Object value) {
		if (value instanceof Number number) {
			return number.intValue();
		}
		return 0;
	}

	private static boolean flag(Object value) {
		if (value instanceof Boolean bool) {
			return bool;
		}
		if (value instanceof Number number) {
			return number.intValue() != 0;
		}
		return false;
	}
}

package com.saga.be.dto.admin.dashboard;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One disconnected team. {@code courseCode}, {@code lecturerName}, and {@code lecturerEmail} are
 * nullable because Course.courseCode and Course.instructor are optional. {@code teamNo} and
 * {@code teamName} are required on Team. {@code daysSinceCreated} is recomputed on each response.
 */
public record AdminDashboardUnconnectedTeamResponse(
		UUID teamId,
		int teamNo,
		String teamName,
		String courseCode,
		String lecturerName,
		String lecturerEmail,
		AdminDashboardMissingService missingService,
		LocalDateTime createdAt,
		int daysSinceCreated) {}

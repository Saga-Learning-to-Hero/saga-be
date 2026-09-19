package com.saga.be.dto.admin.dashboard;

public record AdminDashboardKpisResponse(
		long totalStudents,
		Double studentsGrowthPercentage,
		String comparedSemesterCode,
		long totalCourses,
		long totalTeams,
		long connectedTeamsCount,
		Double connectedTeamsRate,
		long totalCommitsSynced,
		long totalJiraTasksSynced,
		Double traceabilityRate) {}

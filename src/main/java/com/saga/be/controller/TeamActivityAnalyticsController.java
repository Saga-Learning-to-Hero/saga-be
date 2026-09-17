package com.saga.be.controller;

import com.saga.be.dto.project.BurndownChartResponse;
import com.saga.be.dto.project.HeatmapResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.TeamActivityAnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/courses/{courseId}/teams/{teamId}")
@Tag(name = "Team activity analytics", description = "Heatmap and sprint burndown for a team's project.")
@SecurityRequirement(name = "SAGA_SESSION")
public class TeamActivityAnalyticsController {

	private final TeamActivityAnalyticsService analytics;

	public TeamActivityAnalyticsController(TeamActivityAnalyticsService analytics) {
		this.analytics = analytics;
	}

	@GetMapping("/heatmap")
	@Operation(summary = "Daily activity heatmap for the team or one ACTIVE member.")
	public HeatmapResponse heatmap(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID teamId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
			@RequestParam(required = false) UUID studentId) {
		return analytics.heatmap(principal.getUserId(), courseId, teamId, startDate, endDate, studentId);
	}

	@GetMapping("/sprints/{sprintId}/burndown")
	@Operation(summary = "Sprint burndown (ideal vs actual remaining tasks).")
	public BurndownChartResponse burndown(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID teamId,
			@PathVariable UUID sprintId) {
		return analytics.burndown(principal.getUserId(), courseId, teamId, sprintId);
	}
}

package com.saga.be.controller;

import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.service.admin.dashboard.AdminDashboardService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/admin/dashboard")
@Tag(
		name = "Admin dashboard",
		description =
				"Phase A+B+C+D: selected semester, available semesters, KPIs, weeklyTimeline, unconnectedTeamsAlert, platform-wide integrationPulse, Redis cache. ADMIN only.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminDashboardController {

	private final AdminDashboardService dashboard;

	public AdminDashboardController(AdminDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping("/summary")
	@Workload(WorkloadClass.HEAVY_READ)
	@Operation(
			summary = "Admin dashboard summary for one semester.",
			description =
					"Omitting semesterId resolves the platform active semester (active_semester_setting). "
							+ "forceRefresh=true recomputes under a single-flight lock; it does not skip the lock.")
	public AdminDashboardSummaryResponse summary(
			@RequestParam(required = false) UUID semesterId,
			@RequestParam(required = false, defaultValue = "false") boolean forceRefresh) {
		return dashboard.summary(semesterId, forceRefresh);
	}
}

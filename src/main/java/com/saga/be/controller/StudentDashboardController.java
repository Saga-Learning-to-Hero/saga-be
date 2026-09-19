package com.saga.be.controller;

import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.student.StudentDashboardService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/student/courses/{courseId}/dashboard")
@Tag(
		name = "Student dashboard",
		description =
				"Phase A+B1+B2+D1 personal cockpit: identity, optional team/project, integrations, current sprint, personal task/commit metrics, previews, last-3 ISO weekly commits, MSR and peer-review pending alerts. MEMBER, LEADER, and MENTOR.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentDashboardController {

	private final StudentDashboardService dashboard;

	public StudentDashboardController(StudentDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping
	@Operation(
			summary = "Student personal dashboard for one enrolled course (Phase A+B1+B2+D1).",
			description =
					"ACTIVE enrollment on a non-deleted course. No team and no project return 200 with nulls. "
							+ "Ordinary MEMBER is allowed. contribution and GHOSTING_WARNING are not present yet.")
	public StudentDashboardResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		return dashboard.get(principal.getUserId(), courseId);
	}
}

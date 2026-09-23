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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/student/courses/{courseId}/dashboard")
@Tag(
		name = "Student dashboard",
		description =
				"Phase A+B1+B2+D1+D2 personal cockpit: identity, optional team/project, integrations, current sprint, personal task/commit metrics, previews, last-3 ISO weekly commits, MSR, ghosting, and peer-review pending alerts. MEMBER, LEADER, and MENTOR.")
@SecurityRequirement(name = "SAGA_SESSION")
public class StudentDashboardController {

	private final StudentDashboardService dashboard;

	public StudentDashboardController(StudentDashboardService dashboard) {
		this.dashboard = dashboard;
	}

	@GetMapping
	@Operation(
			summary = "Student personal dashboard for one enrolled course (Phase A+B1+B2+D1+D2).",
			description =
					"ACTIVE enrollment on a non-deleted course. No team and no project return 200 with nulls. "
							+ "Ordinary MEMBER is allowed. contribution is not present yet. Optional sprintId selects "
							+ "which sprint's stats populate the currentSprint block (must be a local Sprint.id "
							+ "belonging to the student's own project; omit it to keep today's default: the project's "
							+ "current active sprint). Actionable alerts and personal task/commit metrics always stay "
							+ "current/project-wide regardless of sprintId -- only the currentSprint block changes.")
	public StudentDashboardResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@RequestParam(required = false) UUID sprintId) {
		return dashboard.get(principal.getUserId(), courseId, sprintId);
	}
}

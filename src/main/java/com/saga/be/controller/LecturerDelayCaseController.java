package com.saga.be.controller;

import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.delay.TaskDelayCaseService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/lecturer/delay-cases")
@Tag(name = "Delay cases", description = "The lecturer's delay case review queue.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerDelayCaseController {

	private final TaskDelayCaseService delays;

	public LecturerDelayCaseController(TaskDelayCaseService delays) {
		this.delays = delays;
	}

	@GetMapping
	@Operation(
			summary = "Delay cases across the courses this lecturer teaches",
			description = "Default: cases waiting for the lecturer (AWAITING_LECTURER), oldest first. Pass status (repeatable) "
					+ "to see others. Decide with POST /api/projects/{projectId}/delay-cases/{caseId}/lecturer-review.")
	public List<DelayCaseResponse> queue(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam(required = false) List<DelayCaseStatus> status) {
		return delays.lecturerQueue(principal.getUserId(), status);
	}
}

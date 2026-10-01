package com.saga.be.controller;

import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.dto.delay.DelayCaseDtos.ExplainRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LecturerReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LeaderReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.OnTimeRateResponse;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.delay.TaskDelayCaseService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/projects/{projectId}")
@Tag(
		name = "Delay cases",
		description = "Missed deadlines: system evidence, the assignee's explanation, team leader confirmation and "
				+ "lecturer decision. No AI. Contribution scoring is unchanged; results feed the on-time rate.")
@SecurityRequirement(name = "SAGA_SESSION")
public class TaskDelayCaseController {

	private final TaskDelayCaseService delays;

	public TaskDelayCaseController(TaskDelayCaseService delays) {
		this.delays = delays;
	}

	@GetMapping("/delay-cases")
	@Operation(
			summary = "List this project's delay cases (team members and the assigned lecturer)",
			description = "Newest first. Optional status (OPEN, AWAITING_LEADER, AWAITING_LECTURER, CLOSED_OBJECTIVE, "
					+ "CLOSED_SUBJECTIVE) and taskId filters. Notes, evidence links and reviewer comments are returned "
					+ "only to the assignee, the team leader and the lecturer.")
	public List<DelayCaseResponse> list(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) DelayCaseStatus status,
			@RequestParam(required = false) UUID taskId) {
		return delays.list(principal.getUserId(), projectId, status, taskId);
	}

	@GetMapping("/delay-cases/{caseId}")
	@Operation(summary = "One delay case, with the system's evidence and what the viewer may do (permissions)")
	public DelayCaseResponse get(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID caseId) {
		return delays.get(principal.getUserId(), projectId, caseId);
	}

	@PostMapping("/delay-cases/{caseId}/explanation")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "The assignee explains the delay (case OPEN, within the explanation window)",
			description = "note is required for OTHER, TECHNICAL_ISSUE and PERSONAL_EMERGENCY; blockingTaskId (another task "
					+ "of the project) for BLOCKED_BY_TASK; evidenceUrl is an optional http(s) link. The system checks the "
					+ "explanation against its data (verification CONSISTENT / MISMATCH / UNVERIFIABLE). Next step: the team "
					+ "leader, or the lecturer when the assignee is the leader.")
	public DelayCaseResponse explain(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID caseId,
			@Valid @RequestBody ExplainRequest request) {
		return delays.explain(principal.getUserId(), projectId, caseId, request);
	}

	@PostMapping("/delay-cases/{caseId}/leader-review")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "The team leader agrees or disagrees (case AWAITING_LEADER)",
			description = "comment is required to disagree. A subjective cause the leader agrees with and the data does not "
					+ "contradict closes as CLOSED_SUBJECTIVE; everything else goes to the lecturer.")
	public DelayCaseResponse leaderReview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID caseId,
			@Valid @RequestBody LeaderReviewRequest request) {
		return delays.leaderReview(principal.getUserId(), projectId, caseId, request);
	}

	@PostMapping("/delay-cases/{caseId}/lecturer-review")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "The course lecturer decides OBJECTIVE or SUBJECTIVE (case AWAITING_LECTURER)")
	public DelayCaseResponse lecturerReview(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID caseId,
			@Valid @RequestBody LecturerReviewRequest request) {
		return delays.lecturerReview(principal.getUserId(), projectId, caseId, request);
	}

	@PostMapping("/delay-cases/{caseId}/reopen")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "The course lecturer reopens a closed case",
			description = "A case closed for a missing explanation returns to the assignee with a new explanation window; "
					+ "any other returns to the lecturer's queue.")
	public DelayCaseResponse reopen(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID caseId) {
		return delays.reopen(principal.getUserId(), projectId, caseId);
	}

	@GetMapping("/on-time-rate")
	@Operation(
			summary = "On-time rate per team member",
			description = "Tasks count once done or past their due day; late ones excused by a delay case accepted as "
					+ "objective count as on time. Separate from the contribution percentage, which is unchanged.")
	public OnTimeRateResponse onTimeRate(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return delays.onTimeRate(principal.getUserId(), projectId);
	}
}

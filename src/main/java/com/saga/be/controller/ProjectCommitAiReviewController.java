package com.saga.be.controller;

import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.dto.ai.TeamAiKeyDtos;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.CommitAiReviewService;
import com.saga.be.service.ai.CommitTaskManualLinkService;
import com.saga.be.service.ai.TeamAiCredentialService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/projects/{projectId}")
@Tag(
		name = "Commit AI review",
		description = "AI review of each commit (message, code, task match) shown as PASS / WARNING next to the commit, "
				+ "the team's own AI key (leader-managed) and manual commit-task attachments. Display only: never scored.")
@SecurityRequirement(name = "SAGA_SESSION")
public class ProjectCommitAiReviewController {

	private final CommitAiReviewService reviews;
	private final CommitTaskManualLinkService manualLinks;
	private final TeamAiCredentialService teamKeys;

	public ProjectCommitAiReviewController(
			CommitAiReviewService reviews, CommitTaskManualLinkService manualLinks, TeamAiCredentialService teamKeys) {
		this.reviews = reviews;
		this.manualLinks = manualLinks;
		this.teamKeys = teamKeys;
	}

	@GetMapping("/commits/{commitId}/ai-review")
	@Operation(
			summary = "Detailed AI review of one commit",
			description = "status/reasons decide the badge; messageReview, codeReview (each finding with the exact diff lines) "
					+ "and taskReview explain them. canRequestReview=false with reviewBlockedReason MERGE (merge commits are "
					+ "never reviewed: hide the AI button) or NO_KEY.")
	public CommitAiReviewDtos.Detail review(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID commitId) {
		return reviews.detail(principal.getUserId(), projectId, commitId);
	}

	@PostMapping("/commits/{commitId}/ai-review")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Request (or retry) the AI review of one commit", description = "422 AI_COMMIT_MERGE_NOT_REVIEWED for a merge commit.")
	public CommitAiReviewDtos.Detail requestReview(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId, @PathVariable UUID commitId) {
		return reviews.request(principal.getUserId(), projectId, commitId);
	}

	@PostMapping("/commits/ai-review/backfill")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Leader: review up to 20 recent commits that were never reviewed (merge commits skipped)")
	public CommitAiReviewDtos.BackfillResult backfill(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestParam(required = false) Integer limit) {
		return reviews.backfill(principal.getUserId(), projectId, limit);
	}

	@PostMapping("/commits/{commitId}/manual-task-links")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(
			summary = "Attach the commit to a task by hand (commit author or team leader)",
			description = "Display and AI review context only: a manual attachment is never counted as evidence or in scoring.")
	public CommitAiReviewDtos.Detail link(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID commitId,
			@RequestBody CommitAiReviewDtos.ManualLinkRequest request) {
		return manualLinks.link(principal.getUserId(), projectId, commitId, request == null ? null : request.taskId());
	}

	@DeleteMapping("/commits/{commitId}/manual-task-links/{taskId}")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Detach a task attached by hand")
	public CommitAiReviewDtos.Detail unlink(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@PathVariable UUID commitId,
			@PathVariable UUID taskId) {
		return manualLinks.unlink(principal.getUserId(), projectId, commitId, taskId);
	}

	@GetMapping("/ai-team-key")
	@Operation(summary = "The team's AI key status (never the key) and the providers/models a leader may pick")
	public TeamAiKeyDtos.Status teamKey(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return teamKeys.status(principal.getUserId(), projectId);
	}

	@PutMapping("/ai-team-key")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Leader: save or replace the team's AI key (GEMINI, OPENAI or COHERE)")
	public TeamAiKeyDtos.Status saveTeamKey(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID projectId,
			@RequestBody TeamAiKeyDtos.SaveRequest request) {
		return teamKeys.save(principal.getUserId(), projectId, request);
	}

	@DeleteMapping("/ai-team-key")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Leader: remove the team's AI key")
	public TeamAiKeyDtos.Status removeTeamKey(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID projectId) {
		return teamKeys.revoke(principal.getUserId(), projectId);
	}
}

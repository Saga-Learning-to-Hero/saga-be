package com.saga.be.controller;

import com.saga.be.dto.peerreview.PeerReviewCandidatesResponse;
import com.saga.be.dto.peerreview.PeerReviewListResponse;
import com.saga.be.dto.peerreview.PeerReviewResponse;
import com.saga.be.dto.peerreview.PeerReviewRubricResponse;
import com.saga.be.dto.peerreview.SubmitPeerReviewRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.peerreview.PeerReviewService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/teams/{teamId}")
@Tag(name = "Peer review", description = "Team/sprint peer review: rubric, candidates, upsert, list.")
@SecurityRequirement(name = "SAGA_SESSION")
public class TeamPeerReviewController {

	private final PeerReviewService peerReviews;
	private final UserAccountRepository users;

	public TeamPeerReviewController(PeerReviewService peerReviews, UserAccountRepository users) {
		this.peerReviews = peerReviews;
		this.users = users;
	}

	@GetMapping("/peer-review-rubric")
	@Operation(summary = "Active rubric for the team (subject rubric, else global).")
	public PeerReviewRubricResponse teamRubric(
			@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID teamId) {
		return peerReviews.teamRubric(actor(principal), teamId);
	}

	@GetMapping("/sprints/{sprintId}/peer-reviews/candidates")
	@Operation(summary = "Teammates the current student can review in this sprint. Student members only.")
	public PeerReviewCandidatesResponse candidates(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID teamId,
			@PathVariable UUID sprintId) {
		return peerReviews.candidates(actor(principal), teamId, sprintId);
	}

	@PostMapping("/sprints/{sprintId}/peer-reviews")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Create or update a peer review for one teammate in this sprint. Student members only.")
	public PeerReviewResponse submit(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID teamId,
			@PathVariable UUID sprintId,
			@Valid @RequestBody SubmitPeerReviewRequest body) {
		return peerReviews.submit(actor(principal), teamId, sprintId, body);
	}

	@GetMapping("/sprints/{sprintId}/peer-reviews")
	@Operation(summary = "List peer reviews for the team in this sprint. Members, assigned lecturer, or ADMIN.")
	public PeerReviewListResponse list(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID teamId,
			@PathVariable UUID sprintId) {
		return peerReviews.list(actor(principal), teamId, sprintId);
	}

	private UserAccount actor(SagaUserPrincipal principal) {
		return users.findById(principal.getUserId()).orElseThrow();
	}
}

package com.saga.be.controller;

import com.saga.be.dto.peerreview.PeerReviewRubricResponse;
import com.saga.be.service.peerreview.PeerReviewService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("!test")
@RequestMapping("/api/peer-review-rubrics")
@Tag(name = "Peer review", description = "Default peer-review rubric used when a subject has none of its own.")
@SecurityRequirement(name = "SAGA_SESSION")
public class PeerReviewRubricController {

	private final PeerReviewService peerReviews;

	public PeerReviewRubricController(PeerReviewService peerReviews) {
		this.peerReviews = peerReviews;
	}

	@GetMapping("/default")
	@Operation(summary = "Global peer-review rubric (subject_id is null).")
	public PeerReviewRubricResponse defaultRubric() {
		return peerReviews.defaultRubric();
	}
}

package com.saga.be.dto.peerreview;

import java.util.List;
import java.util.UUID;

public record PeerReviewCandidatesResponse(
		UUID teamId, UUID sprintId, UUID reviewerId, List<Candidate> candidates) {

	public record Candidate(
			UUID studentId,
			String fullName,
			String studentCode,
			boolean alreadyReviewed,
			UUID existingReviewId,
			Integer existingTotalStarRating) {}
}

package com.saga.be.dto.peerreview;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record PeerReviewResponse(
		UUID id,
		UUID sprintId,
		String sprintName,
		UUID reviewerId,
		String reviewerName,
		UUID revieweeId,
		String revieweeName,
		Integer starRating,
		List<CriterionRating> criteriaRatings,
		String comment,
		LocalDateTime createdAt,
		LocalDateTime updatedAt) {

	public record CriterionRating(UUID rubricId, String criteriaName, Integer starRating) {}
}

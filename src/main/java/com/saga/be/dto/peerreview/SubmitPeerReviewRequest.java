package com.saga.be.dto.peerreview;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

public record SubmitPeerReviewRequest(
		@NotNull UUID revieweeId,
		@Min(1) @Max(100) Integer starRating,
		@Valid List<CriterionRating> criteriaRatings,
		@Size(max = 4000) String comment) {

	public record CriterionRating(
			@NotNull UUID rubricId, @NotNull @Min(1) @Max(5) Integer starRating) {}
}

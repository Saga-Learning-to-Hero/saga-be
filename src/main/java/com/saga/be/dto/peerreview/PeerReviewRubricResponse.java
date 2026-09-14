package com.saga.be.dto.peerreview;

import java.util.List;
import java.util.UUID;

public record PeerReviewRubricResponse(UUID teamId, UUID subjectId, List<Criterion> criteria) {

	public record Criterion(UUID rubricId, String criteriaName, String description) {}
}

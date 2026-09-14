package com.saga.be.dto.peerreview;

import java.util.List;
import java.util.UUID;

public record PeerReviewListResponse(
		UUID teamId, UUID sprintId, String sprintName, List<PeerReviewResponse> reviews) {}

package com.saga.be.dto.contribution;

import com.saga.be.entity.enums.ContributionConfigMode;
import java.util.List;
import java.util.UUID;

public record ContributionEvaluationResponse(
		UUID teamId,
		UUID projectId,
		UUID courseId,
		ContributionConfigMode configMode,
		ContributionSliceWeightsResponse sliceWeights,
		List<ContributionMemberResponse> members) {}

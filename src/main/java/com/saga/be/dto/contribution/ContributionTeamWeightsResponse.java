package com.saga.be.dto.contribution;

import com.saga.be.entity.enums.ContributionConfigMode;
import java.util.List;
import java.util.UUID;

public record ContributionTeamWeightsResponse(
		UUID courseId, ContributionConfigMode mode, List<ContributionTeamWeightRowResponse> teams) {}

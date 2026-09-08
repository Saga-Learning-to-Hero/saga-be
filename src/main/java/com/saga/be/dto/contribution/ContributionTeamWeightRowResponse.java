package com.saga.be.dto.contribution;

import java.util.UUID;

public record ContributionTeamWeightRowResponse(
		UUID teamId, UUID projectId, String teamName, Integer teamNo, boolean configured) {}

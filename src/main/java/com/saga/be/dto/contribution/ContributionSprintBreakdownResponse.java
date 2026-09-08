package com.saga.be.dto.contribution;

import java.math.BigDecimal;
import java.util.UUID;

public record ContributionSprintBreakdownResponse(
		UUID sprintId,
		String sprintName,
		BigDecimal sliceScore,
		BigDecimal sliceContributionPercentage,
		BigDecimal contributionPercentage) {}

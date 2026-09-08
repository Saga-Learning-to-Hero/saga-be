package com.saga.be.dto.contribution;

import com.saga.be.entity.enums.RoleInTeam;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ContributionMemberResponse(
		UUID studentProfileId,
		String fullName,
		String studentCode,
		RoleInTeam roleInTeam,
		BigDecimal sliceScore,
		BigDecimal sliceContributionPercentage,
		BigDecimal finalContributionPercentage,
		BigDecimal peerReviewScore,
		BigDecimal codeContributionPercentage,
		BigDecimal testContributionPercentage,
		BigDecimal documentContributionPercentage,
		BigDecimal researchContributionPercentage,
		BigDecimal taskContributionPercentage,
		List<ContributionSprintBreakdownResponse> sprintBreakdowns,
		List<String> warnings) {}

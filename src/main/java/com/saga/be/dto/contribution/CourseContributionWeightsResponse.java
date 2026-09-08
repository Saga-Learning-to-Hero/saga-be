package com.saga.be.dto.contribution;

import com.saga.be.entity.enums.ContributionConfigMode;
import java.math.BigDecimal;

public record CourseContributionWeightsResponse(
		ContributionConfigMode mode,
		BigDecimal codeWeight,
		BigDecimal testWeight,
		BigDecimal documentWeight,
		BigDecimal researchWeight) {}

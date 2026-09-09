package com.saga.be.dto.contribution;

import java.math.BigDecimal;

public record ContributionSliceWeightsResponse(
		BigDecimal codeWeight, BigDecimal testWeight, BigDecimal documentWeight, BigDecimal researchWeight) {}

package com.saga.be.dto.contribution;

import java.math.BigDecimal;
import java.util.UUID;

public record ProjectGroupWeightsResponse(
		UUID projectId,
		UUID teamId,
		BigDecimal codeWeight,
		BigDecimal testWeight,
		BigDecimal documentWeight,
		BigDecimal researchWeight,
		String note) {}

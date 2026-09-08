package com.saga.be.dto.contribution;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ProjectGroupWeightsRequest(
		UUID teamId,
		@NotNull BigDecimal codeWeight,
		@NotNull BigDecimal testWeight,
		@NotNull BigDecimal documentWeight,
		@NotNull BigDecimal researchWeight,
		String note) {}

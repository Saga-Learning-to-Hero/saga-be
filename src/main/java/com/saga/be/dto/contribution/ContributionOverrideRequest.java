package com.saga.be.dto.contribution;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

public record ContributionOverrideRequest(
		@NotNull UUID studentProfileId, @NotNull BigDecimal percentage, String reason) {}

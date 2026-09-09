package com.saga.be.dto.contribution;

import java.math.BigDecimal;
import java.util.UUID;

public record ContributionOverrideResponse(
		UUID id, UUID studentProfileId, BigDecimal oldValue, BigDecimal newValue, String reason) {}

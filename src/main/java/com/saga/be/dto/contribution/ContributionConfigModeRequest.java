package com.saga.be.dto.contribution;

import com.saga.be.entity.enums.ContributionConfigMode;
import jakarta.validation.constraints.NotNull;

public record ContributionConfigModeRequest(@NotNull ContributionConfigMode mode) {}

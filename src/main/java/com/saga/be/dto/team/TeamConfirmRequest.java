package com.saga.be.dto.team;

import jakarta.validation.constraints.NotBlank;

public record TeamConfirmRequest(@NotBlank String previewToken) {}

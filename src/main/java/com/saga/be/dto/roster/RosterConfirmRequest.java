package com.saga.be.dto.roster;

import jakarta.validation.constraints.NotBlank;

public record RosterConfirmRequest(@NotBlank String previewToken) {}

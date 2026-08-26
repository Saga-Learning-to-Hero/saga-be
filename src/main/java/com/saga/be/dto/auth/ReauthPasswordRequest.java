package com.saga.be.dto.auth;

import jakarta.validation.constraints.NotBlank;

public record ReauthPasswordRequest(@NotBlank String password) {}

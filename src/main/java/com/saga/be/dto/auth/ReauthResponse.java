package com.saga.be.dto.auth;

import java.time.Instant;

public record ReauthResponse(boolean stepUp, Instant expiresAt) {}

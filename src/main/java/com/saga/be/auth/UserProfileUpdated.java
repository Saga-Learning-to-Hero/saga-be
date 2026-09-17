package com.saga.be.auth;

import java.util.UUID;

/** Fired after a user's display name or avatar is persisted so graph projections can refresh. */
public record UserProfileUpdated(UUID userId) {}

package com.saga.be.security;

import java.time.Instant;
import java.util.UUID;

/**
 * Published after a successful ACTIVE → INACTIVE commit. Listeners must not run inside the
 * mutation transaction.
 */
public record AccountDisabledEvent(UUID userId, Instant occurredAt) {}

package com.saga.be.service.ai;

import java.util.UUID;

/**
 * Published from the successful Task Intelligence finalization transaction. It intentionally
 * carries identifiers only: listeners resolve any persisted state themselves after commit.
 */
public record TaskIntelligenceCompletedEvent(UUID projectId, UUID taskId) {}

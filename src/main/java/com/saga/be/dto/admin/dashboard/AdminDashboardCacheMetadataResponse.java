package com.saga.be.dto.admin.dashboard;

import java.time.Instant;

public record AdminDashboardCacheMetadataResponse(
		Instant cachedAt,
		Instant expiresAt,
		Long ttlSecondsRemaining,
		boolean refreshPending) {}

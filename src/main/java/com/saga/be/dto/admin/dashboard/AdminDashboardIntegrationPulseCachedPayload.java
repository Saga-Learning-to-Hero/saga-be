package com.saga.be.dto.admin.dashboard;

import java.time.Instant;
import java.util.List;

/**
 * Global Redis body for {@code saga:admin:dashboard:integration-pulse:v1}. Not stored inside the
 * semester summary v3 JSON. {@code generation} changes on every successful pulse compute.
 */
public record AdminDashboardIntegrationPulseCachedPayload(
		String generation, Instant cachedAt, List<AdminDashboardIntegrationPulseResponse> integrationPulse) {}

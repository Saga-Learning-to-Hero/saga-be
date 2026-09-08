package com.saga.be.dto.integration;

import com.saga.be.entity.enums.IntegrationProvider;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Personal provider identity status for {@code GET /api/integrations/me}.
 *
 * <p>{@code linkedAt} is the immutable first successful link time.
 * {@code lastVerifiedAt} is the last successful personal OAuth link/reconnect time
 * (suitable for UI labels like "Thời gian liên kết gần nhất").
 */
public record LinkedIdentityResponse(
		UUID id,
		IntegrationProvider provider,
		String providerSubject,
		String login,
		String displayName,
		boolean primary,
		String status,
		LocalDateTime linkedAt,
		LocalDateTime lastVerifiedAt) {}

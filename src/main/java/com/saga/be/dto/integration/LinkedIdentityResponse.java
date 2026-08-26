package com.saga.be.dto.integration;

import com.saga.be.entity.enums.IntegrationProvider;
import java.time.LocalDateTime;
import java.util.UUID;

public record LinkedIdentityResponse(
		UUID id,
		IntegrationProvider provider,
		String providerSubject,
		String login,
		String displayName,
		boolean primary,
		String status,
		LocalDateTime linkedAt) {}

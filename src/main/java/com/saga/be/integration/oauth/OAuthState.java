package com.saga.be.integration.oauth;

import com.saga.be.entity.enums.OAuthFlowType;
import java.time.Instant;
import java.util.UUID;

public record OAuthState(
		String state,
		UUID sagaUserId,
		OAuthFlowType flowType,
		String frontendReturnPath,
		UUID projectId,
		UUID teamId,
		String pkceVerifier,
		Instant createdAt) {}

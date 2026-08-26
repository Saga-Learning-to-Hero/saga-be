package com.saga.be.integration.oauth;

import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;

public class OAuthStateService {

	private final OAuthStateStore store;
	private final Duration ttl;

	public OAuthStateService(OAuthStateStore store, Duration ttl) {
		this.store = store;
		this.ttl = ttl;
	}

	public OAuthState start(
			UUID sagaUserId,
			OAuthFlowType flowType,
			String frontendReturnPath,
			UUID projectId,
			UUID teamId,
			String pkceVerifier) {
		OAuthState state = new OAuthState(
				Pkce.newState(),
				sagaUserId,
				flowType,
				frontendReturnPath,
				projectId,
				teamId,
				pkceVerifier,
				Instant.now());
		store.save(state, ttl);
		return state;
	}

	public OAuthState consumeForUser(String rawState, UUID sagaUserId) {
		if (rawState == null || rawState.isBlank()) {
			throw invalid();
		}
		OAuthState state = store.consume(rawState).orElseThrow(OAuthStateService::expiredOrReplay);
		if (!state.sagaUserId().equals(sagaUserId)) {
			throw invalid();
		}
		return state;
	}

	public OAuthState consumeForUser(String rawState, UUID sagaUserId, OAuthFlowType expectedFlow) {
		OAuthState state = consumeForUser(rawState, sagaUserId);
		if (state.flowType() != expectedFlow) {
			throw invalid();
		}
		return state;
	}

	private static IntegrationException invalid() {
		return new IntegrationException(
				IntegrationErrorCode.OAUTH_STATE_INVALID, HttpStatus.BAD_REQUEST, "OAuth state is invalid.");
	}

	private static IntegrationException expiredOrReplay() {
		return new IntegrationException(
				IntegrationErrorCode.OAUTH_STATE_EXPIRED, HttpStatus.BAD_REQUEST, "OAuth state is expired or already used.");
	}
}

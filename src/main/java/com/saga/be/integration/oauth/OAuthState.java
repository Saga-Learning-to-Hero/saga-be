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
		Instant createdAt,
		Long githubInstallationId,
		UUID jiraIntegrationId) {

	/** Backward-compatible constructor for flows that do not bind a GitHub installation id. */
	public OAuthState(
			String state,
			UUID sagaUserId,
			OAuthFlowType flowType,
			String frontendReturnPath,
			UUID projectId,
			UUID teamId,
			String pkceVerifier,
			Instant createdAt) {
		this(state, sagaUserId, flowType, frontendReturnPath, projectId, teamId, pkceVerifier, createdAt, null, null);
	}

	/** Backward-compatible constructor for GitHub reconnect without a Jira target id. */
	public OAuthState(
			String state,
			UUID sagaUserId,
			OAuthFlowType flowType,
			String frontendReturnPath,
			UUID projectId,
			UUID teamId,
			String pkceVerifier,
			Instant createdAt,
			Long githubInstallationId) {
		this(
				state,
				sagaUserId,
				flowType,
				frontendReturnPath,
				projectId,
				teamId,
				pkceVerifier,
				createdAt,
				githubInstallationId,
				null);
	}
}

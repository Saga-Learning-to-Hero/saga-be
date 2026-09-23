package com.saga.be.integration;

public enum IntegrationErrorCode {
	OAUTH_STATE_INVALID,
	OAUTH_STATE_EXPIRED,
	EXTERNAL_IDENTITY_ALREADY_LINKED,
	EXTERNAL_IDENTITY_NOT_FOUND,
	INTEGRATION_FORBIDDEN,
	NOT_TEAM_LEADER,
	GITHUB_INSTALLATION_INVALID,
	GITHUB_INSTALLATION_NOT_AUTHORIZED,
	GITHUB_INSTALLATION_IN_USE,
	GITHUB_INSTALLATION_SELECTION_REQUIRED,
	/** GitHub repository is already ACTIVE (or otherwise bound) to another SAGA project. */
	GITHUB_REPOSITORY_IN_USE,
	GITHUB_REPOSITORY_NOT_ACCESSIBLE,
	GITHUB_RATE_LIMITED,
	GITHUB_SYNC_INCOMPLETE,
	JIRA_ACCOUNT_NOT_LINKED_TO_CURRENT_USER,
	JIRA_SITE_NOT_ACCESSIBLE,
	JIRA_PROJECT_NOT_ACCESSIBLE,
	JIRA_BOARD_NOT_ACCESSIBLE,
	JIRA_TOKEN_REFRESH_FAILED,
	JIRA_UNAUTHORIZED,
	JIRA_SYNC_INCOMPLETE,
	JIRA_OAUTH_CANCELLED,
	JIRA_OAUTH_CALLBACK_INVALID,
	JIRA_PROJECT_KEY_AMBIGUOUS,
	JIRA_PROJECT_IN_USE,
	JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE,
	/**
	 * Reconnect/configure selected a different Jira cloud or project than the established source
	 * identity ({@code cloudId} + {@code jiraProjectId}). The row is not repointed; use ADD for a
	 * new source when multi-source is available.
	 */
	JIRA_SOURCE_IDENTITY_MISMATCH,
	/**
	 * Retired Phase 2 ADD guard. Kept for wire compatibility; ADD is allowed after Phase 3
	 * source-scoped sync/webhook isolation.
	 */
	JIRA_MULTI_SOURCE_NOT_READY,
	/** Explicit jiraIntegrationId is required when the project has multiple Jira sources. */
	JIRA_SOURCE_REQUIRED,
	/** Named Jira source was not found for this project. */
	JIRA_SOURCE_NOT_FOUND,
	/** Named Jira source exists but is not ACTIVE / usable for provider writes. */
	JIRA_SOURCE_NOT_ACTIVE,
	/** Failover preview/execute: source and target integrations must differ. */
	JIRA_FAILOVER_SOURCE_TARGET_SAME,
	/** Failover target integration is not ACTIVE. */
	JIRA_FAILOVER_TARGET_NOT_ACTIVE,
	/** Failover target is missing cloudId / jiraProjectId. */
	JIRA_FAILOVER_TARGET_IDENTITY_INCOMPLETE,
	/** Optional target sprint is missing, soft-deleted, or not on the target integration. */
	JIRA_FAILOVER_SPRINT_INVALID,
	/** Failover request body is incomplete or otherwise invalid. */
	JIRA_FAILOVER_INVALID_REQUEST,
	/** A requested source task is already held by another canonical failover item. */
	JIRA_FAILOVER_SOURCE_ALREADY_CLAIMED,
	/** An operator attempted an unsafe failover recovery action. */
	JIRA_FAILOVER_RECONCILIATION_REQUIRED,
	/** A verified remote issue is not in the target Jira project for this run. */
	JIRA_FAILOVER_REMOTE_ISSUE_INVALID,
	/** A target Jira issue has already been bound to a different failover item. */
	JIRA_FAILOVER_REMOTE_ISSUE_ALREADY_BOUND,
	/** A failover item already has a durable remote Jira identity and cannot be rebound. */
	JIRA_FAILOVER_REMOTE_IDENTITY_IMMUTABLE,
	JIRA_ISSUE_CREATE_FAILED,
	JIRA_ISSUE_UPDATE_FAILED,
	JIRA_ISSUE_DELETE_FAILED,
	JIRA_ISSUE_NOT_FOUND,
	JIRA_TRANSITION_UNAVAILABLE,
	JIRA_SPRINT_INVALID,
	JIRA_SPRINT_WRITE_FAILED,
	JIRA_WRITE_INCOMPLETE,
	JIRA_DELETE_BLOCKED_BY_EVIDENCE,
	/** Preferred alias when protected task evidence blocks issue delete. */
	TASK_DELETE_BLOCKED_BY_EVIDENCE,
	/** Active native SAGA children block delete; no cascade and no silent detach. */
	TASK_DELETE_BLOCKED_BY_SUBTASKS,
	JIRA_WEBHOOK_REGISTER_FAILED,
	JIRA_WEBHOOK_REFRESH_FAILED,
	JIRA_FIELD_INVALID,
	JIRA_PARENT_TASK_NOT_FOUND,
	JIRA_PARENT_SOURCE_MISMATCH,
	JIRA_PARENT_PROVIDER_ID_MISSING,
	WEBHOOK_SIGNATURE_INVALID,
	WEBHOOK_DUPLICATE,
	INTEGRATION_REVOKED,
	INTEGRATION_UNAVAILABLE,
	STEP_UP_REQUIRED,
	STEP_UP_EXPIRED,
	REAUTH_RATE_LIMITED,
	WEBAUTHN_DISABLED,
	AI_ANALYSIS_NOT_FOUND,
	AI_ANALYSIS_TARGET_NOT_FOUND,
	AI_ANALYSIS_TARGET_PROJECT_MISMATCH,
	AI_ANALYSIS_QUEUE_FULL,
	AI_ANALYSIS_PROVIDER_FAILED,
	AI_ANALYSIS_RESULT_INVALID,
	AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED,
	AI_CREDENTIAL_INVALID_REQUEST,
	AI_CREDENTIAL_NOT_FOUND,
	AI_CREDENTIAL_UNAVAILABLE,
	ACCESS_DENIED
}

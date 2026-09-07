package com.saga.be.dto.project;

public record ProjectSyncEnqueueResponse(String projectId, String jira, String github) {

	public static final String QUEUED = "QUEUED";
	public static final String SKIPPED_NOT_CONFIGURED = "SKIPPED_NOT_CONFIGURED";
	public static final String SKIPPED_NOT_ACTIVE = "SKIPPED_NOT_ACTIVE";
	public static final String SKIPPED_ALREADY_RUNNING = "SKIPPED_ALREADY_RUNNING";
	public static final String SKIPPED_NO_CREDENTIAL = "SKIPPED_NO_CREDENTIAL";
}

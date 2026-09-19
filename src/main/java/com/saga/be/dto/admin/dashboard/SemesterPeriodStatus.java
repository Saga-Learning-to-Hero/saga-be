package com.saga.be.dto.admin.dashboard;

/**
 * Temporal status derived only from semester dates versus today. Not the platform active
 * selection — that is the separate {@code active} boolean.
 */
public enum SemesterPeriodStatus {
	UPCOMING,
	IN_PROGRESS,
	COMPLETED
}

package com.saga.be.service.projection;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import java.util.UUID;

/**
 * Whether a task's Jira parent is a task SAGA has. A Jira issue id is unique only within a site,
 * so a parent is always looked up by (Jira source, parent issue id) -- never by key or project.
 * Shared by the task API and the graph so both report the same answer.
 */
public final class JiraParentResolution {

	public static final String RESOLVED = "RESOLVED";
	public static final String UNRESOLVED = "UNRESOLVED";
	/** The parent is not among this project's synced tasks (not synced yet, deleted, or moved). */
	public static final String PARENT_NOT_SYNCED = "PARENT_NOT_SYNCED";
	/** The task's Jira source is revoked/disconnected, so its parent cannot be synced now. */
	public static final String PARENT_SOURCE_REVOKED = "PARENT_SOURCE_REVOKED";

	/**
	 * @param parentTaskId the SAGA task id of the parent when resolved, else null
	 * @param resolution RESOLVED or UNRESOLVED
	 * @param reason null when RESOLVED, else PARENT_NOT_SYNCED or PARENT_SOURCE_REVOKED
	 */
	public record Result(UUID parentTaskId, String resolution, String reason) {}

	private JiraParentResolution() {}

	/** Lookup key for (source, Jira issue id). */
	public static String key(UUID jiraIntegrationId, String externalId) {
		return jiraIntegrationId + "|" + externalId;
	}

	/** True when the task names a Jira parent at all (a top-level item does not). */
	public static boolean hasParent(Task task) {
		return task.getParentExternalId() != null && !task.getParentExternalId().isBlank();
	}

	/**
	 * Null for a top-level item (no Jira parent). {@code foundParentId} is the active SAGA task of
	 * the same source whose Jira id is the parent id, or null when there is none.
	 */
	public static Result of(Task task, UUID foundParentId) {
		if (!hasParent(task)) {
			return null;
		}
		if (foundParentId != null && !foundParentId.equals(task.getId())) {
			return new Result(foundParentId, RESOLVED, null);
		}
		return new Result(null, UNRESOLVED, sourceUnavailable(task.getJiraIntegration()) ? PARENT_SOURCE_REVOKED : PARENT_NOT_SYNCED);
	}

	private static boolean sourceUnavailable(JiraIntegration source) {
		if (source == null || source.getConnectionStatus() == null) {
			return false;
		}
		IntegrationStatus status = source.getConnectionStatus();
		return status == IntegrationStatus.REVOKED || status == IntegrationStatus.DISCONNECTED;
	}
}

package com.saga.be.dto.graph;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CytoscapeNodeData(
		String id,
		String label,
		String subLabel,
		String type,
		String status,
		String weightType,
		Boolean isAnomaly,
		String avatar,
		String role,
		Integer storyPoint,
		/* TASK nodes only below; omitted (null) on every other node. */
		/** Normalized display type: EPIC / STORY / TASK / BUG / SUBTASK / REQUEST. Use issueTypeLevel for sizing. */
		String issueType,
		/** The Jira type name, e.g. "Feature". */
		String issueTypeName,
		String issueTypeId,
		/** SUBTASK / STANDARD / EPIC / ABOVE_EPIC; null = not known yet (sync the Jira source). */
		String issueTypeLevel,
		/** Jira's raw hierarchyLevel (-1 / 0 / 1 / 2+), orders levels above Epic. */
		Integer jiraHierarchyLevel,
		String jiraIntegrationId,
		String parentExternalId,
		/** Display only; parents are resolved by (jiraIntegrationId, parentExternalId). */
		String parentExternalKey,
		/** RESOLVED / UNRESOLVED; absent for a top-level item. */
		String parentResolution,
		/** PARENT_NOT_SYNCED / PARENT_SOURCE_REVOKED when UNRESOLVED. */
		String parentResolutionReason) {}

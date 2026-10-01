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
		/** TASK nodes only: EPIC / STORY / TASK / BUG / SUBTASK / REQUEST. Omitted for other nodes. */
		String issueType,
		/** TASK nodes only: the Jira type name, e.g. "Feature". Omitted for other nodes. */
		String issueTypeName) {}

package com.saga.be.dto.graph;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CytoscapeGraphMeta(
		String revision,
		int totalNodes,
		int totalEdges,
		int returnedNodes,
		int returnedEdges,
		boolean truncated,
		String nextCursor) {}

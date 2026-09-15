package com.saga.be.dto.graph;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CytoscapeEdgeData(
		String id, String source, String target, String label, Integer weight, Boolean isAnomaly) {}

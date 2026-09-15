package com.saga.be.dto.graph;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

public record CytoscapeGraphResponse(
		List<CytoscapeNode> nodes,
		List<CytoscapeEdge> edges,
		@JsonInclude(JsonInclude.Include.NON_NULL) CytoscapeGraphMeta meta) {

	public CytoscapeGraphResponse(List<CytoscapeNode> nodes, List<CytoscapeEdge> edges) {
		this(nodes, edges, null);
	}
}

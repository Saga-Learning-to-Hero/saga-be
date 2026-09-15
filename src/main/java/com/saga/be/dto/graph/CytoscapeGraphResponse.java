package com.saga.be.dto.graph;

import java.util.List;

public record CytoscapeGraphResponse(List<CytoscapeNode> nodes, List<CytoscapeEdge> edges) {}

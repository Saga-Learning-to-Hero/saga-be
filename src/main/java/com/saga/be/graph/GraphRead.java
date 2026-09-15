package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeGraphResponse;

public record GraphRead(CytoscapeGraphResponse body, long revision) {}

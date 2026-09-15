package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeEdge;
import com.saga.be.dto.graph.CytoscapeEdgeData;
import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.dto.graph.CytoscapeNode;
import com.saga.be.dto.graph.CytoscapeNodeData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

public final class CytoscapeGraphBuilder {

	private final Map<String, CytoscapeNodeData> nodes = new LinkedHashMap<>();
	private final Map<String, CytoscapeEdgeData> edges = new LinkedHashMap<>();

	public void node(CytoscapeNodeData data) {
		if (data != null && data.id() != null) {
			nodes.putIfAbsent(data.id(), data);
		}
	}

	public void edge(CytoscapeEdgeData data) {
		if (data != null && data.id() != null) {
			edges.putIfAbsent(data.id(), data);
		}
	}

	public CytoscapeGraphResponse build() {
		return new CytoscapeGraphResponse(
				nodes.values().stream().map(CytoscapeNode::new).toList(),
				edges.values().stream().map(CytoscapeEdge::new).toList());
	}

	public static CytoscapeNodeData nodeData(
			String id,
			String label,
			String subLabel,
			String type,
			String status,
			String weightType,
			Boolean anomaly,
			String avatar,
			String role,
			Integer storyPoint) {
		return new CytoscapeNodeData(
				id, label, subLabel, type, status, weightType, anomaly, avatar, role, storyPoint);
	}

	public static CytoscapeEdgeData edgeData(
			String id, String source, String target, String label, Integer weight, Boolean anomaly) {
		return new CytoscapeEdgeData(id, source, target, label, weight, anomaly);
	}

	public static CytoscapeGraphResponse empty() {
		return new CytoscapeGraphResponse(new ArrayList<>(), new ArrayList<>());
	}
}

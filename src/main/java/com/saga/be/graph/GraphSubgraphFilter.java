package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeEdge;
import com.saga.be.dto.graph.CytoscapeEdgeData;
import com.saga.be.dto.graph.CytoscapeGraphMeta;
import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.dto.graph.CytoscapeNode;
import com.saga.be.dto.graph.CytoscapeNodeData;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;

public final class GraphSubgraphFilter {

	private GraphSubgraphFilter() {}

	public static CytoscapeGraphResponse apply(CytoscapeGraphResponse source, GraphViewQuery query, long revision) {
		if (source == null) {
			return CytoscapeGraphBuilder.empty();
		}
		if (query == null || !query.active()) {
			return source;
		}
		Map<String, CytoscapeNodeData> nodes = new LinkedHashMap<>();
		for (CytoscapeNode node : source.nodes()) {
			if (node != null && node.data() != null && node.data().id() != null) {
				nodes.put(node.data().id(), node.data());
			}
		}
		List<CytoscapeEdgeData> edges = new ArrayList<>();
		for (CytoscapeEdge edge : source.edges()) {
			if (edge != null && edge.data() != null && edge.data().id() != null) {
				edges.add(edge.data());
			}
		}
		int totalNodes = nodes.size();
		int totalEdges = edges.size();
		if (query.focusNodeId() != null && !nodes.containsKey(query.focusNodeId())) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"focusNodeId was not found in this graph scope.");
		}
		List<CytoscapeEdgeData> walkable = edges;
		if (!query.edgeTypes().isEmpty()) {
			walkable = edges.stream().filter(edge -> query.edgeTypes().contains(edge.label())).toList();
		}
		Set<String> keep = selectNodes(nodes, walkable, query);
		if (!query.nodeTypes().isEmpty()) {
			keep.removeIf(id -> {
				CytoscapeNodeData data = nodes.get(id);
				return data == null || !query.nodeTypes().contains(data.type());
			});
			if (query.focusNodeId() != null && !keep.contains(query.focusNodeId())) {
				throw new AcademicException(
						AcademicErrorCode.REQUEST_INVALID,
						HttpStatus.BAD_REQUEST,
						"focusNodeId type is excluded by nodeTypes.");
			}
		}
		if (query.usedCriteriaOnly()) {
			Set<String> usedCriteria = new LinkedHashSet<>();
			for (CytoscapeEdgeData edge : edges) {
				if (!"CLASSIFIED_AS".equals(edge.label())) {
					continue;
				}
				CytoscapeNodeData target = nodes.get(edge.target());
				if (target != null && "CRITERION".equals(target.type())) {
					usedCriteria.add(target.id());
				}
			}
			keep.removeIf(id -> {
				CytoscapeNodeData data = nodes.get(id);
				return data != null && "CRITERION".equals(data.type()) && !usedCriteria.contains(id);
			});
		}
		List<String> ordered = new ArrayList<>(keep);
		if (query.focusNodeId() == null && !query.anomaliesOnly()) {
			ordered.sort(String::compareTo);
		}
		boolean truncated = false;
		String nextCursor = null;
		if (query.cursor() != null) {
			ordered = applyCursor(ordered, query.cursor(), revision);
		}
		if (query.maxNodes() != null && ordered.size() > query.maxNodes()) {
			truncated = true;
			ordered = new ArrayList<>(ordered.subList(0, query.maxNodes()));
			nextCursor = revision + ":" + ordered.getLast();
		}
		Set<String> page = new LinkedHashSet<>(ordered);
		List<CytoscapeNode> outNodes = ordered.stream().map(id -> new CytoscapeNode(nodes.get(id))).toList();
		List<CytoscapeEdge> outEdges = new ArrayList<>();
		for (CytoscapeEdgeData edge : walkable) {
			if (page.contains(edge.source()) && page.contains(edge.target())) {
				outEdges.add(new CytoscapeEdge(edge));
			}
		}
		if (query.edgeTypes().isEmpty()) {
			outEdges.clear();
			for (CytoscapeEdgeData edge : edges) {
				if (page.contains(edge.source()) && page.contains(edge.target())) {
					outEdges.add(new CytoscapeEdge(edge));
				}
			}
		}
		CytoscapeGraphMeta meta = new CytoscapeGraphMeta(
				Long.toString(revision),
				totalNodes,
				totalEdges,
				outNodes.size(),
				outEdges.size(),
				truncated,
				nextCursor);
		return new CytoscapeGraphResponse(outNodes, outEdges, meta);
	}

	private static Set<String> selectNodes(
			Map<String, CytoscapeNodeData> nodes, List<CytoscapeEdgeData> walkable, GraphViewQuery query) {
		if (query.focusNodeId() != null || query.anomaliesOnly()) {
			Map<String, TreeSet<String>> adj = new TreeMap<>();
			for (CytoscapeEdgeData edge : walkable) {
				if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
					continue;
				}
				adj.computeIfAbsent(edge.source(), id -> new TreeSet<>()).add(edge.target());
				adj.computeIfAbsent(edge.target(), id -> new TreeSet<>()).add(edge.source());
			}
			LinkedHashSet<String> seeds = new LinkedHashSet<>();
			if (query.anomaliesOnly()) {
				for (CytoscapeNodeData data : nodes.values()) {
					if (Boolean.TRUE.equals(data.isAnomaly())) {
						seeds.add(data.id());
					}
				}
			}
			if (query.focusNodeId() != null) {
				seeds.add(query.focusNodeId());
			}
			return bfs(seeds, adj, query.depth());
		}
		if (!query.nodeTypes().isEmpty()) {
			LinkedHashSet<String> typed = new LinkedHashSet<>();
			for (CytoscapeNodeData data : nodes.values()) {
				if (query.nodeTypes().contains(data.type())) {
					typed.add(data.id());
				}
			}
			return typed;
		}
		if (!query.edgeTypes().isEmpty()) {
			LinkedHashSet<String> incident = new LinkedHashSet<>();
			for (CytoscapeEdgeData edge : walkable) {
				if (nodes.containsKey(edge.source())) {
					incident.add(edge.source());
				}
				if (nodes.containsKey(edge.target())) {
					incident.add(edge.target());
				}
			}
			return incident;
		}
		return new LinkedHashSet<>(nodes.keySet());
	}

	private static LinkedHashSet<String> bfs(Set<String> seeds, Map<String, TreeSet<String>> adj, int depth) {
		LinkedHashSet<String> keep = new LinkedHashSet<>();
		ArrayDeque<String> queue = new ArrayDeque<>();
		Map<String, Integer> dist = new LinkedHashMap<>();
		for (String seed : seeds) {
			if (keep.add(seed)) {
				queue.add(seed);
				dist.put(seed, 0);
			}
		}
		while (!queue.isEmpty()) {
			String current = queue.removeFirst();
			int currentDepth = dist.get(current);
			if (currentDepth >= depth) {
				continue;
			}
			for (String next : adj.getOrDefault(current, new TreeSet<>())) {
				if (keep.add(next)) {
					dist.put(next, currentDepth + 1);
					queue.add(next);
				}
			}
		}
		return keep;
	}

	private static List<String> applyCursor(List<String> ordered, String cursor, long revision) {
		int split = cursor.indexOf(':');
		if (split <= 0) {
			throw invalidCursor();
		}
		String revisionPart = cursor.substring(0, split);
		String lastId = cursor.substring(split + 1);
		if (!revisionPart.equals(Long.toString(revision))) {
			throw invalidCursor();
		}
		int index = ordered.indexOf(lastId);
		if (index < 0) {
			throw invalidCursor();
		}
		if (index + 1 >= ordered.size()) {
			return List.of();
		}
		return new ArrayList<>(ordered.subList(index + 1, ordered.size()));
	}

	private static AcademicException invalidCursor() {
		return new AcademicException(
				AcademicErrorCode.REQUEST_INVALID,
				HttpStatus.BAD_REQUEST,
				"cursor is invalid for this graph revision.");
	}
}

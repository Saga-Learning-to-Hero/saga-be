package com.saga.be.graph;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.HttpStatus;

public record GraphViewQuery(
		String focusNodeId,
		int depth,
		Set<String> nodeTypes,
		Set<String> edgeTypes,
		boolean anomaliesOnly,
		Integer maxNodes,
		String cursor,
		boolean active,
		boolean skipDefaultCompact,
		boolean usedCriteriaOnly) {

	static final Set<String> NODE_TYPES = Set.of(
			"STUDENT", "TEAM", "PROJECT", "SPRINT", "TASK", "COMMIT", "CRITERION", "IDENTITY");
	static final Set<String> EDGE_TYPES = Set.of(
			"MEMBER_OF",
			"OWNS",
			"HAS_SPRINT",
			"CONTAINS",
			"ASSIGNED_TO",
			"EVIDENCED_BY",
			"CLASSIFIED_AS",
			"AUTHORED_BY",
			"MAPS_TO",
			"REVIEWED");
	static final Set<String> OVERVIEW_COMPACT_TYPES =
			Set.of("STUDENT", "TEAM", "PROJECT", "SPRINT", "TASK");
	static final Set<String> ACTIVITY_COMPACT_TYPES =
			Set.of("STUDENT", "SPRINT", "TASK", "CRITERION");
	static final int MAX_DEPTH = 3;
	static final int MAX_NODES_CAP = 2000;

	public static GraphViewQuery none() {
		return new GraphViewQuery(null, 1, Set.of(), Set.of(), false, null, null, false, false, false);
	}

	public static GraphViewQuery full() {
		return new GraphViewQuery(null, 1, Set.of(), Set.of(), false, null, null, false, true, false);
	}

	public static GraphViewQuery compactOverview() {
		return new GraphViewQuery(null, 1, OVERVIEW_COMPACT_TYPES, Set.of(), false, null, null, true, false, false);
	}

	public static GraphViewQuery compactActivity() {
		return new GraphViewQuery(null, 1, ACTIVITY_COMPACT_TYPES, Set.of(), false, null, null, true, false, false);
	}

	public GraphViewQuery withDefaultCompact(GraphViewQuery compact) {
		if (active || skipDefaultCompact) {
			return this;
		}
		return compact;
	}

	public static GraphViewQuery parse(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor) {
		return parse(focusNodeId, depth, nodeTypes, edgeTypes, anomaliesOnly, maxNodes, cursor, null, null, null);
	}

	public static GraphViewQuery parse(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor,
			String continuationToken) {
		return parse(
				focusNodeId,
				depth,
				nodeTypes,
				edgeTypes,
				anomaliesOnly,
				maxNodes,
				cursor,
				continuationToken,
				null,
				null);
	}

	public static GraphViewQuery parse(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor,
			String continuationToken,
			Boolean includeCommits) {
		return parse(
				focusNodeId,
				depth,
				nodeTypes,
				edgeTypes,
				anomaliesOnly,
				maxNodes,
				cursor,
				continuationToken,
				includeCommits,
				null);
	}

	public static GraphViewQuery parse(
			String focusNodeId,
			Integer depth,
			String nodeTypes,
			String edgeTypes,
			Boolean anomaliesOnly,
			Integer maxNodes,
			String cursor,
			String continuationToken,
			Boolean includeCommits,
			Boolean usedCriteriaOnly) {
		boolean hasFocus = focusNodeId != null && !focusNodeId.isBlank();
		boolean hasTypes = (nodeTypes != null && !nodeTypes.isBlank()) || (edgeTypes != null && !edgeTypes.isBlank());
		boolean hasAnomalies = Boolean.TRUE.equals(anomaliesOnly);
		String token = firstNonBlank(cursor, continuationToken);
		boolean hasPage = maxNodes != null || token != null;
		boolean hasUsedCriteria = Boolean.TRUE.equals(usedCriteriaOnly);
		boolean active = hasFocus || hasTypes || hasAnomalies || hasPage || hasUsedCriteria;
		if (!active) {
			return Boolean.TRUE.equals(includeCommits) ? full() : none();
		}
		int resolvedDepth = depth == null ? 1 : depth;
		if (resolvedDepth < 1 || resolvedDepth > MAX_DEPTH) {
			throw invalid("depth must be between 1 and " + MAX_DEPTH + ".");
		}
		Set<String> nodes = parseEnums(nodeTypes, NODE_TYPES, "nodeTypes");
		Set<String> edges = parseEnums(edgeTypes, EDGE_TYPES, "edgeTypes");
		Integer cap = maxNodes;
		if (cap != null && (cap < 1 || cap > MAX_NODES_CAP)) {
			throw invalid("maxNodes must be between 1 and " + MAX_NODES_CAP + ".");
		}
		String focus = hasFocus ? focusNodeId.trim() : null;
		if (token != null && cap == null) {
			cap = 200;
		}
		return new GraphViewQuery(focus, resolvedDepth, nodes, edges, hasAnomalies, cap, token, true, false, hasUsedCriteria);
	}

	static String combine(String scope, GraphViewQuery query) {
		String view = query == null ? "" : query.viewKey();
		if (scope == null || scope.isEmpty()) {
			return view;
		}
		return view.isEmpty() ? scope : scope + "|" + view;
	}

	public String viewKey() {
		if (!active) {
			return skipDefaultCompact ? "full" : "";
		}
		return String.join(
				"|",
				nullToEmpty(focusNodeId),
				Integer.toString(depth),
				String.join(",", new TreeSet<>(nodeTypes)),
				String.join(",", new TreeSet<>(edgeTypes)),
				Boolean.toString(anomaliesOnly),
				Boolean.toString(usedCriteriaOnly),
				maxNodes == null ? "" : maxNodes.toString(),
				nullToEmpty(cursor));
	}

	private static Set<String> parseEnums(String raw, Set<String> allowed, String field) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<String> values = new LinkedHashSet<>();
		for (String part : raw.split(",")) {
			String value = part.trim().toUpperCase(Locale.ROOT);
			if (value.isEmpty()) {
				continue;
			}
			if (!allowed.contains(value)) {
				throw invalid(field + " contains unknown value '" + value + "'.");
			}
			values.add(value);
		}
		return Collections.unmodifiableSet(values);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value.trim();
			}
		}
		return null;
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}

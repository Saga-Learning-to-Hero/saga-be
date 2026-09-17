package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.graph.CytoscapeEdge;
import com.saga.be.dto.graph.CytoscapeEdgeData;
import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.dto.graph.CytoscapeNode;
import com.saga.be.dto.graph.CytoscapeNodeData;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class GraphSubgraphFilterTest {

	private static final ObjectMapper JSON = new ObjectMapper();

	@Test
	void inactiveQueryKeepsOldContractWithoutMeta() throws Exception {
		CytoscapeGraphResponse source = sample();
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(source, GraphViewQuery.none(), 4L);
		assertThat(out.meta()).isNull();
		assertThat(out.nodes()).isEqualTo(source.nodes());
		assertThat(out.edges()).isEqualTo(source.edges());
		JsonNode tree = JSON.valueToTree(out);
		assertThat(tree.has("meta")).isFalse();
		assertThat(tree.get("nodes")).isNotNull();
		assertThat(tree.get("edges")).isNotNull();
	}

	@Test
	void depthOneIsImmediateNeighborhood() {
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(
				sample(), GraphViewQuery.parse("task:1", 1, null, null, null, null, null), 1L);
		assertThat(ids(out)).containsExactlyInAnyOrder("student:1", "task:1", "commit:1", "sprint:1");
		assertThat(ids(out)).doesNotContain("commit:2", "identity:1", "task:2");
		assertNoDanglingEdges(out);
	}

	@Test
	void depthTwoIncludesSecondHop() {
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(
				sample(), GraphViewQuery.parse("task:1", 2, null, null, null, null, null), 1L);
		assertThat(ids(out)).contains("student:1", "task:1", "commit:1", "sprint:1", "identity:1");
		assertThat(ids(out)).doesNotContain("task:2", "commit:2");
		assertNoDanglingEdges(out);
	}

	@Test
	void nodeAndEdgeTypeFiltersCombineOnSprintScopedGraph() {
		CytoscapeGraphResponse sprintSlice = GraphSubgraphFilter.apply(
				sample(),
				GraphViewQuery.parse(null, null, "TASK,COMMIT,STUDENT", "ASSIGNED_TO,EVIDENCED_BY", null, null, null),
				1L);
		assertThat(ids(sprintSlice)).containsExactlyInAnyOrder("student:1", "task:1", "task:2", "commit:1", "commit:2");
		assertThat(labels(sprintSlice)).containsExactlyInAnyOrder("ASSIGNED_TO", "EVIDENCED_BY", "EVIDENCED_BY");
		assertThat(ids(sprintSlice)).doesNotContain("identity:1", "sprint:1");
		assertNoDanglingEdges(sprintSlice);
	}

	@Test
	void danglingEdgesAreDropped() {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		builder.node(node("task:1", "TASK", false));
		builder.edge(edge("task:1", "commit:missing", "EVIDENCED_BY"));
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(
				builder.build(), GraphViewQuery.parse(null, null, "TASK", null, null, null, null), 1L);
		assertThat(out.edges()).isEmpty();
		assertNoDanglingEdges(out);
	}

	@Test
	void anomaliesKeepNeighborhoodContext() {
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(
				sample(), GraphViewQuery.parse(null, 1, null, null, true, null, null), 1L);
		assertThat(ids(out)).contains("identity:1", "commit:1", "commit:2", "task:2");
		assertThat(ids(out)).doesNotContain("student:1", "task:1", "sprint:1");
		assertNoDanglingEdges(out);
		assertThat(out.meta().truncated()).isFalse();
	}

	@Test
	void truncationReportsCursorStableForSameRevision() {
		GraphViewQuery first = GraphViewQuery.parse(null, null, "COMMIT", null, null, 1, null);
		CytoscapeGraphResponse page1 = GraphSubgraphFilter.apply(sample(), first, 8L);
		assertThat(page1.meta().truncated()).isTrue();
		assertThat(page1.meta().totalNodes()).isEqualTo(7);
		assertThat(page1.meta().returnedNodes()).isEqualTo(1);
		assertThat(page1.meta().nextCursor()).isEqualTo("8:" + page1.nodes().getFirst().data().id());

		GraphViewQuery second = GraphViewQuery.parse(null, null, "COMMIT", null, null, 1, page1.meta().nextCursor());
		CytoscapeGraphResponse page2 = GraphSubgraphFilter.apply(sample(), second, 8L);
		assertThat(ids(page1)).doesNotContainAnyElementsOf(ids(page2));
		assertThat(ids(page2)).hasSize(1);
		assertNoDanglingEdges(page1);
		assertNoDanglingEdges(page2);

		assertThatThrownBy(() -> GraphSubgraphFilter.apply(sample(), second, 9L))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void missingFocusInScopeIsRejected() {
		assertThatThrownBy(() -> GraphSubgraphFilter.apply(
						sample(), GraphViewQuery.parse("task:other-project", 1, null, null, null, null, null), 1L))
				.isInstanceOf(AcademicException.class)
				.hasMessageContaining("focusNodeId");
	}

	@Test
	void usedCriteriaOnlyHidesUnlinkedCriterionNodes() {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		builder.node(node("student:1", "STUDENT", false));
		builder.node(node("task:1", "TASK", false));
		builder.node(node("crit_code", "CRITERION", false));
		builder.node(node("crit_test", "CRITERION", false));
		builder.node(node("crit_document", "CRITERION", false));
		builder.node(node("crit_research", "CRITERION", false));
		builder.edge(edge("student:1", "task:1", "ASSIGNED_TO"));
		builder.edge(edge("task:1", "crit_code", "CLASSIFIED_AS"));
		CytoscapeGraphResponse out = GraphSubgraphFilter.apply(
				builder.build(),
				GraphViewQuery.parse(null, null, null, null, null, null, null, null, null, true),
				1L);
		assertThat(ids(out)).containsExactlyInAnyOrder("student:1", "task:1", "crit_code");
		assertThat(ids(out)).doesNotContain("crit_test", "crit_document", "crit_research");
		assertNoDanglingEdges(out);
		assertThat(out.meta().totalNodes()).isEqualTo(6);
		assertThat(out.meta().returnedNodes()).isEqualTo(3);
	}

	private static CytoscapeGraphResponse sample() {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		builder.node(node("student:1", "STUDENT", false));
		builder.node(node("sprint:1", "SPRINT", false));
		builder.node(node("task:1", "TASK", false));
		builder.node(node("task:2", "TASK", false));
		builder.node(node("commit:1", "COMMIT", false));
		builder.node(node("commit:2", "COMMIT", true));
		builder.node(node("identity:1", "IDENTITY", true));
		builder.edge(edge("student:1", "task:1", "ASSIGNED_TO"));
		builder.edge(edge("sprint:1", "task:1", "CONTAINS"));
		builder.edge(edge("task:1", "commit:1", "EVIDENCED_BY"));
		builder.edge(edge("task:2", "commit:2", "EVIDENCED_BY"));
		builder.edge(edge("commit:1", "identity:1", "AUTHORED_BY"));
		builder.edge(edge("commit:2", "identity:1", "AUTHORED_BY"));
		return builder.build();
	}

	private static CytoscapeNodeData node(String id, String type, boolean anomaly) {
		return CytoscapeGraphBuilder.nodeData(id, id, null, type, null, null, anomaly ? true : null, null, null, null);
	}

	private static CytoscapeEdgeData edge(String source, String target, String label) {
		return CytoscapeGraphBuilder.edgeData(label + ":" + source + ":" + target, source, target, label, null, null);
	}

	private static Set<String> ids(CytoscapeGraphResponse graph) {
		return graph.nodes().stream().map(CytoscapeNode::data).map(CytoscapeNodeData::id).collect(Collectors.toSet());
	}

	private static List<String> labels(CytoscapeGraphResponse graph) {
		return graph.edges().stream().map(CytoscapeEdge::data).map(CytoscapeEdgeData::label).toList();
	}

	private static void assertNoDanglingEdges(CytoscapeGraphResponse graph) {
		Set<String> nodeIds = ids(graph);
		assertThat(graph.edges())
				.allMatch(edge -> nodeIds.contains(edge.data().source()) && nodeIds.contains(edge.data().target()));
	}
}

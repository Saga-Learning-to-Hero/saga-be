package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.graph.CytoscapeEdge;
import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.dto.graph.CytoscapeNode;
import com.saga.be.graph.ProjectGraphSnapshot.CommitNode;
import com.saga.be.graph.ProjectGraphSnapshot.SprintNode;
import com.saga.be.graph.ProjectGraphSnapshot.TaskCommitLink;
import com.saga.be.graph.ProjectGraphSnapshot.TaskHierarchyLink;
import com.saga.be.graph.ProjectGraphSnapshot.TaskNode;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.neo4j.driver.AuthTokens;
import org.neo4j.driver.Driver;
import org.neo4j.driver.GraphDatabase;

/**
 * The real Cypher of {@link ProjectGraphWriter} and {@link ProjectGraphReader} against a live Neo4j
 * (no mocks): Initiative -> Epic -> Story/Task -> Subtask -> Commit, HAS_WORK_ITEM only to items
 * with no Jira parent, and an item whose parent is not synced still shown with UNRESOLVED.
 *
 * <p>Runs only when {@code SAGA_NEO4J_TEST_URI} points at a Neo4j bolt URI (auth disabled), e.g.
 * an embedded neo4j-harness; skipped in the normal build.
 */
@EnabledIfEnvironmentVariable(named = "SAGA_NEO4J_TEST_URI", matches = ".+")
class ProjectGraphNeo4jLiveTest {

	private static Driver driver;
	private static ProjectGraphWriter writer;
	private static ProjectGraphReader reader;
	private static SagaGraphClient client;

	private static final UUID PROJECT = UUID.randomUUID();
	private static final UUID SOURCE = UUID.randomUUID();
	private static final UUID SPRINT = UUID.randomUUID();
	private static final UUID INITIATIVE = UUID.randomUUID();
	private static final UUID EPIC = UUID.randomUUID();
	private static final UUID STORY = UUID.randomUUID();
	private static final UUID TASK = UUID.randomUUID();
	private static final UUID SUBTASK = UUID.randomUUID();
	private static final UUID ORPHAN = UUID.randomUUID();
	private static final UUID LOOSE = UUID.randomUUID();
	private static final UUID COMMIT = UUID.randomUUID();

	@BeforeAll
	static void connectAndWrite() {
		driver = GraphDatabase.driver(System.getenv("SAGA_NEO4J_TEST_URI"), AuthTokens.none());
		client = new SagaGraphClient(driver, "");
		writer = new ProjectGraphWriter(client);
		reader = new ProjectGraphReader(client);
		// Written twice: a rebuild must be idempotent (no duplicated edges).
		writer.rebuild(snapshot());
		writer.rebuild(snapshot());
	}

	@AfterAll
	static void close() {
		if (driver != null) {
			driver.close();
		}
	}

	@Test
	void theGraphIsStoredUnderTheCurrentVersion() {
		assertThat(client.projectExists(PROJECT)).isTrue();
	}

	@Test
	void fullOverviewHasTheWholeDecompositionAndOnlyTrueTopLevelItemsHangOffTheProject() {
		CytoscapeGraphResponse graph = reader.overview(PROJECT, null);

		assertThat(edges(graph, "PARENT_OF")).containsExactlyInAnyOrder(
				pair(INITIATIVE, EPIC), pair(EPIC, STORY), pair(EPIC, TASK), pair(STORY, SUBTASK));
		assertThat(edges(graph, "HAS_WORK_ITEM")).containsExactlyInAnyOrder(
				"project:" + PROJECT + "->task:" + INITIATIVE, "project:" + PROJECT + "->task:" + LOOSE);
		assertThat(edgeCount(graph)).isEqualTo(edgeIds(graph).size());
	}

	@Test
	void anItemWithAnUnsyncedParentIsStillShownWithAWarningAndNoFakeProjectLink() {
		CytoscapeGraphResponse graph = reader.overview(PROJECT, null);

		CytoscapeNode orphan = node(graph, ORPHAN).orElseThrow();
		assertThat(orphan.data().parentResolution()).isEqualTo("UNRESOLVED");
		assertThat(orphan.data().parentResolutionReason()).isEqualTo("PARENT_NOT_SYNCED");
		assertThat(orphan.data().parentExternalKey()).isEqualTo("SAGA-999");
		assertThat(edges(graph, "HAS_WORK_ITEM")).noneMatch(edge -> edge.endsWith("task:" + ORPHAN));
		assertThat(node(reader.overview(PROJECT, SPRINT), ORPHAN)).isPresent();
	}

	@Test
	void taskNodesCarryTheIssueTypeMetadata() {
		CytoscapeGraphResponse graph = reader.overview(PROJECT, null);

		assertThat(node(graph, INITIATIVE).orElseThrow().data().issueTypeLevel()).isEqualTo("ABOVE_EPIC");
		assertThat(node(graph, INITIATIVE).orElseThrow().data().jiraHierarchyLevel()).isEqualTo(2);
		assertThat(node(graph, SUBTASK).orElseThrow().data().issueTypeLevel()).isEqualTo("SUBTASK");
		assertThat(node(graph, SUBTASK).orElseThrow().data().parentResolution()).isEqualTo("RESOLVED");
		assertThat(node(graph, LOOSE).orElseThrow().data().parentResolution()).isNull();
		assertThat(node(graph, LOOSE).orElseThrow().data().issueTypeLevel()).isEqualTo("UNKNOWN");
	}

	@Test
	void sprintOverviewPullsInTheWholeAncestorChainThatSitsInNoSprint() {
		CytoscapeGraphResponse graph = reader.overview(PROJECT, SPRINT);

		assertThat(node(graph, INITIATIVE)).isPresent();
		assertThat(node(graph, EPIC)).isPresent();
		assertThat(edges(graph, "PARENT_OF")).contains(
				pair(INITIATIVE, EPIC), pair(EPIC, STORY), pair(EPIC, TASK), pair(STORY, SUBTASK));
		assertThat(edges(graph, "HAS_WORK_ITEM")).contains("project:" + PROJECT + "->task:" + INITIATIVE);
		assertThat(node(graph, LOOSE)).isEmpty();
	}

	@Test
	void activityShowsTheSameChain() {
		CytoscapeGraphResponse graph = reader.activity(PROJECT, SPRINT);

		assertThat(edges(graph, "PARENT_OF")).contains(pair(INITIATIVE, EPIC), pair(STORY, SUBTASK));
		assertThat(edges(graph, "HAS_WORK_ITEM")).contains("project:" + PROJECT + "->task:" + INITIATIVE);
	}

	@Test
	void attributionTracesACommitBackToTheProject() {
		for (UUID sprint : new UUID[] {null, SPRINT}) {
			CytoscapeGraphResponse graph = reader.attribution(PROJECT, sprint);

			assertThat(edges(graph, "EVIDENCED_BY")).contains("task:" + SUBTASK + "->commit:" + COMMIT);
			assertThat(edges(graph, "PARENT_OF")).contains(
					pair(STORY, SUBTASK), pair(EPIC, STORY), pair(INITIATIVE, EPIC));
			assertThat(edges(graph, "HAS_WORK_ITEM")).contains("project:" + PROJECT + "->task:" + INITIATIVE);
		}
	}

	private static ProjectGraphSnapshot snapshot() {
		return new ProjectGraphSnapshot(
				PROJECT,
				"SAGA",
				null,
				List.of(),
				List.of(new SprintNode(SPRINT, "Sprint 3", "active")),
				List.of(
						task(INITIATIVE, null, "SAGA-1", "ABOVE_EPIC", 2, null, null, true),
						task(EPIC, null, "SAGA-2", "EPIC", 1, "SAGA-1", "RESOLVED", false),
						task(STORY, SPRINT, "SAGA-3", "STANDARD", 0, "SAGA-2", "RESOLVED", false),
						task(TASK, SPRINT, "SAGA-4", "STANDARD", 0, "SAGA-2", "RESOLVED", false),
						task(SUBTASK, SPRINT, "SAGA-5", "SUBTASK", -1, "SAGA-3", "RESOLVED", false),
						task(ORPHAN, SPRINT, "SAGA-6", "STANDARD", 0, "SAGA-999", "UNRESOLVED", false),
						task(LOOSE, null, "SAGA-7", "UNKNOWN", null, null, null, true)),
				List.of(new CommitNode(COMMIT, "a1b2c3d4", "Login API", "github:trung", null, true)),
				List.of(new TaskCommitLink(SUBTASK, COMMIT)),
				List.of(
						new TaskHierarchyLink(INITIATIVE, EPIC),
						new TaskHierarchyLink(EPIC, STORY),
						new TaskHierarchyLink(EPIC, TASK),
						new TaskHierarchyLink(STORY, SUBTASK)));
	}

	private static TaskNode task(
			UUID id,
			UUID sprint,
			String key,
			String level,
			Integer hierarchy,
			String parentKey,
			String resolution,
			boolean topLevel) {
		return new TaskNode(
				id, sprint, null, key, key + " title", "TODO", 3, null, false, false, 0,
				"TASK", "Type", "type-" + level, level, hierarchy, SOURCE,
				parentKey == null ? null : "id-" + parentKey, parentKey, resolution,
				"UNRESOLVED".equals(resolution) ? "PARENT_NOT_SYNCED" : null, topLevel);
	}

	private static String pair(UUID parent, UUID child) {
		return "task:" + parent + "->task:" + child;
	}

	private static Set<String> edges(CytoscapeGraphResponse graph, String label) {
		return graph.edges().stream()
				.map(CytoscapeEdge::data)
				.filter(edge -> label.equals(edge.label()))
				.map(edge -> edge.source() + "->" + edge.target())
				.collect(Collectors.toSet());
	}

	private static long edgeCount(CytoscapeGraphResponse graph) {
		return graph.edges().size();
	}

	private static Set<String> edgeIds(CytoscapeGraphResponse graph) {
		return graph.edges().stream().map(edge -> edge.data().id()).collect(Collectors.toSet());
	}

	private static Optional<CytoscapeNode> node(CytoscapeGraphResponse graph, UUID taskId) {
		return graph.nodes().stream().filter(n -> ("task:" + taskId).equals(n.data().id())).findFirst();
	}
}

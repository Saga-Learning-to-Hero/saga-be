package com.saga.be.graph;

import static com.saga.be.graph.CytoscapeGraphBuilder.edgeData;
import static com.saga.be.graph.CytoscapeGraphBuilder.nodeData;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import java.util.Map;
import java.util.UUID;
import org.neo4j.driver.Record;
import org.neo4j.driver.Value;
import org.neo4j.driver.types.Node;
import org.neo4j.driver.types.Relationship;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ProjectGraphReader {

	private final SagaGraphClient graph;

	public ProjectGraphReader(SagaGraphClient graph) {
		this.graph = graph;
	}

	public CytoscapeGraphResponse overview(UUID projectId, UUID sprintId) {
		String pid = SagaGraphIds.project(projectId);
		String projectUuid = projectId.toString();
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		for (Record record : graph.read("MATCH (p:Project {id: $id}) RETURN p", Map.of("id", pid))) {
			addNode(builder, record, "p");
		}
		for (Record record : graph.read(
				"""
				MATCH (t:Team {projectId: $projectId})-[r:OWNS]->(p:Project {id: $id})
				OPTIONAL MATCH (s:Student)-[:MEMBER_OF]->(t)
				RETURN t, p, s
				""",
				Map.of("projectId", projectUuid, "id", pid))) {
			addNode(builder, record, "t");
			addNode(builder, record, "p");
			addNode(builder, record, "s");
			addRel(builder, record.get("t"), record.get("p"), "OWNS");
			addRel(builder, record.get("s"), record.get("t"), "MEMBER_OF");
		}
		if (sprintId == null) {
			for (Record record : graph.read(
					"""
					MATCH (p:Project {id: $id})-[:HAS_SPRINT]->(sp:Sprint)
					RETURN p, sp
					""",
					Map.of("id", pid))) {
				addNode(builder, record, "p");
				addNode(builder, record, "sp");
				addRel(builder, record.get("p"), record.get("sp"), "HAS_SPRINT");
			}
			for (Record record : graph.read(
					"""
					MATCH (sp:Sprint {projectId: $projectId})-[:CONTAINS]->(task:Task)
					OPTIONAL MATCH (assignee:Student)-[:ASSIGNED_TO]->(task)
					OPTIONAL MATCH (task)-[:EVIDENCED_BY]->(c:Commit)
					RETURN sp, task, assignee, c
					""",
					Map.of("projectId", projectUuid))) {
				addOverviewTaskRow(builder, record);
			}
			for (Record record : graph.read(
					"""
					MATCH (task:Task {projectId: $projectId})
					WHERE NOT ( (:Sprint)-[:CONTAINS]->(task) )
					OPTIONAL MATCH (assignee:Student)-[:ASSIGNED_TO]->(task)
					OPTIONAL MATCH (task)-[:EVIDENCED_BY]->(c:Commit)
					RETURN task, assignee, c
					""",
					Map.of("projectId", projectUuid))) {
				addNode(builder, record, "task");
				addNode(builder, record, "assignee");
				addNode(builder, record, "c");
				addRel(builder, record.get("assignee"), record.get("task"), "ASSIGNED_TO");
				addRel(builder, record.get("task"), record.get("c"), "EVIDENCED_BY");
			}
			return builder.build();
		}
		for (Record record : graph.read(
				"""
				MATCH (p:Project {id: $id})-[:HAS_SPRINT]->(sp:Sprint {id: $sprintId})
				RETURN p, sp
				""",
				Map.of("id", pid, "sprintId", SagaGraphIds.sprint(sprintId)))) {
			addNode(builder, record, "p");
			addNode(builder, record, "sp");
			addRel(builder, record.get("p"), record.get("sp"), "HAS_SPRINT");
		}
		for (Record record : graph.read(
				"""
				MATCH (sp:Sprint {id: $sprintId, projectId: $projectId})-[:CONTAINS]->(task:Task)
				OPTIONAL MATCH (assignee:Student)-[:ASSIGNED_TO]->(task)
				OPTIONAL MATCH (task)-[:EVIDENCED_BY]->(c:Commit)
				RETURN sp, task, assignee, c
				""",
				Map.of("projectId", projectUuid, "sprintId", SagaGraphIds.sprint(sprintId)))) {
			addOverviewTaskRow(builder, record);
		}
		return builder.build();
	}

	private static void addOverviewTaskRow(CytoscapeGraphBuilder builder, Record record) {
		addNode(builder, record, "sp");
		addNode(builder, record, "task");
		addNode(builder, record, "assignee");
		addNode(builder, record, "c");
		addRel(builder, record.get("sp"), record.get("task"), "CONTAINS");
		addRel(builder, record.get("assignee"), record.get("task"), "ASSIGNED_TO");
		addRel(builder, record.get("task"), record.get("c"), "EVIDENCED_BY");
	}

	public CytoscapeGraphResponse contribution(UUID projectId, UUID studentId, UUID sprintId) {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		for (Record record : graph.read("MATCH (crit:Criterion) RETURN crit", Map.of())) {
			addNode(builder, record, "crit");
		}
		for (Record record : graph.read(
				"MATCH (s:Student {id: $studentId}) RETURN s",
				Map.of("studentId", SagaGraphIds.student(studentId)))) {
			addNode(builder, record, "s");
		}
		StringBuilder cypher = new StringBuilder(
				"""
				MATCH (s:Student {id: $studentId})-[:ASSIGNED_TO]->(task:Task {projectId: $projectId})<-[:CONTAINS]-(:Sprint)
				WHERE task.status = 'DONE'
				""");
		if (sprintId != null) {
			cypher.append(
					"""
					MATCH (sp:Sprint {id: $sprintId})-[:CONTAINS]->(task)
					""");
		}
		cypher.append(
				"""
				OPTIONAL MATCH (task)-[:CLASSIFIED_AS]->(crit:Criterion)
				OPTIONAL MATCH (task)-[:EVIDENCED_BY]->(c:Commit)
				RETURN s, task, crit, c
				""");
		Map<String, Object> params = new java.util.HashMap<>();
		params.put("studentId", SagaGraphIds.student(studentId));
		params.put("projectId", projectId.toString());
		if (sprintId != null) {
			params.put("sprintId", SagaGraphIds.sprint(sprintId));
		}
		for (Record record : graph.read(cypher.toString(), params)) {
			addNode(builder, record, "s");
			addNode(builder, record, "task");
			addNode(builder, record, "crit");
			addNode(builder, record, "c");
			addRel(builder, record.get("s"), record.get("task"), "ASSIGNED_TO");
			addRel(builder, record.get("task"), record.get("crit"), "CLASSIFIED_AS");
			addRel(builder, record.get("task"), record.get("c"), "EVIDENCED_BY");
		}
		return builder.build();
	}

	public CytoscapeGraphResponse activity(UUID projectId, UUID sprintId) {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		for (Record record : graph.read("MATCH (crit:Criterion) RETURN crit", Map.of())) {
			addNode(builder, record, "crit");
		}
		for (Record record : graph.read(
				"""
				MATCH (sp:Sprint {id: $sprintId, projectId: $projectId})
				RETURN sp
				""",
				Map.of("sprintId", SagaGraphIds.sprint(sprintId), "projectId", projectId.toString()))) {
			addNode(builder, record, "sp");
		}
		for (Record record : graph.read(
				"""
				MATCH (sp:Sprint {id: $sprintId, projectId: $projectId})-[:CONTAINS]->(task:Task)
				OPTIONAL MATCH (s:Student)-[:ASSIGNED_TO]->(task)
				OPTIONAL MATCH (task)-[:CLASSIFIED_AS]->(crit:Criterion)
				OPTIONAL MATCH (task)-[:EVIDENCED_BY]->(c:Commit)
				RETURN sp, task, s, crit, c
				""",
				Map.of("sprintId", SagaGraphIds.sprint(sprintId), "projectId", projectId.toString()))) {
			addNode(builder, record, "sp");
			addNode(builder, record, "task");
			addNode(builder, record, "s");
			addNode(builder, record, "crit");
			addNode(builder, record, "c");
			addRel(builder, record.get("sp"), record.get("task"), "CONTAINS");
			addRel(builder, record.get("s"), record.get("task"), "ASSIGNED_TO");
			addRel(builder, record.get("task"), record.get("crit"), "CLASSIFIED_AS");
			addRel(builder, record.get("task"), record.get("c"), "EVIDENCED_BY");
		}
		return builder.build();
	}

	public CytoscapeGraphResponse attribution(UUID projectId, UUID sprintId) {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		String cypher;
		Map<String, Object> params = new java.util.HashMap<>();
		params.put("projectId", projectId.toString());
		if (sprintId == null) {
			cypher =
					"""
					MATCH (c:Commit {projectId: $projectId})-[:AUTHORED_BY]->(i:Identity)
					OPTIONAL MATCH (i)-[:MAPS_TO]->(s:Student)
					OPTIONAL MATCH (task:Task)-[:EVIDENCED_BY]->(c)
					OPTIONAL MATCH (assignee:Student)-[:ASSIGNED_TO]->(task)
					RETURN c, i, s, task, assignee
					""";
		} else {
			params.put("sprintId", SagaGraphIds.sprint(sprintId));
			cypher =
					"""
					MATCH (sp:Sprint {id: $sprintId, projectId: $projectId})-[:CONTAINS]->(task:Task)
					MATCH (task)-[:EVIDENCED_BY]->(c:Commit)-[:AUTHORED_BY]->(i:Identity)
					OPTIONAL MATCH (i)-[:MAPS_TO]->(s:Student)
					OPTIONAL MATCH (assignee:Student)-[:ASSIGNED_TO]->(task)
					RETURN c, i, s, task, assignee
					""";
		}
		for (Record record : graph.read(cypher, params)) {
			addNode(builder, record, "c");
			addNode(builder, record, "i");
			addNode(builder, record, "s");
			addNode(builder, record, "task");
			addNode(builder, record, "assignee");
			addRel(builder, record.get("c"), record.get("i"), "AUTHORED_BY");
			addRel(builder, record.get("i"), record.get("s"), "MAPS_TO");
			addRel(builder, record.get("task"), record.get("c"), "EVIDENCED_BY");
			addRel(builder, record.get("assignee"), record.get("task"), "ASSIGNED_TO");
		}
		return builder.build();
	}

	public CytoscapeGraphResponse peerReview(UUID projectId, UUID sprintId) {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		for (Record record : graph.read(
				"""
				MATCH (s:Student)-[:MEMBER_OF]->(:Team {projectId: $projectId})
				RETURN s
				""",
				Map.of("projectId", projectId.toString()))) {
			addNode(builder, record, "s");
		}
		for (Record record : graph.read(
				"""
				MATCH (a:Student)-[r:REVIEWED {sprintId: $sprintId, projectId: $projectId}]->(b:Student)
				RETURN a, r, b
				""",
				Map.of("projectId", projectId.toString(), "sprintId", sprintId.toString()))) {
			addNode(builder, record, "a");
			addNode(builder, record, "b");
			Relationship rel = record.get("r").asRelationship();
			Node a = record.get("a").asNode();
			Node b = record.get("b").asNode();
			Integer stars = rel.containsKey("stars") ? rel.get("stars").asInt() : null;
			builder.edge(edgeData(
					SagaGraphIds.reviewedEdge(a.get("id").asString(), b.get("id").asString(), sprintId),
					a.get("id").asString(),
					b.get("id").asString(),
					"REVIEWED",
					stars,
					null));
		}
		return builder.build();
	}

	private static void addRel(CytoscapeGraphBuilder builder, Value from, Value to, String type) {
		if (from == null || to == null || from.isNull() || to.isNull()) {
			return;
		}
		Node source = from.asNode();
		Node target = to.asNode();
		builder.edge(edgeData(
				SagaGraphIds.edge(type, source.get("id").asString(), target.get("id").asString()),
				source.get("id").asString(),
				target.get("id").asString(),
				type,
				null,
				null));
	}

	private static void addNode(CytoscapeGraphBuilder builder, Record record, String key) {
		Value value = record.get(key);
		if (value == null || value.isNull()) {
			return;
		}
		builder.node(toNode(value.asNode()));
	}

	private static com.saga.be.dto.graph.CytoscapeNodeData toNode(Node node) {
		String type = node.hasLabel("Student")
				? "STUDENT"
				: node.hasLabel("Team")
						? "TEAM"
						: node.hasLabel("Project")
								? "PROJECT"
								: node.hasLabel("Sprint")
										? "SPRINT"
										: node.hasLabel("Task")
												? "TASK"
												: node.hasLabel("Commit")
														? "COMMIT"
														: node.hasLabel("Criterion")
																? "CRITERION"
																: node.hasLabel("Identity") ? "IDENTITY" : "UNKNOWN";
		String label = switch (type) {
			case "COMMIT" -> shortSha(str(node, "sha"));
			case "TASK" -> firstNonBlank(str(node, "key"), str(node, "title"));
			case "CRITERION" -> firstNonBlank(str(node, "name"), str(node, "label"));
			case "IDENTITY" -> firstNonBlank(str(node, "username"), "unmapped");
			default -> firstNonBlank(str(node, "name"), str(node, "title"), str(node, "label"), str(node, "id"));
		};
		String subLabel = switch (type) {
			case "TASK" -> str(node, "title");
			case "COMMIT" -> str(node, "message");
			case "STUDENT" -> str(node, "studentCode");
			case "CRITERION" -> str(node, "label");
			default -> null;
		};
		Boolean anomaly = node.containsKey("isAnomaly") && !node.get("isAnomaly").isNull()
				? node.get("isAnomaly").asBoolean()
				: null;
		Integer storyPoint = node.containsKey("storyPoint") && !node.get("storyPoint").isNull()
				? node.get("storyPoint").asInt()
				: null;
		return nodeData(
				node.get("id").asString(),
				label,
				subLabel,
				type,
				str(node, "status"),
				str(node, "weightType"),
				anomaly,
				str(node, "avatar"),
				str(node, "role"),
				storyPoint);
	}

	private static String str(Node node, String key) {
		if (!node.containsKey(key) || node.get(key).isNull()) {
			return null;
		}
		return node.get(key).asString();
	}

	private static String shortSha(String sha) {
		if (sha == null) {
			return "commit";
		}
		return sha.length() <= 7 ? sha : sha.substring(0, 7);
	}

	private static String firstNonBlank(String... values) {
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				return value;
			}
		}
		return "";
	}
}

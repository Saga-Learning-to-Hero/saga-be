package com.saga.be.graph;

import com.saga.be.graph.ProjectGraphSnapshot.CommitNode;
import com.saga.be.graph.ProjectGraphSnapshot.ReviewEdge;
import com.saga.be.graph.ProjectGraphSnapshot.SprintNode;
import com.saga.be.graph.ProjectGraphSnapshot.StudentNode;
import com.saga.be.graph.ProjectGraphSnapshot.TaskCommitLink;
import com.saga.be.graph.ProjectGraphSnapshot.TaskNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class ProjectGraphWriter {

	private static final List<Map<String, String>> CRITERIA = List.of(
			Map.of("id", "crit_code", "name", "CODE", "label", "Lap trinh"),
			Map.of("id", "crit_test", "name", "TEST", "label", "Kiem thu"),
			Map.of("id", "crit_document", "name", "DOCUMENT", "label", "Tai lieu"),
			Map.of("id", "crit_research", "name", "RESEARCH", "label", "Nghien cuu"));

	private final SagaGraphClient graph;

	public ProjectGraphWriter(SagaGraphClient graph) {
		this.graph = graph;
	}

	public void rebuild(ProjectGraphSnapshot snapshot) {
		ensureSchema();
		UUID projectId = snapshot.projectId();
		String projectKey = SagaGraphIds.project(projectId);
		graph.write(tx -> {
			tx.run(
					"""
					MATCH ()-[r:REVIEWED {projectId: $projectId}]->()
					DELETE r
					""",
					Map.of("projectId", projectId.toString()));
			tx.run(
					"""
					MATCH (n)
					WHERE n.projectId = $projectId
					  AND (n:Project OR n:Team OR n:Sprint OR n:Task OR n:Commit OR n:Identity)
					DETACH DELETE n
					""",
					Map.of("projectId", projectId.toString()));
			tx.run(
					"""
					MERGE (p:Project {id: $id})
					SET p.name = $name, p.projectId = $projectId, p.sagaId = $sagaId
					""",
					Map.of(
							"id",
							projectKey,
							"name",
							nullToEmpty(snapshot.projectName()),
							"projectId",
							projectId.toString(),
							"sagaId",
							projectId.toString()));
			if (snapshot.team() != null) {
				tx.run(
						"""
						MATCH (p:Project {id: $projectId})
						MERGE (t:Team {id: $id})
						SET t.name = $name, t.projectId = $pid, t.sagaId = $sagaId
						MERGE (t)-[:OWNS]->(p)
						""",
						Map.of(
								"projectId",
								projectKey,
								"id",
								SagaGraphIds.team(snapshot.team().id()),
								"name",
								nullToEmpty(snapshot.team().name()),
								"pid",
								projectId.toString(),
								"sagaId",
								snapshot.team().id().toString()));
			}
			if (!snapshot.students().isEmpty() && snapshot.team() != null) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (StudentNode student : snapshot.students()) {
					Map<String, Object> row = new HashMap<>();
					row.put("id", SagaGraphIds.student(student.id()));
					row.put("name", nullToEmpty(student.name()));
					row.put("studentCode", student.studentCode());
					row.put("avatar", student.avatar());
					row.put("role", student.role());
					row.put("sagaId", student.id().toString());
					row.put("teamId", SagaGraphIds.team(snapshot.team().id()));
					rows.add(row);
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MERGE (s:Student {id: row.id})
						SET s.name = row.name, s.studentCode = row.studentCode, s.avatar = row.avatar, s.sagaId = row.sagaId, s.role = row.role
						WITH s, row
						MATCH (t:Team {id: row.teamId})
						MERGE (s)-[m:MEMBER_OF]->(t)
						SET m.role = row.role, m.projectId = $projectId
						""",
						Map.of("rows", rows, "projectId", projectId.toString()));
			}
			if (!snapshot.sprints().isEmpty()) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (SprintNode sprint : snapshot.sprints()) {
					Map<String, Object> row = new HashMap<>();
					row.put("id", SagaGraphIds.sprint(sprint.id()));
					row.put("name", nullToEmpty(sprint.name()));
					row.put("state", sprint.state());
					row.put("sagaId", sprint.id().toString());
					rows.add(row);
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MATCH (p:Project {id: $projectId})
						MERGE (sp:Sprint {id: row.id})
						SET sp.name = row.name, sp.state = row.state, sp.projectId = $pid, sp.sagaId = row.sagaId
						MERGE (p)-[:HAS_SPRINT]->(sp)
						""",
						Map.of("rows", rows, "projectId", projectKey, "pid", projectId.toString()));
			}
			if (!snapshot.tasks().isEmpty()) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (TaskNode task : snapshot.tasks()) {
					Map<String, Object> row = new HashMap<>();
					row.put("id", SagaGraphIds.task(task.id()));
					row.put("key", task.key());
					row.put("title", nullToEmpty(task.title()));
					row.put("status", task.status());
					row.put("storyPoint", task.storyPoint());
					row.put("weightType", task.weightType());
					row.put("classified", task.classified());
					row.put("isAnomaly", task.anomaly());
					row.put("linkedCommitCount", task.linkedCommitCount());
					row.put("sagaId", task.id().toString());
					row.put("sprintId", task.sprintId() == null ? null : SagaGraphIds.sprint(task.sprintId()));
					row.put(
							"assigneeId",
							task.assigneeStudentId() == null ? null : SagaGraphIds.student(task.assigneeStudentId()));
					rows.add(row);
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MERGE (t:Task {id: row.id})
						SET t.key = row.key, t.title = row.title, t.status = row.status,
						    t.storyPoint = row.storyPoint, t.weightType = row.weightType,
						    t.classified = row.classified, t.isAnomaly = row.isAnomaly,
						    t.linkedCommitCount = row.linkedCommitCount,
						    t.projectId = $pid, t.sagaId = row.sagaId
						WITH t, row
						FOREACH (_ IN CASE WHEN row.sprintId IS NULL THEN [] ELSE [1] END |
						  MERGE (sp:Sprint {id: row.sprintId})
						  MERGE (sp)-[:CONTAINS]->(t)
						)
						FOREACH (_ IN CASE WHEN row.assigneeId IS NULL THEN [] ELSE [1] END |
						  MERGE (s:Student {id: row.assigneeId})
						  MERGE (s)-[:ASSIGNED_TO]->(t)
						)
						FOREACH (_ IN CASE WHEN row.classified = true THEN [1] ELSE [] END |
						  MERGE (c:Criterion {id:
						    CASE row.weightType
						      WHEN 'CODE' THEN 'crit_code'
						      WHEN 'TEST' THEN 'crit_test'
						      WHEN 'DOCUMENT' THEN 'crit_document'
						      WHEN 'RESEARCH' THEN 'crit_research'
						    END})
						  MERGE (t)-[:CLASSIFIED_AS]->(c)
						)
						""",
						Map.of("rows", rows, "pid", projectId.toString()));
			}
			if (!snapshot.commits().isEmpty()) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (CommitNode commit : snapshot.commits()) {
					Map<String, Object> row = new HashMap<>();
					row.put("id", SagaGraphIds.commit(commit.id()));
					row.put("sha", commit.sha());
					row.put("message", truncate(commit.message(), 200));
					row.put("unmapped", commit.unmapped());
					row.put("sagaId", commit.id().toString());
					row.put(
							"identityId",
							SagaGraphIds.identity(projectId, "GITHUB", commit.authorSubject()));
					row.put("username", commit.authorSubject());
					row.put(
							"studentId",
							commit.authorStudentId() == null ? null : SagaGraphIds.student(commit.authorStudentId()));
					rows.add(row);
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MERGE (c:Commit {id: row.id})
						SET c.sha = row.sha, c.message = row.message, c.isAnomaly = row.unmapped,
						    c.projectId = $pid, c.sagaId = row.sagaId
						MERGE (i:Identity {id: row.identityId})
						SET i.provider = 'GITHUB', i.username = row.username, i.projectId = $pid,
						    i.isAnomaly = row.unmapped
						MERGE (c)-[:AUTHORED_BY]->(i)
						FOREACH (_ IN CASE WHEN row.studentId IS NULL THEN [] ELSE [1] END |
						  MERGE (s:Student {id: row.studentId})
						  MERGE (i)-[:MAPS_TO]->(s)
						)
						""",
						Map.of("rows", rows, "pid", projectId.toString()));
			}
			if (!snapshot.links().isEmpty()) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (TaskCommitLink link : snapshot.links()) {
					rows.add(Map.of(
							"taskId",
							SagaGraphIds.task(link.taskId()),
							"commitId",
							SagaGraphIds.commit(link.commitId())));
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MATCH (t:Task {id: row.taskId})
						MATCH (c:Commit {id: row.commitId})
						MERGE (t)-[:EVIDENCED_BY]->(c)
						""",
						Map.of("rows", rows));
			}
			if (!snapshot.reviews().isEmpty()) {
				List<Map<String, Object>> rows = new ArrayList<>();
				for (ReviewEdge review : snapshot.reviews()) {
					Map<String, Object> row = new HashMap<>();
					row.put("source", SagaGraphIds.student(review.reviewerStudentId()));
					row.put("target", SagaGraphIds.student(review.revieweeStudentId()));
					row.put("stars", review.stars());
					row.put("sprintId", review.sprintId().toString());
					rows.add(row);
				}
				tx.run(
						"""
						UNWIND $rows AS row
						MATCH (a:Student {id: row.source})
						MATCH (b:Student {id: row.target})
						MERGE (a)-[r:REVIEWED {sprintId: row.sprintId}]->(b)
						SET r.stars = row.stars, r.projectId = $projectId
						""",
						Map.of("rows", rows, "projectId", projectId.toString()));
			}
		});
	}

	private void ensureSchema() {
		List<String> constraints = List.of(
				"CREATE CONSTRAINT student_id IF NOT EXISTS FOR (n:Student) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT team_id IF NOT EXISTS FOR (n:Team) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT project_id IF NOT EXISTS FOR (n:Project) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT sprint_id IF NOT EXISTS FOR (n:Sprint) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT task_id IF NOT EXISTS FOR (n:Task) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT commit_id IF NOT EXISTS FOR (n:Commit) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT identity_id IF NOT EXISTS FOR (n:Identity) REQUIRE n.id IS UNIQUE",
				"CREATE CONSTRAINT criterion_id IF NOT EXISTS FOR (n:Criterion) REQUIRE n.id IS UNIQUE");
		for (String cypher : constraints) {
			graph.write(tx -> tx.run(cypher));
		}
		graph.write(tx -> tx.run(
				"""
				UNWIND $rows AS row
				MERGE (c:Criterion {id: row.id})
				SET c.name = row.name, c.label = row.label
				""",
				Map.of("rows", CRITERIA)));
		List<String> indexes = List.of(
				"CREATE INDEX team_projectId IF NOT EXISTS FOR (n:Team) ON (n.projectId)",
				"CREATE INDEX sprint_projectId IF NOT EXISTS FOR (n:Sprint) ON (n.projectId)",
				"CREATE INDEX task_projectId IF NOT EXISTS FOR (n:Task) ON (n.projectId)",
				"CREATE INDEX commit_projectId IF NOT EXISTS FOR (n:Commit) ON (n.projectId)",
				"CREATE INDEX identity_projectId IF NOT EXISTS FOR (n:Identity) ON (n.projectId)");
		for (String cypher : indexes) {
			graph.write(tx -> tx.run(cypher));
		}
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max);
	}
}

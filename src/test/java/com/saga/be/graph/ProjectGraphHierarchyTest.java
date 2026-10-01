package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.graph.ProjectGraphSnapshot.TaskHierarchyLink;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** EPIC -> task -> subtask decomposition as graph edges, and the issue type on task nodes. */
class ProjectGraphHierarchyTest {

	private final JiraIntegration siteA = site();
	private final JiraIntegration siteB = site();

	@Test
	void eachTaskIsLinkedToItsJiraParentInTheSameSource() {
		Task epic = task(siteA, "100", null);
		Task story = task(siteA, "101", "100");
		Task subtask = task(siteA, "102", "101");
		Task loose = task(siteA, "103", null);

		assertThat(ProjectGraphLoader.hierarchyLinks(List.of(subtask, story, epic, loose)))
				.containsExactlyInAnyOrder(
						new TaskHierarchyLink(epic.getId(), story.getId()),
						new TaskHierarchyLink(story.getId(), subtask.getId()));
	}

	@Test
	void aParentOutsideTheProjectOrInAnotherJiraSourceGivesNoLink() {
		// Jira issue ids repeat across sites: "100" on site B is not the parent of a site-A task.
		Task epicOnB = task(siteB, "100", null);
		Task childOnA = task(siteA, "101", "100");
		Task orphan = task(siteA, "102", "999");

		assertThat(ProjectGraphLoader.hierarchyLinks(List.of(epicOnB, childOnA, orphan))).isEmpty();
	}

	@Test
	void malformedRowsAreSkipped() {
		Task self = task(siteA, "100", "100");
		Task noSource = task(null, "101", "100");
		Task blankParent = task(siteA, "102", " ");

		assertThat(ProjectGraphLoader.hierarchyLinks(List.of(self, noSource, blankParent))).isEmpty();
	}

	@Test
	void parentResolutionTellsResolvedUnresolvedAndTopLevelApart() {
		Task epic = task(siteA, "100", null);
		Task story = task(siteA, "101", "100");
		Task orphan = task(siteA, "102", "999");
		java.util.Map<String, UUID> index = java.util.Map.of(
				com.saga.be.service.projection.JiraParentResolution.key(siteA.getId(), "100"), epic.getId());

		assertThat(ProjectGraphLoader.resolveParent(epic, index)).isNull();
		assertThat(ProjectGraphLoader.resolveParent(story, index))
				.isEqualTo(new com.saga.be.service.projection.JiraParentResolution.Result(epic.getId(), "RESOLVED", null));
		assertThat(ProjectGraphLoader.resolveParent(orphan, index))
				.isEqualTo(new com.saga.be.service.projection.JiraParentResolution.Result(null, "UNRESOLVED", "PARENT_NOT_SYNCED"));
	}

	@Test
	void aRevokedSourceIsReportedAsTheReason() {
		siteA.setConnectionStatus(com.saga.be.entity.enums.IntegrationStatus.REVOKED);
		Task orphan = task(siteA, "102", "999");

		assertThat(ProjectGraphLoader.resolveParent(orphan, java.util.Map.of()).reason()).isEqualTo("PARENT_SOURCE_REVOKED");
	}

	@Test
	void theDecompositionEdgeCanBeFilteredOn() {
		GraphViewQuery query = GraphViewQuery.parse(null, 1, null, "PARENT_OF,HAS_WORK_ITEM,EVIDENCED_BY", null, null, null);

		assertThat(query.edgeTypes()).containsExactlyInAnyOrder("PARENT_OF", "HAS_WORK_ITEM", "EVIDENCED_BY");
	}

	@Test
	void onlyTaskNodesCarryTheIssueTypeInTheJsonSentToTheFrontend() throws Exception {
		ObjectMapper json = new ObjectMapper();
		String taskNode = json.writeValueAsString(CytoscapeGraphBuilder.nodeData(
				"task_1", "SAGA-1", "Login", "TASK", "DONE", null, null, null, null, 3,
				new CytoscapeGraphBuilder.TaskInfo(
						"STORY", "User Story", "10001", "STANDARD", 0, "src-1", "10049", "SAGA-0", "RESOLVED", null)));
		String studentNode = json.writeValueAsString(
				CytoscapeGraphBuilder.nodeData("student_1", "Minh", null, "STUDENT", null, null, null, null, "LEADER", null));

		assertThat(taskNode)
				.contains("\"issueType\":\"STORY\"")
				.contains("\"issueTypeName\":\"User Story\"")
				.contains("\"issueTypeLevel\":\"STANDARD\"")
				.contains("\"jiraHierarchyLevel\":0")
				.contains("\"parentResolution\":\"RESOLVED\"")
				.doesNotContain("parentResolutionReason");
		assertThat(studentNode).doesNotContain("issueType");
	}

	private static JiraIntegration site() {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		return integration;
	}

	private static Task task(JiraIntegration source, String externalId, String parentExternalId) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setJiraIntegration(source);
		task.setExternalId(externalId);
		task.setParentExternalId(parentExternalId);
		return task;
	}
}

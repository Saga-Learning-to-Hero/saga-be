package com.saga.be.integration.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class JiraIssueEvidenceParserTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void readsIssueAndAttachmentsFromWebhook() throws Exception {
		JsonNode root = MAPPER.readTree(
				"""
				{"webhookEvent":"jira:issue_updated","issue":{"id":"10001","key":"SAGA-7","fields":{
				  "summary":"Write spec","status":{"id":"3","name":"Done","statusCategory":{"key":"done","name":"Done"}},
				  "project":{"id":"10000","key":"SAGA"},
				  "issuetype":{"name":"Task"},
				  "labels":["saga:document"],
				  "attachment":[{"id":"55","filename":"spec.pdf","mimeType":"application/pdf","size":1200,"author":{"accountId":"abc"}}]
				}}}
				""");
		JiraIssueEvidenceParser.IssueRef ref = JiraIssueEvidenceParser.fromWebhook(root).orElseThrow();
		assertEquals("10001", ref.issueId());
		assertEquals("SAGA-7", ref.issueKey());
		assertEquals("10000", ref.projectId());
		List<JiraIssueEvidenceParser.Attachment> files = JiraIssueEvidenceParser.attachments(root.path("issue"));
		assertEquals(1, files.size());
		assertEquals("55", files.getFirst().id());
		assertEquals("spec.pdf", files.getFirst().filename());
		assertEquals("done", JiraIssueEvidenceParser.statusCategory(root.path("issue")));
	}

	@Test
	void readsTheIssueTypeIdentityAndHierarchyLevel() throws Exception {
		JsonNode epic = MAPPER.readTree(
				"""
				{"id":"1","fields":{"issuetype":{"id":"10000","name":"Epic","subtask":false,"hierarchyLevel":1}}}
				""");
		JsonNode bare = MAPPER.readTree("""
				{"id":"2","fields":{"issuetype":{"name":"Task"}}}
				""");

		assertEquals("10000", JiraIssueEvidenceParser.issueTypeId(epic));
		assertEquals(Boolean.FALSE, JiraIssueEvidenceParser.issueTypeSubtask(epic));
		assertEquals(1, JiraIssueEvidenceParser.issueTypeHierarchyLevel(epic));
		org.junit.jupiter.api.Assertions.assertNull(JiraIssueEvidenceParser.issueTypeId(bare));
		org.junit.jupiter.api.Assertions.assertNull(JiraIssueEvidenceParser.issueTypeSubtask(bare));
		org.junit.jupiter.api.Assertions.assertNull(JiraIssueEvidenceParser.issueTypeHierarchyLevel(bare));
	}

	@Test
	void readsRemoteLinks() throws Exception {
		JsonNode node = MAPPER.readTree(
				"""
				[{"id":42,"object":{"url":"https://docs.google.com/document/d/abc","title":"Spec"}}]
				""");
		List<JiraIssueEvidenceParser.RemoteLink> links = JiraIssueEvidenceParser.remoteLinks(node);
		assertEquals(1, links.size());
		assertEquals("42", links.getFirst().id());
		assertTrue(links.getFirst().url().startsWith("https://docs.google.com"));
		assertEquals("Spec", links.getFirst().title());
	}

	@Test
	void skipsRemoteLinksWithoutUrl() throws Exception {
		JsonNode node = MAPPER.readTree("[{\"id\":1,\"object\":{\"title\":\"No url\"}}]");
		assertTrue(JiraIssueEvidenceParser.remoteLinks(node).isEmpty());
	}
}

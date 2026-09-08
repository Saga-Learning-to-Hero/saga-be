package com.saga.be.integration.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSearchPage;
import com.saga.be.integration.jira.JiraOAuthClient.JiraBoardResponse;
import com.saga.be.integration.jira.JiraOAuthClient.JiraProjectOption;
import com.saga.be.integration.jira.JiraOAuthClient.JiraProjectResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class JiraOAuthClientTest {

	private MockRestServiceServer server;
	private JiraOAuthClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new JiraOAuthClient(builder.build(), new IntegrationProperties());
	}

	@Test
	void getProjectParsesIdKeyAndName() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/project/SAGA"))
				.andRespond(withSuccess(
						"""
						{"id":"10000","key":"SAGA","name":"SAGA Learning","style":"classic"}
						""",
						MediaType.APPLICATION_JSON));

		JiraProjectResponse project = client.getProject("token", "cloud-1", "SAGA");
		assertEquals("10000", project.id());
		assertEquals("SAGA", project.key());
		assertEquals("SAGA Learning", project.name());
		server.verify();
	}

	@Test
	void getBoardParsesId() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-1/rest/agile/1.0/board/12"))
				.andRespond(withSuccess(
						"""
						{"id":12,"name":"SAGA Board","type":"scrum"}
						""",
						MediaType.APPLICATION_JSON));

		JiraBoardResponse board = client.getBoard("token", "cloud-1", "12");
		assertEquals("12", board.id());
		assertEquals("SAGA Board", board.name());
		server.verify();
	}

	@Test
	void listProjectsParsesSearchValues() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/project/search?maxResults=100"))
				.andRespond(withSuccess(
						"""
						{"values":[{"id":"10000","key":"SAGA","name":"SAGA Learning"}]}
						""",
						MediaType.APPLICATION_JSON));

		List<JiraProjectOption> projects = client.listProjects("token", "cloud-1");
		assertEquals(1, projects.size());
		assertEquals("10000", projects.getFirst().id());
		assertEquals("SAGA", projects.getFirst().key());
		server.verify();
	}

	@Test
	void searchIssues_usesEnhancedJqlEndpoint() {
		server.expect(request -> {
					assertEquals(HttpMethod.GET, request.getMethod());
					String uri = request.getURI().toString();
					assertTrue(uri.startsWith("https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/search/jql?"));
					assertTrue(uri.contains("jql="));
					assertTrue(uri.contains("maxResults=50"));
					assertTrue(uri.contains("fields="));
					assertFalse(uri.contains("/rest/api/3/search?"));
					assertFalse(uri.contains("startAt="));
				})
				.andRespond(withSuccess(
						"""
						{"issues":[{"id":"1","key":"SAGA-1","fields":{"summary":"A","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Task"},"assignee":null,"created":null,"updated":null}}],"nextPageToken":"tok-2","isLast":false}
						""",
						MediaType.APPLICATION_JSON));

		IssueSearchPage page = client.searchIssues("token", "cloud-1", "SAGA", null, 50);
		assertEquals(1, page.issues().size());
		assertEquals("SAGA-1", page.issues().getFirst().key());
		assertEquals("tok-2", page.nextPageToken());
		assertFalse(page.last());
		server.verify();
	}

	@Test
	void searchIssues_passesNextPageToken() {
		server.expect(request -> {
					String uri = request.getURI().toString();
					assertTrue(uri.contains("/rest/api/3/search/jql?"));
					assertTrue(uri.contains("nextPageToken=tok-2"));
				})
				.andRespond(withSuccess(
						"""
						{"issues":[{"id":"2","key":"SAGA-2","fields":{"summary":"B","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Task"}}}],"isLast":true}
						""",
						MediaType.APPLICATION_JSON));

		IssueSearchPage page = client.searchIssues("token", "cloud-1", "SAGA", "tok-2", 50);
		assertEquals(1, page.issues().size());
		assertTrue(page.last());
		server.verify();
	}

	@Test
	void searchIssues_goneMapsToSyncIncomplete() {
		server.expect(method(HttpMethod.GET))
				.andRespond(withStatus(HttpStatus.GONE)
						.body("""
								{"errorMessages":["The requested API has been removed."],"errors":{}}
								""")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> client.searchIssues("token", "cloud-1", "SAGA", null, 50));
		assertEquals(IntegrationErrorCode.JIRA_SYNC_INCOMPLETE, ex.getCode());
		server.verify();
	}

	@Test
	void searchIssues_forbiddenMapsToProjectNotAccessible() {
		server.expect(method(HttpMethod.GET))
				.andRespond(withStatus(HttpStatus.FORBIDDEN)
						.body("""
								{"errorMessages":["You do not have permission."],"errors":{}}
								""")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> client.searchIssues("token", "cloud-1", "SAGA", null, 50));
		assertEquals(IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, ex.getCode());
		server.verify();
	}

	@Test
	void getProject_unauthorizedMapsDistinctly() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/project/SAGA"))
				.andRespond(withStatus(HttpStatus.UNAUTHORIZED));

		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> client.getProject("token", "cloud-1", "SAGA"));
		assertEquals(IntegrationErrorCode.JIRA_UNAUTHORIZED, ex.getCode());
		server.verify();
	}

	@Test
	void jiraClientDoesNotTargetJackson2JsonNode() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/integration/jira/JiraOAuthClient.java"));
		assertFalse(source.contains("com.fasterxml.jackson.databind.JsonNode"));
		assertFalse(source.contains("body(JsonNode.class)"));
	}
}

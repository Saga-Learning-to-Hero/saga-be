package com.saga.be.integration.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.integration.jira.JiraOAuthClient.JiraBoardResponse;
import com.saga.be.integration.jira.JiraOAuthClient.JiraProjectOption;
import com.saga.be.integration.jira.JiraOAuthClient.JiraProjectResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
	void jiraClientDoesNotTargetJackson2JsonNode() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/integration/jira/JiraOAuthClient.java"));
		assertFalse(source.contains("com.fasterxml.jackson.databind.JsonNode"));
		assertFalse(source.contains("body(JsonNode.class)"));
	}
}

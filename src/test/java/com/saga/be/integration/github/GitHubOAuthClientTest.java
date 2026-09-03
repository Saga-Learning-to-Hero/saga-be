package com.saga.be.integration.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.integration.github.GitHubOAuthClient.GitHubInstallationResponse;
import com.saga.be.integration.github.GitHubOAuthClient.RepoSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class GitHubOAuthClientTest {

	private MockRestServiceServer server;
	private GitHubOAuthClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		IntegrationProperties properties = new IntegrationProperties();
		properties.getGithub().setClientId("Iv1.test");
		properties.getGithub().setClientSecret("test-secret");
		properties.getGithub().setAppSlug("saga");
		client = new GitHubOAuthClient(builder.build(), properties);
	}

	@Test
	void getInstallationParsesGithubShapedJson() {
		server.expect(requestTo("https://api.github.com/app/installations/158866076"))
				.andExpect(method(HttpMethod.GET))
				.andRespond(withSuccess(
						"""
						{
						  "id": 158866076,
						  "app_id": 123456,
						  "account": { "login": "Saga-Learning-to-Hero", "id": 1, "type": "Organization" },
						  "html_url": "https://github.com/settings/installations/158866076",
						  "repository_selection": "selected",
						  "permissions": { "contents": "read" }
						}
						""",
						MediaType.APPLICATION_JSON));

		GitHubInstallationResponse installation = client.getInstallation("app-jwt", 158866076L);

		assertEquals(158866076L, installation.id());
		assertEquals(123456L, installation.appId());
		assertEquals("Saga-Learning-to-Hero", installation.account().login());
		assertEquals("Organization", installation.account().type());
		assertEquals("https://github.com/settings/installations/158866076", installation.htmlUrl());
		assertEquals("selected", installation.repositorySelection());
		server.verify();
	}

	@Test
	void listInstallationReposParsesAccessibleRepositories() {
		server.expect(requestTo("https://api.github.com/installation/repositories?per_page=100"))
				.andRespond(withSuccess(
						"""
						{
						  "total_count": 2,
						  "repositories": [
						    {
						      "id": 11,
						      "name": "saga-fe",
						      "full_name": "Saga-Learning-to-Hero/saga-fe",
						      "private": false,
						      "owner": { "login": "Saga-Learning-to-Hero" },
						      "default_branch": "main"
						    },
						    {
						      "id": 22,
						      "name": "saga-be",
						      "full_name": "Saga-Learning-to-Hero/saga-be",
						      "private": true,
						      "owner": { "login": "Saga-Learning-to-Hero" },
						      "default_branch": "develop"
						    }
						  ]
						}
						""",
						MediaType.APPLICATION_JSON));

		List<RepoSummary> repos = client.parseRepos(client.listInstallationRepos("installation-token"));

		assertEquals(2, repos.size());
		assertEquals(11L, repos.get(0).id());
		assertEquals("saga-fe", repos.get(0).name());
		assertEquals("Saga-Learning-to-Hero/saga-fe", repos.get(0).fullName());
		assertEquals("Saga-Learning-to-Hero", repos.get(0).owner());
		assertEquals("main", repos.get(0).defaultBranch());
		assertFalse(repos.get(0).privateRepo());
		assertEquals("Saga-Learning-to-Hero/saga-be", repos.get(1).fullName());
		assertEquals("develop", repos.get(1).defaultBranch());
		server.verify();
	}

	@Test
	void createInstallationTokenParsesTokenField() {
		server.expect(requestTo("https://api.github.com/app/installations/158866076/access_tokens"))
				.andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess(
						"""
						{"token":"ghs_test","expires_at":"2026-09-03T21:00:00Z"}
						""",
						MediaType.APPLICATION_JSON));

		String token = client.createInstallationToken("app-jwt", 158866076L);
		assertNotNull(token);
		assertFalse(token.isBlank());
		server.verify();
	}

	@Test
	void listUserInstallationsParsesInstallationIds() {
		server.expect(requestTo("https://api.github.com/user/installations"))
				.andRespond(withSuccess(
						"""
						{"total_count":1,"installations":[{"id":158866076,"app_id":123456}]}
						""",
						MediaType.APPLICATION_JSON));

		assertEquals(158866076L, client.listUserInstallations("user-token").installations().getFirst().id());
		server.verify();
	}

	@Test
	void githubClientDoesNotTargetJackson2JsonNode() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/integration/github/GitHubOAuthClient.java"));
		assertFalse(source.contains("com.fasterxml.jackson.databind.JsonNode"));
		assertFalse(source.contains("body(JsonNode.class)"));
	}
}

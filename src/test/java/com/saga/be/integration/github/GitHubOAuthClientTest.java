package com.saga.be.integration.github;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubOAuthClient.CommitDetail;
import com.saga.be.integration.github.GitHubOAuthClient.GitHubInstallationResponse;
import com.saga.be.integration.github.GitHubOAuthClient.RepoSummary;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.MockRestResponseCreators;
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

	@Test
	void listBranches_paginatesUntilShortPage() {
		String page1 = "["
				+ String.join(
						",",
						java.util.stream.IntStream.range(0, 100)
								.mapToObj(i -> "{\"name\":\"b" + i + "\"}")
								.toList())
				+ "]";
		server.expect(requestTo("https://api.github.com/repos/org/repo/branches?per_page=100&page=1"))
				.andRespond(withSuccess(page1, MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.github.com/repos/org/repo/branches?per_page=100&page=2"))
				.andRespond(withSuccess("[{\"name\":\"last\"}]", MediaType.APPLICATION_JSON));

		List<String> branches = client.listBranches("tok", "org", "repo");

		assertEquals(101, branches.size());
		assertEquals("b0", branches.getFirst());
		assertEquals("last", branches.getLast());
		server.verify();
	}

	@Test
	void listCommits_includesSinceWhenProvided() {
		server.expect(requestTo(
						"https://api.github.com/repos/org/repo/commits?per_page=100&page=1&sha=main&since=2026-09-01T00%3A00%3A00Z"))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		assertEquals(
				List.of(),
				client.listCommits(
						"tok",
						"org",
						"repo",
						"main",
						1,
						100,
						java.time.Instant.parse("2026-09-01T00:00:00Z")));
		server.verify();
	}

	@Test
	void listCommits_rateLimitMapsToTypedFailure() {
		server.expect(requestTo("https://api.github.com/repos/org/repo/commits?per_page=100&page=1&sha=main"))
				.andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withStatus(
								org.springframework.http.HttpStatus.FORBIDDEN)
						.body("{\"message\":\"API rate limit exceeded\"}")
						.contentType(MediaType.APPLICATION_JSON));

		com.saga.be.exception.IntegrationException ex = org.junit.jupiter.api.Assertions.assertThrows(
				com.saga.be.exception.IntegrationException.class,
				() -> client.listCommits("tok", "org", "repo", "main", 1, 100));
		assertEquals(com.saga.be.integration.IntegrationErrorCode.GITHUB_RATE_LIMITED, ex.getCode());
		server.verify();
	}

	@Test
	void getCommit_parsesFilesParentsAndNullPatch() {
		server.expect(requestTo(commitUrl(1)))
				.andExpect(method(HttpMethod.GET))
				.andExpect(header("Accept", "application/vnd.github+json"))
				.andRespond(withSuccess(commitJson(List.of(
						fileJson("src/a.java", "modified", 3, 1, 4, "@@ -1 +1 @@"),
						fileJson("src/new.java", "added", 10, 0, 10, "@@ +10 @@"),
						fileJson("old.txt", "removed", 0, 2, 2, "@@ -2 @@"),
						"""
						{"filename":"b.txt","previous_filename":"a.txt","status":"renamed","additions":0,"deletions":0,"changes":0,"patch":null}
						""",
						"""
						{"filename":"pic.png","status":"added","additions":0,"deletions":0,"changes":0}
						"""), 2), MediaType.APPLICATION_JSON));

		CommitDetail detail = client.getCommit("tok", "org", "repo", SHA);

		assertEquals(SHA, detail.sha());
		assertEquals("https://github.com/org/repo/commit/" + SHA, detail.htmlUrl());
		assertEquals("fix: SAGA-1\n\nbody", detail.message());
		assertEquals("Ada Lovelace", detail.authorName());
		assertEquals("ada", detail.authorLogin());
		assertEquals("2026-09-01T10:00:00Z", detail.committedAt());
		assertEquals(12, detail.stats().additions());
		assertEquals(3, detail.stats().deletions());
		assertEquals(15, detail.stats().total());
		assertEquals(List.of("parent-one", "parent-two"), detail.parents().stream().map(GitHubOAuthClient.CommitParent::sha).toList());
		assertEquals(5, detail.files().size());
		assertFalse(detail.filesTruncated());
		assertEquals("b.txt", detail.files().get(3).filename());
		assertEquals("a.txt", detail.files().get(3).previousFilename());
		assertEquals("renamed", detail.files().get(3).status());
		org.junit.jupiter.api.Assertions.assertNull(detail.files().get(3).patch());
		org.junit.jupiter.api.Assertions.assertNull(detail.files().get(4).patch());
		assertFalse(detail.toString().contains("ada@example.com"));
		server.verify();
	}

	@Test
	void getCommit_oneFullPageWithoutNextDoesNotFetchFurther() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(withSuccess(commitJson(fileRange(0, 100), 1), MediaType.APPLICATION_JSON));

		CommitDetail detail = client.getCommit("tok", "org", "repo", SHA);

		assertEquals(100, detail.files().size());
		assertFalse(detail.filesTruncated());
		server.verify();
	}

	@Test
	void getCommit_paginatesFilesAndDedupes() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(withSuccess(commitJson(fileRange(0, 100), 1), MediaType.APPLICATION_JSON).headers(nextLink(2)));
		server.expect(requestTo(commitUrl(2)))
				.andRespond(withSuccess(commitJson(concat(fileRange(0, 5), fileRange(100, 120)), 1), MediaType.APPLICATION_JSON));

		CommitDetail detail = client.getCommit("tok", "org", "repo", SHA);

		assertEquals(120, detail.files().size());
		assertFalse(detail.filesTruncated());
		assertEquals("f0.txt", detail.files().getFirst().filename());
		assertEquals("f119.txt", detail.files().getLast().filename());
		server.verify();
	}

	@Test
	void getCommit_threeFullPagesWithoutFurtherLinkAreNotTruncated() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(withSuccess(commitJson(fileRange(0, 100), 1), MediaType.APPLICATION_JSON).headers(nextLink(2)));
		server.expect(requestTo(commitUrl(2)))
				.andRespond(withSuccess(commitJson(fileRange(100, 200), 1), MediaType.APPLICATION_JSON).headers(nextLink(3)));
		server.expect(requestTo(commitUrl(3)))
				.andRespond(withSuccess(commitJson(fileRange(200, 300), 1), MediaType.APPLICATION_JSON));

		CommitDetail detail = client.getCommit("tok", "org", "repo", SHA);

		assertEquals(300, detail.files().size());
		assertFalse(detail.filesTruncated());
		server.verify();
	}

	@Test
	void getCommit_capsAt300AndMarksTruncatedWhenMorePagesExist() {
		HttpHeaders next = new HttpHeaders();
		next.add(
				HttpHeaders.LINK,
				"<https://api.github.com/repos/org/repo/commits/" + SHA + "?page=4&per_page=100>; rel=\"next\"");
		server.expect(requestTo(commitUrl(1)))
				.andRespond(withSuccess(commitJson(fileRange(0, 100), 1), MediaType.APPLICATION_JSON).headers(next));
		server.expect(requestTo(commitUrl(2)))
				.andRespond(withSuccess(commitJson(fileRange(100, 200), 1), MediaType.APPLICATION_JSON).headers(next));
		server.expect(requestTo(commitUrl(3)))
				.andRespond(withSuccess(commitJson(fileRange(200, 300), 1), MediaType.APPLICATION_JSON).headers(next));

		CommitDetail detail = client.getCommit("tok", "org", "repo", SHA);

		assertEquals(300, detail.files().size());
		org.junit.jupiter.api.Assertions.assertTrue(detail.filesTruncated());
		server.verify();
	}

	@Test
	void getCommit_404MapsToUnavailableWithoutLeakingBody() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.NOT_FOUND)
						.body("{\"message\":\"Not Found\",\"documentation_url\":\"https://docs.github.com\"}")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException ex = org.junit.jupiter.api.Assertions.assertThrows(
				IntegrationException.class, () -> client.getCommit("tok", "org", "repo", SHA));
		assertEquals(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex.getCode());
		assertFalse(ex.getMessage().contains("documentation_url"));
		assertFalse(ex.getMessage().contains("Not Found"));
		server.verify();
	}

	@Test
	void getCommit_403MapsToRateLimitedWithoutLeakingBody() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.FORBIDDEN)
						.body("{\"message\":\"API rate limit exceeded\"}")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException forbidden = org.junit.jupiter.api.Assertions.assertThrows(
				IntegrationException.class, () -> client.getCommit("tok", "org", "repo", SHA));
		assertEquals(IntegrationErrorCode.GITHUB_RATE_LIMITED, forbidden.getCode());
		assertFalse(forbidden.getMessage().contains("API rate limit exceeded"));
		server.verify();
	}

	@Test
	void getCommit_429MapsToRateLimited() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.TOO_MANY_REQUESTS)
						.body("{\"message\":\"slow down\"}")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException throttled = org.junit.jupiter.api.Assertions.assertThrows(
				IntegrationException.class, () -> client.getCommit("tok", "org", "repo", SHA));
		assertEquals(IntegrationErrorCode.GITHUB_RATE_LIMITED, throttled.getCode());
		server.verify();
	}

	@Test
	void getCommit_5xxMapsToUnavailable() {
		server.expect(requestTo(commitUrl(1)))
				.andRespond(MockRestResponseCreators.withStatus(HttpStatus.BAD_GATEWAY)
						.body("upstream token=ghs_leak")
						.contentType(MediaType.APPLICATION_JSON));

		IntegrationException ex = org.junit.jupiter.api.Assertions.assertThrows(
				IntegrationException.class, () -> client.getCommit("tok", "org", "repo", SHA));
		assertEquals(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex.getCode());
		assertFalse(ex.getMessage().contains("ghs_leak"));
		server.verify();
	}

	private static final String SHA = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	private static String commitUrl(int page) {
		return "https://api.github.com/repos/org/repo/commits/" + SHA + "?per_page=100&page=" + page;
	}

	private static HttpHeaders nextLink(int nextPage) {
		HttpHeaders headers = new HttpHeaders();
		headers.add(
				HttpHeaders.LINK,
				"<https://api.github.com/repos/org/repo/commits/"
						+ SHA
						+ "?page="
						+ nextPage
						+ "&per_page=100>; rel=\"next\"");
		return headers;
	}

	private static String commitJson(List<String> files, int parentCount) {
		String parents = parentCount >= 2
				? "[{\"sha\":\"parent-one\"},{\"sha\":\"parent-two\"}]"
				: "[{\"sha\":\"parent-one\"}]";
		return """
				{
				  "sha":"%s",
				  "html_url":"https://github.com/org/repo/commit/%s",
				  "commit":{
				    "message":"fix: SAGA-1\\n\\nbody",
				    "author":{"name":"Ada Lovelace","email":"ada@example.com","date":"2026-09-01T10:00:00Z"},
				    "committer":{"name":"GitHub","email":"noreply@github.com","date":"2026-09-01T10:00:01Z"}
				  },
				  "author":{"id":1,"login":"ada"},
				  "parents":%s,
				  "stats":{"total":15,"additions":12,"deletions":3},
				  "files":[%s]
				}
				"""
				.formatted(SHA, SHA, parents, String.join(",", files));
	}

	private static String fileJson(String name, String status, int additions, int deletions, int changes, String patch) {
		return "{\"filename\":\"%s\",\"status\":\"%s\",\"additions\":%d,\"deletions\":%d,\"changes\":%d,\"patch\":%s}"
				.formatted(name, status, additions, deletions, changes, patch == null ? "null" : "\"" + patch + "\"");
	}

	private static List<String> fileRange(int fromInclusive, int toExclusive) {
		return IntStream.range(fromInclusive, toExclusive)
				.mapToObj(i -> fileJson("f" + i + ".txt", "modified", 1, 0, 1, "@@"))
				.collect(Collectors.toList());
	}

	private static List<String> concat(List<String> first, List<String> second) {
		java.util.ArrayList<String> all = new java.util.ArrayList<>(first);
		all.addAll(second);
		return all;
	}
}

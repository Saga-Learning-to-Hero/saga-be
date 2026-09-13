package com.saga.be.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Audit fix: JiraCloudWorkClient.searchIssues used to call the legacy {@code
 * GET .../rest/api/3/search} endpoint, which Atlassian removed (HTTP 410) -- the exact cause of
 * "jira initial evidence sync failed ... Jira issue search failed" in production. Migrated to the
 * same {@code GET .../rest/api/3/search/jql} endpoint {@link JiraOAuthClient#searchIssues} already
 * uses, which paginates by an opaque {@code nextPageToken} cursor instead of a numeric offset.
 */
class JiraCloudWorkClientTest {

	private MockRestServiceServer server;
	private JiraCloudWorkClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new JiraCloudWorkClient(builder.build(), new ObjectMapper());
	}

	@Test
	void searchIssues_usesSupportedSearchJqlEndpoint_notRemovedLegacyEndpoint() {
		server.expect(request -> {
					String uri = request.getURI().toString();
					assertThat(uri)
							.as("must use the supported enhanced JQL endpoint, not the removed legacy one")
							.contains("/rest/api/3/search/jql")
							.doesNotContain("/rest/api/3/search?")
							.doesNotContain("startAt=");
				})
				.andRespond(withSuccess("""
						{"issues":[],"isLast":true}
						""", MediaType.APPLICATION_JSON));

		client.searchIssues("token", "cloud-1", "SAGA", null, 50);
		server.verify();
	}

	@Test
	void searchIssues_firstPage_omitsNextPageTokenParam() {
		server.expect(request -> assertThat(request.getURI().toString()).doesNotContain("nextPageToken="))
				.andRespond(withSuccess("""
						{"issues":[],"isLast":true}
						""", MediaType.APPLICATION_JSON));

		client.searchIssues("token", "cloud-1", "SAGA", null, 50);
		server.verify();
	}

	@Test
	void searchIssues_subsequentPage_passesNextPageTokenForward() {
		server.expect(request -> assertThat(request.getURI().toString()).contains("nextPageToken=tok-abc"))
				.andRespond(withSuccess("""
						{"issues":[],"isLast":true}
						""", MediaType.APPLICATION_JSON));

		client.searchIssues("token", "cloud-1", "SAGA", "tok-abc", 50);
		server.verify();
	}

	@Test
	void searchIssues_requestsEvidenceFieldsAndJql() {
		server.expect(request -> {
					String uri = request.getURI().toString();
					assertThat(uri).contains("fields=");
					String fields = java.net.URLDecoder.decode(
							uri.substring(uri.indexOf("fields=") + "fields=".length()).split("&")[0],
							java.nio.charset.StandardCharsets.UTF_8);
					assertThat(fields).contains("attachment", "summary", "status", "labels", "issuetype", "project", "description");
					assertThat(uri).contains("jql=");
				})
				.andRespond(withSuccess("""
						{"issues":[],"isLast":true}
						""", MediaType.APPLICATION_JSON));

		client.searchIssues("token", "cloud-1", "SAGA", null, 50);
		server.verify();
	}

	@Test
	void searchIssues_parsesIssuesAndPaginationMetadata() {
		server.expect(anything())
				.andRespond(withSuccess(
						"""
						{"issues":[{"id":"10001","key":"SAGA-1"},{"id":"10002","key":"SAGA-2"}],"nextPageToken":"tok-next","isLast":false}
						""",
						MediaType.APPLICATION_JSON));

		JiraCloudWorkClient.EvidenceSearchPage page = client.searchIssues("token", "cloud-1", "SAGA", null, 50);

		assertThat(page.issues()).hasSize(2);
		assertThat(page.issues().get(0).path("key").asText()).isEqualTo("SAGA-1");
		assertThat(page.nextPageToken()).isEqualTo("tok-next");
		assertThat(page.last()).isFalse();
		server.verify();
	}

	@Test
	void searchIssues_lastPage_noNextPageToken() {
		server.expect(anything())
				.andRespond(withSuccess(
						"""
						{"issues":[{"id":"10003","key":"SAGA-3"}],"isLast":true}
						""",
						MediaType.APPLICATION_JSON));

		JiraCloudWorkClient.EvidenceSearchPage page = client.searchIssues("token", "cloud-1", "SAGA", null, 50);

		assertThat(page.issues()).hasSize(1);
		assertThat(page.nextPageToken()).isNull();
		assertThat(page.last()).isTrue();
	}

	@Test
	void searchIssues_removedEndpointErrorStatus_mapsToControlledError() {
		// Simulates what the OLD /rest/api/3/search endpoint actually returns now: HTTP 410 Gone.
		server.expect(anything())
				.andRespond(withStatus(HttpStatus.GONE));

		assertThatThrownBy(() -> client.searchIssues("token", "cloud-1", "SAGA", null, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
	}
}

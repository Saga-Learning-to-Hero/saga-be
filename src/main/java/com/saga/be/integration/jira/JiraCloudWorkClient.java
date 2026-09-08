package com.saga.be.integration.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!test")
public class JiraCloudWorkClient {

	private final RestClient restClient;
	private final ObjectMapper mapper;

	public JiraCloudWorkClient(RestClient integrationRestClient, ObjectMapper mapper) {
		this.restClient = integrationRestClient;
		this.mapper = mapper;
	}

	public JsonNode getIssue(String accessToken, String cloudId, String issueIdOrKey) {
		try {
			String body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}?fields=attachment,summary,status,labels,issuetype,project,description",
							cloudId,
							issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);
			return mapper.readTree(body == null ? "{}" : body);
		} catch (RestClientResponseException ex) {
			throw unavailable("Jira issue is not accessible.");
		} catch (Exception ex) {
			throw unavailable("Jira issue could not be read.");
		}
	}

	public JsonNode listRemoteLinks(String accessToken, String cloudId, String issueIdOrKey) {
		try {
			String body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}/remotelink",
							cloudId,
							issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);
			return mapper.readTree(body == null ? "[]" : body);
		} catch (RestClientResponseException ex) {
			if (ex.getStatusCode().value() == 404) {
				return mapper.createArrayNode();
			}
			throw unavailable("Jira remote links are not accessible.");
		} catch (Exception ex) {
			throw unavailable("Jira remote links could not be read.");
		}
	}

	public byte[] downloadAttachment(String accessToken, String cloudId, String attachmentId) {
		try {
			byte[] body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/attachment/content/{id}",
							cloudId,
							attachmentId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(byte[].class);
			return body == null ? new byte[0] : body;
		} catch (RestClientResponseException ex) {
			throw unavailable("Jira attachment could not be downloaded.");
		}
	}

	public List<JsonNode> searchIssues(String accessToken, String cloudId, String projectKey, int startAt, int maxResults) {
		String jql = "project = \"" + projectKey.replace("\"", "") + "\" ORDER BY updated DESC";
		try {
			String body = restClient
					.get()
					.uri(uriBuilder -> uriBuilder
							.scheme("https")
							.host("api.atlassian.com")
							.path("/ex/jira/{cloudId}/rest/api/3/search")
							.queryParam("jql", jql)
							.queryParam(
									"fields", "attachment,summary,status,labels,issuetype,project,description")
							.queryParam("startAt", startAt)
							.queryParam("maxResults", maxResults)
							.build(cloudId))
					.header("Authorization", "Bearer " + accessToken)
					.accept(MediaType.APPLICATION_JSON)
					.retrieve()
					.body(String.class);
			JsonNode root = mapper.readTree(body == null ? "{}" : body);
			JsonNode issues = root.path("issues");
			List<JsonNode> out = new ArrayList<>();
			if (issues.isArray()) {
				issues.forEach(out::add);
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw unavailable("Jira issue search failed.");
		} catch (Exception ex) {
			throw unavailable("Jira issue search could not be read.");
		}
	}

	private static IntegrationException unavailable(String message) {
		return new IntegrationException(IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.BAD_GATEWAY, message);
	}
}

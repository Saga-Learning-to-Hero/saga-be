package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@Profile("!test")
public class JiraOAuthClient {

	private final RestClient restClient;
	private final IntegrationProperties properties;
	private final ObjectMapper mapper;

	public JiraOAuthClient(RestClient integrationRestClient, IntegrationProperties properties, ObjectMapper mapper) {
		this.restClient = integrationRestClient;
		this.properties = properties;
		this.mapper = mapper;
	}

	public String authorizationUrl(String state, String challenge, String redirectUri, boolean offline) {
		List<String> scopes = offline ? properties.getJira().teamScopes() : properties.getJira().identityScopes();
		String scope = scopes.stream().map(this::url).collect(Collectors.joining("%20"));
		return "https://auth.atlassian.com/authorize?audience=api.atlassian.com&client_id="
				+ url(properties.getJira().getClientId())
				+ "&scope="
				+ scope
				+ "&redirect_uri="
				+ url(redirectUri)
				+ "&state="
				+ url(state)
				+ "&response_type=code&prompt=consent&code_challenge="
				+ url(challenge)
				+ "&code_challenge_method=S256";
	}

	public TokenResponse exchange(String code, String verifier, String redirectUri) {
		String body = """
				{"grant_type":"authorization_code","client_id":"%s","client_secret":"%s","code":"%s","redirect_uri":"%s","code_verifier":"%s"}
				"""
				.formatted(
						escape(properties.getJira().getClientId()),
						escape(properties.getJira().getClientSecret()),
						escape(code),
						escape(redirectUri),
						escape(verifier));
		return postToken(body);
	}

	public TokenResponse refresh(String refreshToken) {
		String body = """
				{"grant_type":"refresh_token","client_id":"%s","client_secret":"%s","refresh_token":"%s"}
				"""
				.formatted(
						escape(properties.getJira().getClientId()),
						escape(properties.getJira().getClientSecret()),
						escape(refreshToken));
		return postToken(body);
	}

	public List<AccessibleResource> accessibleResources(String accessToken) {
		AccessibleResource[] resources = restClient
				.get()
				.uri("https://api.atlassian.com/oauth/token/accessible-resources")
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(AccessibleResource[].class);
		return resources == null ? List.of() : List.of(resources);
	}

	public Myself myself(String accessToken, String cloudId) {
		return restClient
				.get()
				.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/myself", cloudId)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(Myself.class);
	}

	public JsonNode getProject(String accessToken, String cloudId, String projectIdOrKey) {
		return restClient
				.get()
				.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/project/{projectId}", cloudId, projectIdOrKey)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(JsonNode.class);
	}

	public JsonNode getBoard(String accessToken, String cloudId, String boardId) {
		return restClient
				.get()
				.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board/{boardId}", cloudId, boardId)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(JsonNode.class);
	}

	public List<JiraProjectOption> listProjects(String accessToken, String cloudId) {
		JsonNode node = restClient
				.get()
				.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/project/search?maxResults=100", cloudId)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(JsonNode.class);
		java.util.ArrayList<JiraProjectOption> projects = new java.util.ArrayList<>();
		JsonNode values = node != null && node.has("values") ? node.get("values") : node;
		if (values != null && values.isArray()) {
			values.forEach(item -> projects.add(new JiraProjectOption(
					item.path("id").asText(), item.path("key").asText(), item.path("name").asText())));
		}
		return projects;
	}

	public List<JiraBoardOption> listBoards(String accessToken, String cloudId, String projectIdOrKey) {
		JsonNode node = restClient
				.get()
				.uri(
						"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board?projectKeyOrId={project}&maxResults=50",
						cloudId,
						projectIdOrKey)
				.header("Authorization", "Bearer " + accessToken)
				.retrieve()
				.body(JsonNode.class);
		java.util.ArrayList<JiraBoardOption> boards = new java.util.ArrayList<>();
		if (node != null && node.has("values") && node.get("values").isArray()) {
			node.get("values")
					.forEach(item -> boards.add(new JiraBoardOption(
							item.path("id").asText(), item.path("name").asText(), item.path("type").asText())));
		}
		return boards;
	}

	private TokenResponse postToken(String json) {
		try {
			String raw = restClient
					.post()
					.uri("https://auth.atlassian.com/oauth/token")
					.contentType(MediaType.APPLICATION_JSON)
					.body(json)
					.retrieve()
					.body(String.class);
			JsonNode node = mapper.readTree(raw);
			if (!node.hasNonNull("access_token")) {
				throw failed();
			}
			return new TokenResponse(
					node.path("access_token").asText(),
					node.path("refresh_token").asText(null),
					node.path("expires_in").asLong(3600),
					node.path("scope").asText(""));
		} catch (IntegrationException ex) {
			throw ex;
		} catch (Exception ex) {
			throw failed();
		}
	}

	private IntegrationException failed() {
		return new IntegrationException(
				IntegrationErrorCode.JIRA_TOKEN_REFRESH_FAILED, HttpStatus.BAD_GATEWAY, "Jira token exchange failed.");
	}

	private String url(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static String escape(String value) {
		return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record AccessibleResource(String id, String url, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Myself(String accountId, String displayName, String emailAddress, String avatarUrl) {}

	public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, String scope) {}

	public record JiraProjectOption(String id, String key, String name) {}

	public record JiraBoardOption(String id, String name, String type) {}
}

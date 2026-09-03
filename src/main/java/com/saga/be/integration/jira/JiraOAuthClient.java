package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!test")
public class JiraOAuthClient {

	private final RestClient restClient;
	private final IntegrationProperties properties;

	public JiraOAuthClient(RestClient integrationRestClient, IntegrationProperties properties) {
		this.restClient = integrationRestClient;
		this.properties = properties;
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

	public JiraProjectResponse getProject(String accessToken, String cloudId, String projectIdOrKey) {
		try {
			return restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/project/{projectId}", cloudId, projectIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JiraProjectResponse.class);
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira project is not accessible.");
		}
	}

	public JiraBoardResponse getBoard(String accessToken, String cloudId, String boardId) {
		try {
			return restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board/{boardId}", cloudId, boardId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JiraBoardResponse.class);
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_BOARD_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira board is not accessible.");
		}
	}

	public List<JiraProjectOption> listProjects(String accessToken, String cloudId) {
		try {
			JiraProjectSearchResponse node = restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/project/search?maxResults=100", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JiraProjectSearchResponse.class);
			if (node == null || node.values() == null) {
				return List.of();
			}
			return node.values().stream()
					.map(item -> new JiraProjectOption(item.id(), item.key(), item.name()))
					.toList();
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira project is not accessible.");
		}
	}

	public List<JiraBoardOption> listBoards(String accessToken, String cloudId, String projectIdOrKey) {
		try {
			JiraBoardSearchResponse node = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board?projectKeyOrId={project}&maxResults=50",
							cloudId,
							projectIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JiraBoardSearchResponse.class);
			if (node == null || node.values() == null) {
				return List.of();
			}
			return node.values().stream()
					.map(item -> new JiraBoardOption(item.id(), item.name(), item.type()))
					.toList();
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_BOARD_NOT_ACCESSIBLE, HttpStatus.FORBIDDEN, "Jira board is not accessible.");
		}
	}

	private TokenResponse postToken(String json) {
		try {
			JiraTokenApiResponse node = restClient
					.post()
					.uri("https://auth.atlassian.com/oauth/token")
					.contentType(MediaType.APPLICATION_JSON)
					.body(json)
					.retrieve()
					.body(JiraTokenApiResponse.class);
			if (node == null || node.accessToken() == null || node.accessToken().isBlank()) {
				throw failed();
			}
			return new TokenResponse(
					node.accessToken(),
					node.refreshToken(),
					node.expiresIn() == null ? 3600 : node.expiresIn(),
					node.scope() == null ? "" : node.scope());
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraTokenApiResponse(
			@JsonProperty("access_token") String accessToken,
			@JsonProperty("refresh_token") String refreshToken,
			@JsonProperty("expires_in") Long expiresIn,
			String scope) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraProjectResponse(String id, String key, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraBoardResponse(String id, String name, String type) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraProjectSearchResponse(List<JiraProjectResponse> values) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraBoardSearchResponse(List<JiraBoardResponse> values) {}

	public record TokenResponse(String accessToken, String refreshToken, long expiresInSeconds, String scope) {}

	public record JiraProjectOption(String id, String key, String name) {}

	public record JiraBoardOption(String id, String name, String type) {}
}

package com.saga.be.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
@Profile("!test")
public class GitHubOAuthClient {

	private final RestClient restClient;
	private final IntegrationProperties properties;
	private final ObjectMapper mapper;

	public GitHubOAuthClient(RestClient integrationRestClient, IntegrationProperties properties, ObjectMapper mapper) {
		this.restClient = integrationRestClient;
		this.properties = properties;
		this.mapper = mapper;
	}

	public String authorizationUrl(String state, String challenge, String redirectUri) {
		return "https://github.com/login/oauth/authorize?client_id="
				+ properties.getGithub().getClientId()
				+ "&redirect_uri="
				+ url(redirectUri)
				+ "&state="
				+ url(state)
				+ "&code_challenge="
				+ url(challenge)
				+ "&code_challenge_method=S256&prompt=select_account";
	}

	public String installationUrl(String state) {
		return "https://github.com/apps/" + properties.getGithub().getAppSlug() + "/installations/new?state=" + url(state);
	}

	public String exchangeUserToken(String code, String verifier, String redirectUri) {
		LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
		form.add("client_id", properties.getGithub().getClientId());
		form.add("client_secret", properties.getGithub().getClientSecret());
		form.add("code", code);
		form.add("redirect_uri", redirectUri);
		form.add("code_verifier", verifier);
		String body = restClient
				.post()
				.uri("https://github.com/login/oauth/access_token")
				.contentType(MediaType.APPLICATION_FORM_URLENCODED)
				.accept(MediaType.APPLICATION_JSON)
				.body(form)
				.retrieve()
				.body(String.class);
		try {
			JsonNode node = mapper.readTree(body);
			if (node.hasNonNull("access_token")) {
				return node.get("access_token").asText();
			}
		} catch (Exception ignored) {
			// fall through
		}
		throw new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "GitHub token exchange failed.");
	}

	public GitHubUser getAuthenticatedUser(String accessToken) {
		return restClient
				.get()
				.uri("https://api.github.com/user")
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(GitHubUser.class);
	}

	public JsonNode getInstallation(String appJwt, long installationId) {
		return restClient
				.get()
				.uri("https://api.github.com/app/installations/{id}", installationId)
				.header("Authorization", "Bearer " + appJwt)
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(JsonNode.class);
	}

	public JsonNode listInstallationRepos(String installationToken) {
		return restClient
				.get()
				.uri("https://api.github.com/installation/repositories?per_page=100")
				.header("Authorization", "Bearer " + installationToken)
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(JsonNode.class);
	}

	public JsonNode listUserInstallations(String userToken) {
		return restClient
				.get()
				.uri("https://api.github.com/user/installations")
				.header("Authorization", "Bearer " + userToken)
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(JsonNode.class);
	}

	public String createInstallationToken(String appJwt, long installationId) {
		JsonNode node = restClient
				.post()
				.uri("https://api.github.com/app/installations/{id}/access_tokens", installationId)
				.header("Authorization", "Bearer " + appJwt)
				.header("Accept", "application/vnd.github+json")
				.retrieve()
				.body(JsonNode.class);
		if (node == null || !node.hasNonNull("token")) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
					HttpStatus.BAD_GATEWAY,
					"GitHub installation token could not be created.");
		}
		return node.get("token").asText();
	}

	private static String url(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubUser(long id, String login, String name, String avatar_url) {}

	public record RepoSummary(long id, String name, String fullName, String owner, String defaultBranch, boolean privateRepo) {}

	public List<RepoSummary> parseRepos(JsonNode payload) {
		java.util.ArrayList<RepoSummary> repos = new java.util.ArrayList<>();
		if (payload == null) {
			return repos;
		}
		JsonNode array = payload.has("repositories") ? payload.get("repositories") : payload;
		if (array == null || !array.isArray()) {
			return repos;
		}
		array.forEach(node -> repos.add(new RepoSummary(
				node.path("id").asLong(),
				node.path("name").asText(),
				node.path("full_name").asText(),
				node.path("owner").path("login").asText(),
				node.path("default_branch").asText("main"),
				node.path("private").asBoolean(false))));
		return repos;
	}
}

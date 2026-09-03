package com.saga.be.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
@Profile("!test")
public class GitHubOAuthClient {

	private final RestClient restClient;
	private final IntegrationProperties properties;

	public GitHubOAuthClient(RestClient integrationRestClient, IntegrationProperties properties) {
		this.restClient = integrationRestClient;
		this.properties = properties;
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
		try {
			GitHubOAuthTokenResponse node = restClient
					.post()
					.uri("https://github.com/login/oauth/access_token")
					.contentType(MediaType.APPLICATION_FORM_URLENCODED)
					.accept(MediaType.APPLICATION_JSON)
					.body(form)
					.retrieve()
					.body(GitHubOAuthTokenResponse.class);
			if (node == null || node.accessToken() == null || node.accessToken().isBlank()) {
				throw tokenExchangeFailed();
			}
			return node.accessToken();
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw tokenExchangeFailed();
		}
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

	public GitHubInstallationResponse getInstallation(String appJwt, long installationId) {
		try {
			GitHubInstallationResponse body = restClient
					.get()
					.uri("https://api.github.com/app/installations/{id}", installationId)
					.header("Authorization", "Bearer " + appJwt)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubInstallationResponse.class);
			if (body == null || body.id() == null) {
				throw installationInvalid("GitHub installation could not be verified.");
			}
			return body;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw installationInvalid("GitHub installation could not be verified.");
		} catch (HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_GATEWAY,
					"GitHub installation response could not be parsed.");
		}
	}

	public GitHubInstallationReposResponse listInstallationRepos(String installationToken) {
		try {
			GitHubInstallationReposResponse body = restClient
					.get()
					.uri("https://api.github.com/installation/repositories?per_page=100")
					.header("Authorization", "Bearer " + installationToken)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubInstallationReposResponse.class);
			return body == null ? new GitHubInstallationReposResponse(List.of()) : body;
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_GATEWAY,
					"GitHub repositories could not be loaded.");
		}
	}

	public GitHubUserInstallationsResponse listUserInstallations(String userToken) {
		try {
			GitHubUserInstallationsResponse body = restClient
					.get()
					.uri("https://api.github.com/user/installations")
					.header("Authorization", "Bearer " + userToken)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubUserInstallationsResponse.class);
			return body == null ? new GitHubUserInstallationsResponse(List.of()) : body;
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_NOT_AUTHORIZED,
					HttpStatus.FORBIDDEN,
					"Current GitHub user is not authorized for this installation.");
		}
	}

	public String createInstallationToken(String appJwt, long installationId) {
		try {
			GitHubInstallationTokenResponse node = restClient
					.post()
					.uri("https://api.github.com/app/installations/{id}/access_tokens", installationId)
					.header("Authorization", "Bearer " + appJwt)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubInstallationTokenResponse.class);
			if (node == null || node.token() == null || node.token().isBlank()) {
				throw installationInvalid("GitHub installation token could not be created.");
			}
			return node.token();
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException | HttpMessageConversionException ex) {
			throw installationInvalid("GitHub installation token could not be created.");
		}
	}

	public List<RepoSummary> parseRepos(GitHubInstallationReposResponse payload) {
		if (payload == null || payload.repositories() == null) {
			return List.of();
		}
		return payload.repositories().stream()
				.map(node -> new RepoSummary(
						node.id(),
						node.name(),
						node.fullName(),
						node.owner() == null ? null : node.owner().login(),
						node.defaultBranch() == null || node.defaultBranch().isBlank() ? "main" : node.defaultBranch(),
						node.privateRepo()))
				.toList();
	}

	private static IntegrationException tokenExchangeFailed() {
		return new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "GitHub token exchange failed.");
	}

	private static IntegrationException installationInvalid(String message) {
		return new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, HttpStatus.BAD_GATEWAY, message);
	}

	private static String url(String value) {
		return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubUser(long id, String login, String name, String avatar_url) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubOAuthTokenResponse(@JsonProperty("access_token") String accessToken) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubInstallationResponse(
			Long id,
			@JsonProperty("app_id") Long appId,
			GitHubAccountResponse account,
			@JsonProperty("html_url") String htmlUrl,
			@JsonProperty("repository_selection") String repositorySelection) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubAccountResponse(String login, String type) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubInstallationTokenResponse(String token) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubUserInstallationsResponse(List<GitHubInstallationIdResponse> installations) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubInstallationIdResponse(Long id) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubInstallationReposResponse(List<GitHubRepoResponse> repositories) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubRepoResponse(
			long id,
			String name,
			@JsonProperty("full_name") String fullName,
			GitHubAccountResponse owner,
			@JsonProperty("default_branch") String defaultBranch,
			@JsonProperty("private") boolean privateRepo) {}

	public record RepoSummary(long id, String name, String fullName, String owner, String defaultBranch, boolean privateRepo) {}
}

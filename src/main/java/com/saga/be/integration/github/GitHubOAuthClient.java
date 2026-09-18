package com.saga.be.integration.github;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
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

	/**
	 * Lists all branch names for owner/repo. Paginates until the final/empty page (per_page=100).
	 */
	public List<String> listBranches(String installationToken, String owner, String repo) {
		List<String> names = new java.util.ArrayList<>();
		int page = 1;
		while (page <= MAX_BRANCH_LIST_PAGES) {
			List<String> pageNames = listBranchesPage(installationToken, owner, repo, page, 100);
			if (pageNames.isEmpty()) {
				break;
			}
			names.addAll(pageNames);
			if (pageNames.size() < 100) {
				break;
			}
			page++;
		}
		if (page > MAX_BRANCH_LIST_PAGES) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_SYNC_INCOMPLETE,
					HttpStatus.BAD_GATEWAY,
					"GitHub branch listing exceeded defensive pagination guard.");
		}
		return names;
	}

	private List<String> listBranchesPage(
			String installationToken, String owner, String repo, int page, int perPage) {
		try {
			int safePerPage = Math.max(1, Math.min(perPage, 100));
			int safePage = Math.max(1, page);
			GitHubBranchApiResponse[] nodes = restClient
					.get()
					.uri(
							"https://api.github.com/repos/{owner}/{repo}/branches?per_page={perPage}&page={page}",
							owner,
							repo,
							safePerPage,
							safePage)
					.header("Authorization", "Bearer " + installationToken)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubBranchApiResponse[].class);
			if (nodes == null || nodes.length == 0) {
				return List.of();
			}
			return java.util.Arrays.stream(nodes)
					.filter(node -> node != null && node.name() != null && !node.name().isBlank())
					.map(GitHubBranchApiResponse::name)
					.toList();
		} catch (RestClientResponseException ex) {
			throw mapGithubListFailure(ex, "GitHub branches could not be listed.");
		} catch (HttpMessageConversionException ex) {
			throw installationInvalid("GitHub branches could not be listed.");
		}
	}

	/**
	 * Newest commits first for owner/repo reachable from {@code sha} (branch or commit).
	 * GitHub returns at most 100 commits per page. Caller paginates until a short/empty page.
	 * Optional {@code since} is retained for future use but must not be treated as equivalent to
	 * SAGA's claim cutoff ({@code committedAt >= gitRepo.createdAt}). GitHub documents {@code since}
	 * as commits "last updated after" the timestamp. Callers that need Option B must enforce local
	 * filtering and should not pass {@code repo.createdAt} as {@code since} unless provider
	 * equivalence is proven.
	 */
	public List<CommitSummary> listCommits(
			String installationToken, String owner, String repo, String sha, int page, int perPage) {
		return listCommits(installationToken, owner, repo, sha, page, perPage, null);
	}

	public List<CommitSummary> listCommits(
			String installationToken,
			String owner,
			String repo,
			String sha,
			int page,
			int perPage,
			java.time.Instant since) {
		try {
			int safePerPage = Math.max(1, Math.min(perPage, 100));
			int safePage = Math.max(1, page);
			StringBuilder uri = new StringBuilder(
					"https://api.github.com/repos/{owner}/{repo}/commits?per_page={perPage}&page={page}");
			java.util.List<Object> vars = new java.util.ArrayList<>();
			vars.add(owner);
			vars.add(repo);
			vars.add(safePerPage);
			vars.add(safePage);
			if (sha != null && !sha.isBlank()) {
				uri.append("&sha={sha}");
				vars.add(sha);
			}
			if (since != null) {
				uri.append("&since={since}");
				vars.add(java.time.format.DateTimeFormatter.ISO_INSTANT.format(since));
			}
			GitHubCommitApiResponse[] nodes = restClient
					.get()
					.uri(uri.toString(), vars.toArray())
					.header("Authorization", "Bearer " + installationToken)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.body(GitHubCommitApiResponse[].class);
			if (nodes == null) {
				return List.of();
			}
			return java.util.Arrays.stream(nodes)
					.filter(node -> node != null && node.sha() != null && !node.sha().isBlank())
					.map(node -> new CommitSummary(
							node.sha(),
							node.commit() == null ? null : node.commit().message(),
							node.commit() == null || node.commit().author() == null
									? null
									: node.commit().author().date(),
							node.author() == null ? null : node.author().id(),
							node.author() == null ? null : node.author().login(),
							parentCountOf(node.parents())))
					.toList();
		} catch (RestClientResponseException ex) {
			throw mapGithubListFailure(ex, "GitHub commits could not be listed.");
		} catch (HttpMessageConversionException ex) {
			throw installationInvalid("GitHub commits could not be listed.");
		}
	}

	/**
	 * Live GitHub "Get a commit" for one SHA. Metadata is taken from page 1. Changed files are
	 * paginated at {@code per_page=100} for at most {@link #MAX_COMMIT_DETAIL_FILE_PAGES} pages and
	 * capped at {@link #MAX_COMMIT_DETAIL_FILES}. {@code filesTruncated=true} means GitHub still had
	 * more files after that product cap — the UI is showing only the first bounded portion.
	 */
	public CommitDetail getCommit(String installationToken, String owner, String repo, String sha) {
		Map<String, CommitFileChange> uniqueFiles = new LinkedHashMap<>();
		GitHubCommitDetailApiResponse first = null;
		boolean filesTruncated = false;
		for (int page = 1; page <= MAX_COMMIT_DETAIL_FILE_PAGES; page++) {
			CommitPage fetched = getCommitPage(installationToken, owner, repo, sha, page, COMMIT_DETAIL_FILES_PER_PAGE);
			if (first == null) {
				first = fetched.body();
			}
			List<GitHubCommitFile> pageFiles = fetched.body() == null || fetched.body().files() == null
					? List.of()
					: fetched.body().files();
			boolean skippedBecauseCap = false;
			for (GitHubCommitFile file : pageFiles) {
				if (file == null || file.filename() == null || file.filename().isBlank()) {
					continue;
				}
				if (uniqueFiles.containsKey(file.filename())) {
					continue;
				}
				if (uniqueFiles.size() >= MAX_COMMIT_DETAIL_FILES) {
					skippedBecauseCap = true;
					break;
				}
				uniqueFiles.put(
						file.filename(),
						new CommitFileChange(
								file.filename(),
								file.previousFilename(),
								file.status(),
								file.additions(),
								file.deletions(),
								file.changes(),
								file.patch()));
			}
			boolean hasNext = fetched.hasNext();
			if (skippedBecauseCap || (uniqueFiles.size() >= MAX_COMMIT_DETAIL_FILES && hasNext)) {
				filesTruncated = true;
				break;
			}
			if (!hasNext) {
				break;
			}
			if (page == MAX_COMMIT_DETAIL_FILE_PAGES) {
				filesTruncated = true;
			}
		}
		if (first == null) {
			throw commitUnavailable();
		}
		return toCommitDetail(first, List.copyOf(uniqueFiles.values()), filesTruncated);
	}

	private CommitPage getCommitPage(
			String installationToken, String owner, String repo, String sha, int page, int perPage) {
		try {
			int safePerPage = Math.max(1, Math.min(perPage, 100));
			int safePage = Math.max(1, page);
			ResponseEntity<GitHubCommitDetailApiResponse> entity = restClient
					.get()
					.uri(
							"https://api.github.com/repos/{owner}/{repo}/commits/{sha}?per_page={perPage}&page={page}",
							owner,
							repo,
							sha,
							safePerPage,
							safePage)
					.header("Authorization", "Bearer " + installationToken)
					.header("Accept", "application/vnd.github+json")
					.retrieve()
					.toEntity(GitHubCommitDetailApiResponse.class);
			GitHubCommitDetailApiResponse body = entity.getBody();
			if (body == null || body.sha() == null || body.sha().isBlank()) {
				throw commitUnavailable();
			}
			boolean linkPresent = hasLinkHeader(entity.getHeaders());
			return new CommitPage(body, hasRelNext(entity.getHeaders()), linkPresent);
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapGithubCommitFailure(ex);
		} catch (RestClientException | HttpMessageConversionException ex) {
			throw commitUnavailable();
		}
	}

	private static CommitDetail toCommitDetail(
			GitHubCommitDetailApiResponse first, List<CommitFileChange> files, boolean filesTruncated) {
		GitHubCommitDetailBody commit = first.commit();
		GitHubCommitAuthorMeta authorMeta = commit == null ? null : commit.author();
		GitHubCommitAuthorMeta committerMeta = commit == null ? null : commit.committer();
		String authorName = firstNonBlank(authorMeta == null ? null : authorMeta.name(), committerMeta == null ? null : committerMeta.name());
		String committedAt = firstNonBlank(
				authorMeta == null ? null : authorMeta.date(), committerMeta == null ? null : committerMeta.date());
		String authorLogin = firstNonBlank(
				first.author() == null ? null : first.author().login(),
				first.committer() == null ? null : first.committer().login());
		List<CommitParent> parents = first.parents() == null
				? List.of()
				: first.parents().stream()
						.filter(parent -> parent != null && parent.sha() != null && !parent.sha().isBlank())
						.map(parent -> new CommitParent(parent.sha()))
						.toList();
		GitHubCommitStats stats = first.stats();
		return new CommitDetail(
				first.sha(),
				first.htmlUrl(),
				commit == null ? null : commit.message(),
				authorName,
				authorLogin,
				committedAt,
				stats == null ? null : new CommitStats(stats.total(), stats.additions(), stats.deletions()),
				parents,
				files,
				filesTruncated);
	}

	static Integer parentCountOf(List<GitHubCommitParent> parents) {
		return parents == null ? null : parents.size();
	}

	private static boolean hasLinkHeader(HttpHeaders headers) {
		if (headers == null) {
			return false;
		}
		List<String> values = headers.get(HttpHeaders.LINK);
		return values != null && !values.isEmpty();
	}

	private static boolean hasRelNext(HttpHeaders headers) {
		if (headers == null) {
			return false;
		}
		List<String> values = headers.get(HttpHeaders.LINK);
		if (values == null || values.isEmpty()) {
			return false;
		}
		for (String value : values) {
			if (value == null) {
				continue;
			}
			String lower = value.toLowerCase(Locale.ROOT);
			if (lower.contains("rel=\"next\"") || lower.contains("rel='next'") || lower.contains("rel=next")) {
				return true;
			}
		}
		return false;
	}

	private static String firstNonBlank(String primary, String fallback) {
		if (primary != null && !primary.isBlank()) {
			return primary;
		}
		if (fallback != null && !fallback.isBlank()) {
			return fallback;
		}
		return null;
	}

	private static IntegrationException mapGithubCommitFailure(RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		if (status == 403 || status == 429) {
			return new IntegrationException(
					IntegrationErrorCode.GITHUB_RATE_LIMITED,
					HttpStatus.BAD_GATEWAY,
					"GitHub rate limit prevented loading this commit.");
		}
		return commitUnavailable();
	}

	private static IntegrationException commitUnavailable() {
		return new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
				HttpStatus.BAD_GATEWAY,
				"GitHub commit could not be loaded.");
	}

	private static IntegrationException mapGithubListFailure(RestClientResponseException ex, String fallback) {
		int status = ex.getStatusCode().value();
		if (status == 403 || status == 429) {
			return new IntegrationException(
					IntegrationErrorCode.GITHUB_RATE_LIMITED,
					HttpStatus.BAD_GATEWAY,
					"GitHub rate limit prevented completing repository sync.");
		}
		return installationInvalid(fallback);
	}

	/** Defensive only — malformed/infinite provider pagination, not a product data cap. */
	static final int MAX_BRANCH_LIST_PAGES = 1_000;

	static final int COMMIT_DETAIL_FILES_PER_PAGE = 100;
	static final int MAX_COMMIT_DETAIL_FILE_PAGES = 3;
	public static final int MAX_COMMIT_DETAIL_FILES = 300;

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

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubBranchApiResponse(String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitApiResponse(
			String sha, GitHubCommitBody commit, GitHubCommitAuthorUser author, List<GitHubCommitParent> parents) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitBody(String message, GitHubCommitAuthorMeta author) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitAuthorMeta(String name, String email, String date) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitAuthorUser(Long id, String login) {}

	public record CommitSummary(
			String sha, String message, String committedAt, Long authorId, String authorLogin, Integer parentCount) {
		public CommitSummary(String sha, String message, String committedAt, Long authorId, String authorLogin) {
			this(sha, message, committedAt, authorId, authorLogin, null);
		}
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitDetailApiResponse(
			String sha,
			@JsonProperty("html_url") String htmlUrl,
			GitHubCommitDetailBody commit,
			GitHubCommitAuthorUser author,
			GitHubCommitAuthorUser committer,
			List<GitHubCommitParent> parents,
			GitHubCommitStats stats,
			List<GitHubCommitFile> files) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitDetailBody(String message, GitHubCommitAuthorMeta author, GitHubCommitAuthorMeta committer) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitParent(String sha) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitStats(Integer total, Integer additions, Integer deletions) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record GitHubCommitFile(
			String filename,
			@JsonProperty("previous_filename") String previousFilename,
			String status,
			Integer additions,
			Integer deletions,
			Integer changes,
			String patch) {}

	public record CommitDetail(
			String sha,
			String htmlUrl,
			String message,
			String authorName,
			String authorLogin,
			String committedAt,
			CommitStats stats,
			List<CommitParent> parents,
			List<CommitFileChange> files,
			boolean filesTruncated) {}

	public record CommitStats(Integer total, Integer additions, Integer deletions) {}

	public record CommitParent(String sha) {}

	public record CommitFileChange(
			String filename,
			String previousFilename,
			String status,
			Integer additions,
			Integer deletions,
			Integer changes,
			String patch) {}

	private record CommitPage(GitHubCommitDetailApiResponse body, boolean hasNext, boolean linkHeaderPresent) {}
}

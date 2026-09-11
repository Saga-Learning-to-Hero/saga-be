package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

	private static final Logger log = LoggerFactory.getLogger(JiraOAuthClient.class);
	private static final ObjectMapper SAFE_ERROR_MAPPER = new ObjectMapper();

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
		} catch (RestClientResponseException ex) {
			throw mapProjectFailure("getProject", cloudId, projectIdOrKey, ex);
		} catch (HttpMessageConversionException ex) {
			log.warn(
					"jira operation=getProject httpStatus=null errorCode=JIRA_PROJECT_NOT_ACCESSIBLE cloudIdPresent={} projectKey={} cause=body_parse",
					cloudId != null && !cloudId.isBlank(),
					safeProjectKey(projectIdOrKey));
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

	/**
	 * Search issues in one Jira project via enhanced JQL API
	 * ({@code GET /rest/api/3/search/jql}). Legacy {@code /rest/api/3/search} was removed by
	 * Atlassian (HTTP 410). Pagination uses {@code nextPageToken} / {@code isLast}.
	 */
	public IssueSearchPage searchIssues(
			String accessToken, String cloudId, String projectKey, String nextPageToken, int maxResults) {
		try {
			int safeMax = Math.max(1, Math.min(maxResults, 100));
			String jql = "project = \"" + projectKey.replace("\"", "") + "\" ORDER BY updated DESC";
			String fields =
					"summary,status,issuetype,assignee,updated,created,description,priority,resolution,sprint";
			IssueSearchJqlResponse node = restClient
					.get()
					.uri(builder -> {
						var uri = builder
								.scheme("https")
								.host("api.atlassian.com")
								.path("/ex/jira/{cloudId}/rest/api/3/search/jql")
								.queryParam("jql", jql)
								.queryParam("maxResults", safeMax)
								.queryParam("fields", fields);
						if (nextPageToken != null && !nextPageToken.isBlank()) {
							uri = uri.queryParam("nextPageToken", nextPageToken);
						}
						return uri.build(cloudId);
					})
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(IssueSearchJqlResponse.class);
			if (node == null || node.issues() == null) {
				return new IssueSearchPage(List.of(), null, true, safeMax);
			}
			List<IssueSummary> issues = node.issues().stream()
					.filter(item -> item != null && item.id() != null)
					.map(JiraOAuthClient::toSummary)
					.toList();
			boolean last = Boolean.TRUE.equals(node.isLast())
					|| node.nextPageToken() == null
					|| node.nextPageToken().isBlank()
					|| issues.isEmpty();
			return new IssueSearchPage(issues, blankToNull(node.nextPageToken()), last, safeMax);
		} catch (RestClientResponseException ex) {
			throw mapSearchFailure("searchIssues", cloudId, projectKey, ex);
		} catch (HttpMessageConversionException ex) {
			log.warn(
					"jira operation=searchIssues httpStatus=null errorCode=JIRA_PROJECT_NOT_ACCESSIBLE cloudIdPresent={} projectKey={} cause=body_parse",
					cloudId != null && !cloudId.isBlank(),
					safeProjectKey(projectKey));
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE,
					HttpStatus.BAD_GATEWAY,
					"Jira issues could not be listed.");
		}
	}

	private IntegrationException mapProjectFailure(
			String operation, String cloudId, String projectIdOrKey, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		IntegrationErrorCode code = status == 401
				? IntegrationErrorCode.JIRA_UNAUTHORIZED
				: IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE;
		log.warn(
				"jira operation={} httpStatus={} errorCode={} cloudIdPresent={} projectKey={} errorMessages={}",
				operation,
				status,
				code.name(),
				cloudId != null && !cloudId.isBlank(),
				safeProjectKey(projectIdOrKey),
				safeErrorMessages(ex));
		HttpStatus mapped = status == 401 ? HttpStatus.UNAUTHORIZED : HttpStatus.FORBIDDEN;
		return new IntegrationException(code, mapped, "Jira project is not accessible.");
	}

	private IntegrationException mapSearchFailure(
			String operation, String cloudId, String projectKey, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		IntegrationErrorCode code;
		HttpStatus mapped;
		String message;
		if (status == 401) {
			code = IntegrationErrorCode.JIRA_UNAUTHORIZED;
			mapped = HttpStatus.UNAUTHORIZED;
			message = "Jira access token rejected.";
		} else if (status == 410) {
			code = IntegrationErrorCode.JIRA_SYNC_INCOMPLETE;
			mapped = HttpStatus.BAD_GATEWAY;
			message = "Jira issue search API was removed or unavailable.";
		} else if (status == 403 || status == 404) {
			code = IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE;
			mapped = HttpStatus.BAD_GATEWAY;
			message = "Jira issues could not be listed.";
		} else {
			code = IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE;
			mapped = HttpStatus.BAD_GATEWAY;
			message = "Jira issues could not be listed.";
		}
		log.warn(
				"jira operation={} httpStatus={} errorCode={} cloudIdPresent={} projectKey={} errorMessages={}",
				operation,
				status,
				code.name(),
				cloudId != null && !cloudId.isBlank(),
				safeProjectKey(projectKey),
				safeErrorMessages(ex));
		return new IntegrationException(code, mapped, message);
	}

	static String safeErrorMessages(RestClientResponseException ex) {
		try {
			String body = ex.getResponseBodyAsString();
			if (body == null || body.isBlank()) {
				return "";
			}
			JiraErrorBody parsed = SAFE_ERROR_MAPPER.readValue(body, JiraErrorBody.class);
			if (parsed.errorMessages() == null || parsed.errorMessages().isEmpty()) {
				return "";
			}
			return parsed.errorMessages().stream()
					.filter(msg -> msg != null && !msg.isBlank())
					.limit(3)
					.map(msg -> msg.length() > 200 ? msg.substring(0, 200) : msg)
					.collect(Collectors.joining("; "));
		} catch (Exception ignored) {
			return "";
		}
	}

	private static String safeProjectKey(String projectKey) {
		if (projectKey == null || projectKey.isBlank()) {
			return "";
		}
		return projectKey.length() > 64 ? projectKey.substring(0, 64) : projectKey;
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	private static IssueSummary toSummary(IssueApiResponse item) {
		IssueFields fields = item.fields();
		IssueStatus status = fields == null ? null : fields.status();
		IssueStatusCategory category = status == null ? null : status.statusCategory();
		IssueType type = fields == null ? null : fields.issuetype();
		IssueAssignee assignee = fields == null ? null : fields.assignee();
		IssuePriority priority = fields == null ? null : fields.priority();
		SprintRef sprint = firstSprint(fields == null ? null : fields.sprint());
		return new IssueSummary(
				item.id(),
				item.key(),
				fields == null ? null : fields.summary(),
				status == null ? null : status.id(),
				status == null ? null : status.name(),
				category == null ? null : category.key(),
				type == null ? null : type.name(),
				type == null ? null : type.id(),
				assignee == null ? null : assignee.accountId(),
				assignee == null ? null : assignee.displayName(),
				priority == null ? null : priority.id(),
				priority == null ? null : priority.name(),
				null,
				descriptionText(fields == null ? null : fields.description()),
				sprint == null ? null : sprint.id(),
				sprint == null ? null : sprint.name(),
				sprint == null ? null : sprint.state(),
				fields == null ? null : fields.created(),
				fields == null ? null : fields.updated());
	}

	static IssueSummary withStoryPoints(IssueSummary summary, Integer storyPoints) {
		if (summary == null) {
			return null;
		}
		return new IssueSummary(
				summary.id(),
				summary.key(),
				summary.summary(),
				summary.statusId(),
				summary.statusName(),
				summary.statusCategory(),
				summary.issueTypeName(),
				summary.issueTypeId(),
				summary.assigneeAccountId(),
				summary.assigneeDisplayName(),
				summary.priorityId(),
				summary.priorityName(),
				storyPoints,
				summary.description(),
				summary.sprintExternalId(),
				summary.sprintName(),
				summary.sprintState(),
				summary.created(),
				summary.updated());
	}

	private static SprintRef firstSprint(List<SprintRef> sprints) {
		if (sprints == null || sprints.isEmpty()) {
			return null;
		}
		for (int i = sprints.size() - 1; i >= 0; i--) {
			SprintRef sprint = sprints.get(i);
			if (sprint != null && sprint.id() != null && !sprint.id().isBlank()) {
				return sprint;
			}
		}
		return null;
	}

	private static String descriptionText(Object node) {
		if (node == null) {
			return null;
		}
		if (node instanceof String text) {
			return text.isBlank() ? null : text;
		}
		if (node instanceof Map<?, ?> map) {
			StringBuilder out = new StringBuilder();
			appendAdfObject(map, out);
			String text = out.toString().trim();
			return text.isEmpty() ? null : text;
		}
		return null;
	}

	private static void appendAdfObject(Object node, StringBuilder out) {
		if (node == null) {
			return;
		}
		if (node instanceof String text) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(text);
			return;
		}
		if (node instanceof Map<?, ?> map) {
			Object text = map.get("text");
			if (text instanceof String value && !value.isBlank()) {
				if (!out.isEmpty()) {
					out.append(' ');
				}
				out.append(value);
			}
			Object content = map.get("content");
			if (content instanceof List<?> list) {
				for (Object child : list) {
					appendAdfObject(child, out);
				}
			}
		} else if (node instanceof List<?> list) {
			for (Object child : list) {
				appendAdfObject(child, out);
			}
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

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueSearchJqlResponse(
			List<IssueApiResponse> issues, String nextPageToken, Boolean isLast) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraErrorBody(List<String> errorMessages) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueApiResponse(String id, String key, IssueFields fields) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueFields(
			String summary,
			IssueStatus status,
			IssueType issuetype,
			IssueAssignee assignee,
			String created,
			String updated,
			Object description,
			IssuePriority priority,
			IssueResolution resolution,
			List<SprintRef> sprint) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueStatus(String id, String name, IssueStatusCategory statusCategory) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueStatusCategory(String key, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueType(String id, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueAssignee(String accountId, String displayName) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssuePriority(String id, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record IssueResolution(String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SprintRef(String id, String name, String state) {}

	public record IssueSummary(
			String id,
			String key,
			String summary,
			String statusId,
			String statusName,
			String statusCategory,
			String issueTypeName,
			String issueTypeId,
			String assigneeAccountId,
			String assigneeDisplayName,
			String priorityId,
			String priorityName,
			Integer storyPoints,
			String description,
			String sprintExternalId,
			String sprintName,
			String sprintState,
			String created,
			String updated) {}

	public record IssueSearchPage(List<IssueSummary> issues, String nextPageToken, boolean last, int maxResults) {}
}

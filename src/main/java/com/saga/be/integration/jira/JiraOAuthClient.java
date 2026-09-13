package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
	private static final ObjectMapper MAPPER = new ObjectMapper();

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
	 *
	 * <p>{@code storyPointsFieldId}/{@code sprintFieldId} are the site-specific custom field IDs
	 * (e.g. {@code customfield_10020}) resolved once per sync by the caller via {@link
	 * JiraIssueWriteClient#resolveStoryPointsFieldId}/{@link JiraIssueWriteClient#resolveSprintFieldId}
	 * — never hardcoded here, since they vary between Jira sites. Either may be {@code null}/blank
	 * when undiscoverable; the literal {@code "sprint"} field is always requested too since some
	 * team-managed projects expose Sprint under that name directly. Parsing reuses {@link
	 * JiraIssueWriteClient#toSummary} so bulk sync and single-issue reads share one payload-shape
	 * audit instead of two divergent implementations.
	 */
	public IssueSearchPage searchIssues(
			String accessToken,
			String cloudId,
			String projectKey,
			String nextPageToken,
			int maxResults,
			String storyPointsFieldId,
			String sprintFieldId) {
		try {
			int safeMax = Math.max(1, Math.min(maxResults, 100));
			String jql = "project = \"" + projectKey.replace("\"", "") + "\" ORDER BY updated DESC";
			String fields = "summary,status,issuetype,assignee,updated,created,description,priority,resolution,sprint"
					+ (storyPointsFieldId == null || storyPointsFieldId.isBlank() ? "" : "," + storyPointsFieldId)
					+ (sprintFieldId == null || sprintFieldId.isBlank() || "sprint".equals(sprintFieldId)
							? ""
							: "," + sprintFieldId);
			String raw = restClient
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
					.body(String.class);
			JsonNode node = raw == null || raw.isBlank() ? null : MAPPER.readTree(raw);
			if (node == null || !node.path("issues").isArray()) {
				return new IssueSearchPage(List.of(), null, true, safeMax);
			}
			List<IssueSummary> issues = new ArrayList<>();
			for (JsonNode issueNode : node.path("issues")) {
				if (issueNode == null || issueNode.path("id").isMissingNode() || issueNode.path("id").isNull()) {
					continue;
				}
				issues.add(JiraIssueWriteClient.toSummary(issueNode, storyPointsFieldId, sprintFieldId, true));
			}
			String nextToken = blankToNull(node.path("nextPageToken").asText(null));
			boolean last = node.path("isLast").asBoolean(false) || nextToken == null || issues.isEmpty();
			return new IssueSearchPage(List.copyOf(issues), nextToken, last, safeMax);
		} catch (RestClientResponseException ex) {
			throw mapSearchFailure("searchIssues", cloudId, projectKey, ex);
		} catch (HttpMessageConversionException | com.fasterxml.jackson.core.JsonProcessingException ex) {
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
			JiraErrorBody parsed = MAPPER.readValue(body, JiraErrorBody.class);
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

	/**
	 * Field-level rejection detail from a Jira 400 (e.g. {@code {"errors":{"customfield_10016":
	 * "Field 'customfield_10016' cannot be set. It is not on the appropriate screen, or unknown."}}}).
	 * Distinct from {@link #safeErrorMessages}, which only reads the top-level {@code errorMessages}
	 * list — "reject field" failures live in the {@code errors} map instead, keyed by field id.
	 * Never includes the OAuth token, the request body, or the raw response — only field
	 * ids/messages, capped in count and length.
	 */
	static String safeFieldErrors(RestClientResponseException ex) {
		try {
			String body = ex.getResponseBodyAsString();
			if (body == null || body.isBlank()) {
				return "";
			}
			JiraErrorBody parsed = MAPPER.readValue(body, JiraErrorBody.class);
			if (parsed.errors() == null || parsed.errors().isEmpty()) {
				return "";
			}
			return parsed.errors().entrySet().stream()
					.filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
					.limit(5)
					.map(entry -> {
						String message = entry.getValue();
						String safeMessage = message == null
								? ""
								: (message.length() > 200 ? message.substring(0, 200) : message);
						return safeMessage.isBlank() ? entry.getKey() : entry.getKey() + ": " + safeMessage;
					})
					.collect(Collectors.joining("; "));
		} catch (Exception ignored) {
			return "";
		}
	}

	/** Field ids from {@link #safeFieldErrors}'s parsed {@code errors} map, for programmatic use. */
	static Set<String> rejectedFieldIds(RestClientResponseException ex) {
		try {
			String body = ex.getResponseBodyAsString();
			if (body == null || body.isBlank()) {
				return Set.of();
			}
			JiraErrorBody parsed = MAPPER.readValue(body, JiraErrorBody.class);
			if (parsed.errors() == null || parsed.errors().isEmpty()) {
				return Set.of();
			}
			return Set.copyOf(parsed.errors().keySet());
		} catch (Exception ignored) {
			return Set.of();
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

	/** {@code errors} is Jira's field-keyed rejection map, e.g. {@code {"customfield_10016": "..."}}. */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record JiraErrorBody(List<String> errorMessages, Map<String, String> errors) {}

	/**
	 * {@code storyPointsProvided}/{@code sprintProvided} distinguish "Jira told us the true current
	 * value (possibly null, meaning explicitly cleared)" from "this payload said nothing about this
	 * field, leave whatever SAGA already has alone" — see {@link JiraIssueWriteClient#toSummary}.
	 * The legacy 19-arg constructor defaults both to {@code true} (authoritative), matching every
	 * existing caller (bulk sync, single-issue fetch, and all pre-existing tests) unchanged; only
	 * the webhook path needs the distinction and uses the canonical 21-arg constructor explicitly.
	 */
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
			String updated,
			boolean storyPointsProvided,
			boolean sprintProvided) {

		public IssueSummary(
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
				String updated) {
			this(
					id, key, summary, statusId, statusName, statusCategory, issueTypeName, issueTypeId,
					assigneeAccountId, assigneeDisplayName, priorityId, priorityName, storyPoints, description,
					sprintExternalId, sprintName, sprintState, created, updated,
					true, true);
		}
	}

	public record IssueSearchPage(List<IssueSummary> issues, String nextPageToken, boolean last, int maxResults) {}
}

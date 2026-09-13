package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Jira Cloud write + reconcile client. Provider HTTP only — callers keep JDBC short.
 */
@Component
@Profile("!test")
public class JiraIssueWriteClient {

	private static final Logger log = LoggerFactory.getLogger(JiraIssueWriteClient.class);

	/**
	 * Known Story Point field display names across Jira project types. Team-managed (next-gen)
	 * projects expose "Story point estimate"; Company-managed (classic) projects typically expose
	 * "Story Points". An exact (case-insensitive) name match is a far stronger signal than "name
	 * contains 'story point'", which can false-positive on unrelated fields.
	 */
	private static final Set<String> STORY_POINT_EXACT_NAMES = Set.of("story points", "story point estimate");

	/** Reinforces an exact-name match; alone (float-typed, non-matching name) it is NOT sufficient. */
	private static final Set<String> STORY_POINT_SCHEMA_CUSTOM_TYPES = Set.of(
			"com.atlassian.jira.plugin.system.customfieldtypes:float", "com.pyxis.greenhopper.jira:jsw-story-points");

	private static final String SPRINT_EXACT_NAME = "sprint";

	/** Company-managed Sprint field's schema custom type. Not assumed present on Team-managed. */
	private static final String SPRINT_SCHEMA_CUSTOM_TYPE_FRAGMENT = "gh-sprint";

	private final RestClient restClient;
	private final IntegrationProperties properties;
	private final ObjectMapper mapper;
	private final ConcurrentHashMap<String, String> storyPointsFieldByCloud = new ConcurrentHashMap<>();
	private final ConcurrentHashMap<String, String> sprintFieldByCloud = new ConcurrentHashMap<>();

	public JiraIssueWriteClient(RestClient integrationRestClient, IntegrationProperties properties, ObjectMapper mapper) {
		this.restClient = integrationRestClient;
		this.properties = properties;
		this.mapper = mapper;
	}

	public IssueSummary getIssue(String accessToken, String cloudId, String issueIdOrKey) {
		String storyField = resolveStoryPointsFieldId(accessToken, cloudId);
		String sprintField = resolveSprintFieldId(accessToken, cloudId);
		String fields = "summary,status,issuetype,assignee,updated,created,description,priority,resolution,sprint"
				+ (storyField == null || storyField.isBlank() ? "" : "," + storyField)
				+ (sprintField == null || sprintField.isBlank() || "sprint".equals(sprintField)
						? ""
						: "," + sprintField);
		try {
			JsonNode node = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}?fields={fields}",
							cloudId,
							issueIdOrKey,
							fields)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			if (node == null || node.path("id").isMissingNode()) {
				throw issueNotFound();
			}
			return toSummary(node, storyField, sprintField, true);
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapIssueFailure("getIssue", ex);
		} catch (HttpMessageConversionException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "Jira issue could not be parsed.");
		}
	}

	public CreatedIssue createIssue(
			String accessToken,
			String cloudId,
			String projectId,
			String summary,
			String description,
			String issueTypeId,
			String assigneeAccountId,
			String priorityId,
			Integer storyPoints) {
		ObjectNode body = mapper.createObjectNode();
		ObjectNode fields = body.putObject("fields");
		fields.putObject("project").put("id", projectId);
		fields.put("summary", summary);
		if (issueTypeId != null && !issueTypeId.isBlank()) {
			fields.putObject("issuetype").put("id", issueTypeId);
		} else {
			fields.putObject("issuetype").put("name", "Task");
		}
		if (description != null && !description.isBlank()) {
			fields.set("description", plainAdf(description));
		}
		if (assigneeAccountId != null && !assigneeAccountId.isBlank()) {
			fields.putObject("assignee").put("accountId", assigneeAccountId);
		}
		if (priorityId != null && !priorityId.isBlank()) {
			fields.putObject("priority").put("id", priorityId);
		}
		String storyField = resolveStoryPointsFieldId(accessToken, cloudId);
		if (storyPoints != null && storyField != null && !storyField.isBlank()) {
			fields.put(storyField, storyPoints);
		}
		try {
			CreatedIssueResponse created = restClient
					.post()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(CreatedIssueResponse.class);
			if (created == null || created.id() == null || created.id().isBlank()) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_ISSUE_CREATE_FAILED, HttpStatus.BAD_GATEWAY, "Jira issue create failed.");
			}
			return new CreatedIssue(created.id(), created.key());
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("createIssue", IntegrationErrorCode.JIRA_ISSUE_CREATE_FAILED, ex);
		}
	}

	public void updateIssueFields(String accessToken, String cloudId, String issueIdOrKey, Map<String, Object> fieldUpdates) {
		if (fieldUpdates == null || fieldUpdates.isEmpty()) {
			return;
		}
		ObjectNode body = mapper.createObjectNode();
		ObjectNode fields = body.putObject("fields");
		for (Map.Entry<String, Object> entry : fieldUpdates.entrySet()) {
			fields.set(entry.getKey(), mapper.valueToTree(entry.getValue()));
		}
		try {
			restClient
					.put()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}", cloudId, issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("updateIssue", IntegrationErrorCode.JIRA_ISSUE_UPDATE_FAILED, ex);
		}
	}

	public List<TransitionOption> listTransitions(String accessToken, String cloudId, String issueIdOrKey) {
		try {
			TransitionsResponse body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}/transitions",
							cloudId,
							issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(TransitionsResponse.class);
			if (body == null || body.transitions() == null) {
				return List.of();
			}
			List<TransitionOption> out = new ArrayList<>();
			for (TransitionApi t : body.transitions()) {
				if (t == null || t.id() == null) {
					continue;
				}
				out.add(new TransitionOption(
						t.id(),
						t.name(),
						t.to() == null ? null : t.to().id(),
						t.to() == null ? null : t.to().name()));
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("listTransitions", IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE, ex);
		}
	}

	public void transitionIssue(String accessToken, String cloudId, String issueIdOrKey, String transitionId) {
		ObjectNode body = mapper.createObjectNode();
		body.putObject("transition").put("id", transitionId);
		try {
			restClient
					.post()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}/transitions",
							cloudId,
							issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("transitionIssue", IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE, ex);
		}
	}

	public void deleteIssue(String accessToken, String cloudId, String issueIdOrKey) {
		try {
			restClient
					.delete()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issue/{issue}?deleteSubtasks=true",
							cloudId,
							issueIdOrKey)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("deleteIssue", IntegrationErrorCode.JIRA_ISSUE_DELETE_FAILED, ex);
		}
	}

	public SprintDetail createSprint(
			String accessToken, String cloudId, String boardId, String name, String goal, String startDate, String endDate) {
		ObjectNode body = mapper.createObjectNode();
		body.put("name", name);
		body.put("originBoardId", Long.parseLong(boardId));
		if (goal != null) {
			body.put("goal", goal);
		}
		if (startDate != null && !startDate.isBlank()) {
			body.put("startDate", startDate);
		}
		if (endDate != null && !endDate.isBlank()) {
			body.put("endDate", endDate);
		}
		try {
			SprintDetail created = restClient
					.post()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/sprint", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(SprintDetail.class);
			if (created == null || created.id() == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, HttpStatus.BAD_GATEWAY, "Jira sprint create failed.");
			}
			return created;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("createSprint", IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, ex);
		} catch (NumberFormatException ex) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SPRINT_INVALID, HttpStatus.BAD_REQUEST, "Jira board id is invalid.");
		}
	}

	public SprintDetail updateSprint(
			String accessToken,
			String cloudId,
			String sprintId,
			String name,
			String goal,
			String state,
			String startDate,
			String endDate) {
		ObjectNode body = mapper.createObjectNode();
		if (name != null) {
			body.put("name", name);
		}
		if (goal != null) {
			body.put("goal", goal);
		}
		if (state != null) {
			body.put("state", state);
		}
		if (startDate != null) {
			body.put("startDate", startDate);
		}
		if (endDate != null) {
			body.put("endDate", endDate);
		}
		try {
			SprintDetail updated = restClient
					.post()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/sprint/{sprintId}", cloudId, sprintId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(SprintDetail.class);
			if (updated == null || updated.id() == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, HttpStatus.BAD_GATEWAY, "Jira sprint update failed.");
			}
			return updated;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("updateSprint", IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, ex);
		}
	}

	public void deleteSprint(String accessToken, String cloudId, String sprintId) {
		try {
			restClient
					.delete()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/sprint/{sprintId}", cloudId, sprintId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("deleteSprint", IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, ex);
		}
	}

	public List<SprintDetail> listBoardSprints(String accessToken, String cloudId, String boardId) {
		try {
			SprintListResponse body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board/{boardId}/sprint?maxResults=50",
							cloudId,
							boardId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(SprintListResponse.class);
			return body == null || body.values() == null ? List.of() : body.values();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("listBoardSprints", IntegrationErrorCode.JIRA_SPRINT_INVALID, ex);
		}
	}

	public SprintDetail getSprint(String accessToken, String cloudId, String sprintId) {
		try {
			SprintDetail body = restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/sprint/{sprintId}", cloudId, sprintId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(SprintDetail.class);
			if (body == null || body.id() == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SPRINT_INVALID, HttpStatus.NOT_FOUND, "Jira sprint was not found.");
			}
			return body;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("getSprint", IntegrationErrorCode.JIRA_SPRINT_INVALID, ex);
		}
	}

	public void moveIssuesToSprint(String accessToken, String cloudId, String sprintId, List<String> issueKeysOrIds) {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode issues = body.putArray("issues");
		for (String id : issueKeysOrIds) {
			issues.add(id);
		}
		try {
			restClient
					.post()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/sprint/{sprintId}/issue",
							cloudId,
							sprintId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("moveIssuesToSprint", IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, ex);
		}
	}

	public void moveIssuesToBacklog(String accessToken, String cloudId, String boardId, List<String> issueKeysOrIds) {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode issues = body.putArray("issues");
		for (String id : issueKeysOrIds) {
			issues.add(id);
		}
		try {
			restClient
					.post()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board/{boardId}/backlog/issue",
							cloudId,
							boardId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			// Prefer board-scoped backlog; fall back to site backlog endpoint.
			try {
				restClient
						.post()
						.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/backlog/issue", cloudId)
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.body(body)
						.retrieve()
						.toBodilessEntity();
			} catch (RestClientResponseException fallback) {
				throw mapWriteFailure("moveIssuesToBacklog", IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, ex);
			}
		}
	}

	/**
	 * Prefer Jira Software board estimation API so the board's configured field is used.
	 * Falls back to discovered Story Points custom field when board estimation is unavailable.
	 */
	public void setIssueEstimation(
			String accessToken, String cloudId, String boardId, String issueIdOrKey, Number value) {
		if (boardId != null && !boardId.isBlank()) {
			ObjectNode body = mapper.createObjectNode();
			if (value == null) {
				body.putNull("value");
			} else {
				body.put("value", value.doubleValue());
			}
			try {
				restClient
						.put()
						.uri(
								"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/issue/{issue}/estimation?boardId={boardId}",
								cloudId,
								issueIdOrKey,
								boardId)
						.header("Authorization", "Bearer " + accessToken)
						.contentType(MediaType.APPLICATION_JSON)
						.body(body)
						.retrieve()
						.toBodilessEntity();
				return;
			} catch (RestClientResponseException ex) {
				// fall through to custom field write
			}
		}
		String storyField = resolveStoryPointsFieldId(accessToken, cloudId);
		if (storyField == null || storyField.isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FIELD_INVALID,
					HttpStatus.BAD_REQUEST,
					"Story Points / estimation field could not be resolved for this Jira board.");
		}
		updateIssueFields(accessToken, cloudId, issueIdOrKey, Map.of(storyField, value));
	}

	public EstimationInfo getIssueEstimation(String accessToken, String cloudId, String boardId, String issueIdOrKey) {
		if (boardId != null && !boardId.isBlank()) {
			try {
				JsonNode node = restClient
						.get()
						.uri(
								"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/issue/{issue}/estimation?boardId={boardId}",
								cloudId,
								issueIdOrKey,
								boardId)
						.header("Authorization", "Bearer " + accessToken)
						.retrieve()
						.body(JsonNode.class);
				if (node != null) {
					Integer value = null;
					if (node.has("value") && !node.get("value").isNull()) {
						value = (int) Math.round(node.get("value").asDouble());
					}
					return new EstimationInfo(
							true, text(node, "fieldId"), text(node, "fieldName"), value);
				}
			} catch (RestClientResponseException ex) {
				// fall through
			}
		}
		String fieldId = resolveStoryPointsFieldId(accessToken, cloudId);
		return new EstimationInfo(
				fieldId != null && !fieldId.isBlank(), fieldId == null || fieldId.isBlank() ? null : fieldId, "Story Points", null);
	}

	public EstimationInfo boardEstimationCapability(String accessToken, String cloudId, String boardId) {
		if (boardId == null || boardId.isBlank()) {
			String fieldId = resolveStoryPointsFieldId(accessToken, cloudId);
			return new EstimationInfo(
					fieldId != null && !fieldId.isBlank(),
					fieldId == null || fieldId.isBlank() ? null : fieldId,
					"Story Points",
					null);
		}
		try {
			JsonNode conf = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/agile/1.0/board/{boardId}/configuration",
							cloudId,
							boardId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			JsonNode estimation = conf == null ? null : conf.path("estimation").path("field");
			String fieldId = estimation == null ? null : text(estimation, "fieldId");
			String fieldName = estimation == null ? null : text(estimation, "displayName");
			if (fieldId != null) {
				return new EstimationInfo(true, fieldId, fieldName, null);
			}
		} catch (Exception ex) {
			// fall through
		}
		return boardEstimationCapability(accessToken, cloudId, null);
	}

	public List<IssueTypeOption> listProjectIssueTypes(String accessToken, String cloudId, String projectId) {
		try {
			JsonNode body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/issuetype/project?projectId={projectId}",
							cloudId,
							projectId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			List<IssueTypeOption> out = new ArrayList<>();
			if (body != null && body.isArray()) {
				for (JsonNode node : body) {
					out.add(new IssueTypeOption(text(node, "id"), text(node, "name"), text(node, "description")));
				}
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("listProjectIssueTypes", IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex);
		}
	}

	public List<PriorityOption> listPriorities(String accessToken, String cloudId) {
		try {
			JsonNode body = restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/priority", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			List<PriorityOption> out = new ArrayList<>();
			if (body != null && body.isArray()) {
				for (JsonNode node : body) {
					out.add(new PriorityOption(text(node, "id"), text(node, "name")));
				}
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("listPriorities", IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex);
		}
	}

	public List<AssignableUserOption> listAssignableUsers(
			String accessToken, String cloudId, String projectKeyOrId, int maxResults) {
		try {
			JsonNode body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/user/assignable/search?project={project}&maxResults={max}",
							cloudId,
							projectKeyOrId,
							Math.max(1, Math.min(maxResults, 50)))
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			List<AssignableUserOption> out = new ArrayList<>();
			if (body != null && body.isArray()) {
				for (JsonNode node : body) {
					out.add(new AssignableUserOption(text(node, "accountId"), text(node, "displayName")));
				}
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw mapWriteFailure("listAssignableUsers", IntegrationErrorCode.INTEGRATION_UNAVAILABLE, ex);
		}
	}

	/**
	 * Resolution precedence (highest first): (1) {@code saga.integration.jira.story-points-field-id}
	 * — an optional manual override for emergencies, never defaulted to a site-specific id; (2) a
	 * per-cloud cached id from a prior discovery on this cloud; (3) fresh dynamic discovery via
	 * {@link #discoverStoryPointsField}, cached for this cloud thereafter. No config is required for
	 * normal operation — the override exists only as an escape hatch when discovery is verified
	 * wrong for a given site.
	 */
	public String resolveStoryPointsFieldId(String accessToken, String cloudId) {
		String configured = properties.getJira().getStoryPointsFieldId();
		if (configured != null && !configured.isBlank()) {
			log.info("jira field discovery cloudId={} concept=storyPoints result=CONFIG_OVERRIDE fieldId={}", cloudId, configured);
			return configured;
		}
		return storyPointsFieldByCloud.computeIfAbsent(cloudId, id -> discoverStoryPointsField(accessToken, id));
	}

	/** Same precedence as {@link #resolveStoryPointsFieldId}, for {@code saga.integration.jira.sprint-field-id}. */
	public String resolveSprintFieldId(String accessToken, String cloudId) {
		String configured = properties.getJira().getSprintFieldId();
		if (configured != null && !configured.isBlank()) {
			log.info("jira field discovery cloudId={} concept=sprint result=CONFIG_OVERRIDE fieldId={}", cloudId, configured);
			return configured;
		}
		return sprintFieldByCloud.computeIfAbsent(cloudId, id -> discoverSprintField(accessToken, id));
	}

	/**
	 * Cache-only lookup — never makes a provider HTTP call. For callers on a payload-only hot path
	 * (webhook projection) that must not perform provider HTTP: returns whatever a prior {@link
	 * #resolveStoryPointsFieldId} call for this cloud already discovered, or {@code null} if this
	 * cloud's field id has not been resolved yet (e.g. no sync has run since the app started).
	 */
	public String peekCachedStoryPointsFieldId(String cloudId) {
		String configured = properties.getJira().getStoryPointsFieldId();
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return storyPointsFieldByCloud.get(cloudId);
	}

	/** Cache-only counterpart of {@link #peekCachedStoryPointsFieldId} for the Sprint field. */
	public String peekCachedSprintFieldId(String cloudId) {
		String configured = properties.getJira().getSprintFieldId();
		if (configured != null && !configured.isBlank()) {
			return configured;
		}
		return sprintFieldByCloud.get(cloudId);
	}

	private String discoverStoryPointsField(String accessToken, String cloudId) {
		List<JsonNode> candidates = fetchFieldMetadata(accessToken, cloudId);
		if (candidates == null) {
			return "";
		}
		List<String> exactNameMatches = new ArrayList<>();
		List<String> schemaOnlyMatches = new ArrayList<>();
		for (JsonNode field : candidates) {
			String id = text(field, "id");
			String name = text(field, "name");
			String custom = text(field.path("schema"), "custom");
			if (id == null) {
				continue;
			}
			String normalizedName = name == null ? "" : name.toLowerCase(Locale.ROOT).trim();
			if (STORY_POINT_EXACT_NAMES.contains(normalizedName)) {
				exactNameMatches.add(id);
			} else if (custom != null
					&& STORY_POINT_SCHEMA_CUSTOM_TYPES.contains(custom)
					&& normalizedName.contains("story")) {
				// Schema reinforces a fuzzy name match only -- schema type alone (e.g. any
				// arbitrary "float" custom field) is never sufficient on its own.
				schemaOnlyMatches.add(id);
			}
		}
		return selectUnambiguous(cloudId, "storyPoints", candidates.size(), exactNameMatches, schemaOnlyMatches);
	}

	private String discoverSprintField(String accessToken, String cloudId) {
		List<JsonNode> candidates = fetchFieldMetadata(accessToken, cloudId);
		if (candidates == null) {
			return "";
		}
		List<String> exactNameMatches = new ArrayList<>();
		List<String> schemaOnlyMatches = new ArrayList<>();
		for (JsonNode field : candidates) {
			String id = text(field, "id");
			String name = text(field, "name");
			String custom = text(field.path("schema"), "custom");
			if (id == null) {
				continue;
			}
			if (name != null && SPRINT_EXACT_NAME.equalsIgnoreCase(name.trim())) {
				// Exact name "Sprint" is trusted on its own -- Team-managed projects are not
				// assumed to expose the same gh-sprint schema custom type as Company-managed.
				exactNameMatches.add(id);
			} else if (custom != null && custom.contains(SPRINT_SCHEMA_CUSTOM_TYPE_FRAGMENT)) {
				schemaOnlyMatches.add(id);
			}
		}
		return selectUnambiguous(cloudId, "sprint", candidates.size(), exactNameMatches, schemaOnlyMatches);
	}

	/**
	 * Strongest-evidence-first, ambiguity-safe selection: prefer exact-name matches; only fall back
	 * to schema-only matches when there is no exact-name match at all. Never silently picks an
	 * arbitrary first result when more than one field qualifies at the SAME evidence tier --
	 * returns a controlled discovery failure ({@code ""}) instead, logged for diagnosis.
	 *
	 * <p>Diagnostic logs intentionally include the Jira cloud id and field id/name counts (per the
	 * "REAL JIRA DIAGNOSTIC SUPPORT" audit requirement) so discovery outcomes can be confirmed
	 * against a real site -- but never an OAuth/refresh token or the full field-metadata payload.
	 */
	private static String selectUnambiguous(
			String cloudId, String concept, int totalFieldCount, List<String> exactNameMatches, List<String> schemaOnlyMatches) {
		List<String> winningTier = !exactNameMatches.isEmpty() ? exactNameMatches : schemaOnlyMatches;
		String tierName = !exactNameMatches.isEmpty() ? "exact-name" : "schema-only";
		if (winningTier.isEmpty()) {
			log.info(
					"jira field discovery cloudId={} concept={} result=NOT_FOUND totalFieldCount={}",
					cloudId,
					concept,
					totalFieldCount);
			return "";
		}
		if (winningTier.size() > 1) {
			log.warn(
					"jira field discovery cloudId={} concept={} result=AMBIGUOUS tier={} candidateCount={} totalFieldCount={}",
					cloudId,
					concept,
					tierName,
					winningTier.size(),
					totalFieldCount);
			return "";
		}
		String resolved = winningTier.getFirst();
		log.info(
				"jira field discovery cloudId={} concept={} result=RESOLVED tier={} fieldId={} totalFieldCount={}",
				cloudId,
				concept,
				tierName,
				resolved,
				totalFieldCount);
		return resolved;
	}

	private List<JsonNode> fetchFieldMetadata(String accessToken, String cloudId) {
		try {
			// Fetched as String and parsed manually (not .body(JsonNode.class)) -- a bare
			// RestClient built without a Spring context does not reliably negotiate a JSON message
			// converter for JsonNode, which surfaces as HttpMessageConversionException.
			String raw = restClient
					.get()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/field", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(String.class);
			JsonNode fields = raw == null || raw.isBlank() ? null : mapper.readTree(raw);
			if (fields == null || !fields.isArray()) {
				return List.of();
			}
			List<JsonNode> out = new ArrayList<>();
			for (JsonNode field : fields) {
				out.add(field);
			}
			return out;
		} catch (Exception ex) {
			log.warn("jira field discovery cloudId={} result=FETCH_FAILED type={}", cloudId, ex.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * Shared story-points/sprint payload parsing for both bulk sync ({@link
	 * JiraOAuthClient#searchIssues}), single-issue reads ({@link #getIssue}), and webhook
	 * projection — one payload-shape audit, not three divergent implementations.
	 *
	 * <p>{@code authoritative} controls the resulting {@code storyPointsProvided}/{@code
	 * sprintProvided} flags: a full/authoritative fetch (bulk sync, single-issue) explicitly
	 * requested these fields from Jira, so whatever the payload says (including absence, meaning
	 * cleared) is the true current value. A webhook does not control what Jira includes in its
	 * payload, so for a non-authoritative call the flags instead reflect whether the payload
	 * actually carried a value for the dynamically-resolved field — a payload that simply omits
	 * the field must never be treated as "Jira cleared this field".
	 */
	public static IssueSummary toSummary(JsonNode issue, String storyField, String sprintField, boolean authoritative) {
		JsonNode fields = issue.path("fields");
		JsonNode status = fields.path("status");
		JsonNode category = status.path("statusCategory");
		JsonNode type = fields.path("issuetype");
		JsonNode assignee = fields.path("assignee");
		JsonNode priority = fields.path("priority");
		Integer storyPoints = null;
		boolean storyPointsProvided = authoritative;
		if (storyField != null && !storyField.isBlank() && fields.has(storyField)) {
			storyPointsProvided = true;
			if (fields.get(storyField).canConvertToInt()) {
				storyPoints = fields.get(storyField).asInt();
			}
		}
		JsonNode sprintNode = fields.path("sprint");
		boolean sprintProvided = authoritative || fields.has("sprint");
		if ((sprintNode.isMissingNode() || sprintNode.isNull()) && sprintField != null && !sprintField.isBlank()) {
			if (fields.has(sprintField)) {
				sprintProvided = true;
			}
			sprintNode = fields.path(sprintField);
		}
		String sprintId = null;
		String sprintName = null;
		String sprintState = null;
		if (sprintNode.isArray() && !sprintNode.isEmpty()) {
			JsonNode last = sprintNode.get(sprintNode.size() - 1);
			sprintId = text(last, "id");
			if (sprintId == null && last != null && last.isNumber()) {
				sprintId = last.asText();
			}
			sprintName = text(last, "name");
			sprintState = text(last, "state");
		} else if (sprintNode.isObject()) {
			sprintId = text(sprintNode, "id");
			sprintName = text(sprintNode, "name");
			sprintState = text(sprintNode, "state");
		}
		return new IssueSummary(
				text(issue, "id"),
				text(issue, "key"),
				text(fields, "summary"),
				text(status, "id"),
				text(status, "name"),
				text(category, "key"),
				text(type, "name"),
				text(type, "id"),
				text(assignee, "accountId"),
				text(assignee, "displayName"),
				text(priority, "id"),
				text(priority, "name"),
				storyPoints,
				descriptionText(fields.get("description")),
				sprintId,
				sprintName,
				sprintState,
				text(fields, "created"),
				text(fields, "updated"),
				storyPointsProvided,
				sprintProvided);
	}

	private ObjectNode plainAdf(String text) {
		ObjectNode doc = mapper.createObjectNode();
		doc.put("type", "doc");
		doc.put("version", 1);
		ArrayNode content = doc.putArray("content");
		ObjectNode paragraph = content.addObject();
		paragraph.put("type", "paragraph");
		ArrayNode pContent = paragraph.putArray("content");
		ObjectNode textNode = pContent.addObject();
		textNode.put("type", "text");
		textNode.put("text", text);
		return doc;
	}

	private static String descriptionText(JsonNode node) {
		if (node == null || node.isNull() || node.isMissingNode()) {
			return null;
		}
		if (node.isTextual()) {
			String text = node.asText();
			return text == null || text.isBlank() ? null : text;
		}
		StringBuilder out = new StringBuilder();
		appendAdf(node, out);
		String text = out.toString().trim();
		return text.isEmpty() ? null : text;
	}

	private static void appendAdf(JsonNode node, StringBuilder out) {
		if (node == null || node.isNull()) {
			return;
		}
		if (node.isTextual()) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(node.asText());
			return;
		}
		if (node.has("text") && node.get("text").isTextual()) {
			if (!out.isEmpty()) {
				out.append(' ');
			}
			out.append(node.get("text").asText());
		}
		if (node.has("content") && node.get("content").isArray()) {
			for (JsonNode child : node.get("content")) {
				appendAdf(child, out);
			}
		}
	}

	private static String text(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText(null);
		return text == null || text.isBlank() ? null : text;
	}

	private IntegrationException mapIssueFailure(String operation, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		if (status == 404) {
			return issueNotFound();
		}
		if (status == 401) {
			return new IntegrationException(
					IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Jira access token rejected.");
		}
		return new IntegrationException(
				IntegrationErrorCode.INTEGRATION_UNAVAILABLE, HttpStatus.BAD_GATEWAY, "Jira issue request failed.");
	}

	private IntegrationException mapWriteFailure(
			String operation, IntegrationErrorCode defaultCode, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		if (status == 401) {
			return new IntegrationException(
					IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Jira access token rejected.");
		}
		if (status == 403) {
			return new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Jira write was not permitted.");
		}
		if (status == 404) {
			return new IntegrationException(defaultCode, HttpStatus.NOT_FOUND, "Jira resource was not found.");
		}
		if (status == 400) {
			return new IntegrationException(
					IntegrationErrorCode.JIRA_FIELD_INVALID, HttpStatus.BAD_REQUEST, "Jira rejected the field update.");
		}
		return new IntegrationException(defaultCode, HttpStatus.BAD_GATEWAY, "Jira write failed.");
	}

	private static IntegrationException issueNotFound() {
		return new IntegrationException(
				IntegrationErrorCode.JIRA_ISSUE_NOT_FOUND, HttpStatus.NOT_FOUND, "Jira issue was not found.");
	}

	public record CreatedIssue(String id, String key) {}

	public record TransitionOption(String id, String name, String toStatusId, String toStatusName) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record CreatedIssueResponse(String id, String key) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TransitionsResponse(List<TransitionApi> transitions) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TransitionApi(String id, String name, TransitionTo to) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TransitionTo(String id, String name) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SprintDetail(
			Long id,
			String name,
			String state,
			String startDate,
			String endDate,
			String completeDate,
			String goal,
			Long originBoardId) {}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record SprintListResponse(List<SprintDetail> values) {}

	public record EstimationInfo(boolean supported, String fieldId, String fieldName, Integer value) {}

	public record IssueTypeOption(String id, String name, String description) {}

	public record PriorityOption(String id, String name) {}

	public record AssignableUserOption(String accountId, String displayName) {}
}

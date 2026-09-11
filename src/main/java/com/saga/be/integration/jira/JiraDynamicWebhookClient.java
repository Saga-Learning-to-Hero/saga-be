package com.saga.be.integration.jira;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

/**
 * Jira Cloud OAuth dynamic webhook REST client ({@code /rest/api/3/webhook*}).
 */
@Component
@Profile("!test")
public class JiraDynamicWebhookClient {

	private final RestClient restClient;
	private final ObjectMapper mapper;

	public JiraDynamicWebhookClient(RestClient integrationRestClient, ObjectMapper mapper) {
		this.restClient = integrationRestClient;
		this.mapper = mapper;
	}

	public List<RegisteredWebhook> listWebhooks(String accessToken, String cloudId) {
		try {
			JsonNode body = restClient
					.get()
					.uri(
							"https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/webhook?startAt=0&maxResults=100",
							cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.retrieve()
					.body(JsonNode.class);
			List<RegisteredWebhook> out = new ArrayList<>();
			if (body == null) {
				return out;
			}
			JsonNode values = body.path("values");
			if (!values.isArray()) {
				return out;
			}
			for (JsonNode node : values) {
				Long id = node.path("id").isNumber() ? node.path("id").asLong() : null;
				if (id == null) {
					continue;
				}
				out.add(new RegisteredWebhook(
						id,
						text(node, "url"),
						text(node, "jqlFilter"),
						text(node, "expirationDate"),
						events(node.path("events"))));
			}
			return out;
		} catch (RestClientResponseException ex) {
			throw mapFailure("listWebhooks", ex);
		}
	}

	public long registerWebhook(
			String accessToken, String cloudId, String callbackUrl, String jqlFilter, List<String> events) {
		ObjectNode body = mapper.createObjectNode();
		body.put("url", callbackUrl);
		ArrayNode webhooks = body.putArray("webhooks");
		ObjectNode webhook = webhooks.addObject();
		webhook.put("jqlFilter", jqlFilter);
		ArrayNode eventArr = webhook.putArray("events");
		for (String event : events) {
			eventArr.add(event);
		}
		try {
			JsonNode response = restClient
					.post()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/webhook", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
			Long created = firstCreatedId(response);
			if (created == null) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED,
						HttpStatus.BAD_GATEWAY,
						"Jira dynamic webhook registration returned no webhook id.");
			}
			return created;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapFailure("registerWebhook", ex);
		}
	}

	public String refreshWebhooks(String accessToken, String cloudId, List<Long> webhookIds) {
		ObjectNode body = mapper.createObjectNode();
		ArrayNode ids = body.putArray("webhookIds");
		for (Long id : webhookIds) {
			ids.add(id);
		}
		try {
			JsonNode response = restClient
					.put()
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/webhook/refresh", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.body(JsonNode.class);
			String expiration = response == null ? null : text(response, "expirationDate");
			if (expiration == null || expiration.isBlank()) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED,
						HttpStatus.BAD_GATEWAY,
						"Jira webhook refresh returned no expirationDate.");
			}
			return expiration;
		} catch (IntegrationException ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			throw mapFailure("refreshWebhooks", ex);
		}
	}

	public void deleteWebhooks(String accessToken, String cloudId, List<Long> webhookIds) {
		if (webhookIds == null || webhookIds.isEmpty()) {
			return;
		}
		ObjectNode body = mapper.createObjectNode();
		ArrayNode ids = body.putArray("webhookIds");
		for (Long id : webhookIds) {
			ids.add(id);
		}
		try {
			restClient
					.method(org.springframework.http.HttpMethod.DELETE)
					.uri("https://api.atlassian.com/ex/jira/{cloudId}/rest/api/3/webhook", cloudId)
					.header("Authorization", "Bearer " + accessToken)
					.contentType(MediaType.APPLICATION_JSON)
					.body(body)
					.retrieve()
					.toBodilessEntity();
		} catch (RestClientResponseException ex) {
			throw mapFailure("deleteWebhooks", ex);
		}
	}

	private static Long firstCreatedId(JsonNode response) {
		if (response == null) {
			return null;
		}
		JsonNode results = response.path("webhookRegistrationResult");
		if (!results.isArray() && response.isArray()) {
			results = response;
		}
		if (!results.isArray()) {
			return null;
		}
		for (JsonNode item : results) {
			if (item.has("createdWebhookId") && item.get("createdWebhookId").canConvertToLong()) {
				return item.get("createdWebhookId").asLong();
			}
			if (item.has("errors")) {
				continue;
			}
		}
		return null;
	}

	private static List<String> events(JsonNode node) {
		List<String> out = new ArrayList<>();
		if (node != null && node.isArray()) {
			for (JsonNode item : node) {
				String text = item.asText(null);
				if (text != null && !text.isBlank()) {
					out.add(text);
				}
			}
		}
		return out;
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

	private IntegrationException mapFailure(String operation, RestClientResponseException ex) {
		int status = ex.getStatusCode().value();
		IntegrationErrorCode code = operation.startsWith("refresh")
				? IntegrationErrorCode.JIRA_WEBHOOK_REFRESH_FAILED
				: IntegrationErrorCode.JIRA_WEBHOOK_REGISTER_FAILED;
		if (status == 401) {
			return new IntegrationException(
					IntegrationErrorCode.JIRA_UNAUTHORIZED, HttpStatus.UNAUTHORIZED, "Jira access token rejected.");
		}
		if (status == 403) {
			return new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "Jira webhook operation was not permitted.");
		}
		return new IntegrationException(code, HttpStatus.BAD_GATEWAY, "Jira webhook " + operation + " failed.");
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record RegisteredWebhook(Long id, String url, String jqlFilter, String expirationDate, List<String> events) {}
}

package com.saga.be.service.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class AuditRedactor {

	public static final String REDACTED = "[REDACTED]";

	private static final Set<String> SENSITIVE_KEYS = Set.of(
			"password",
			"password_hash",
			"passwordhash",
			"newpassword",
			"confirmpassword",
			"client_secret",
			"clientsecret",
			"private_key",
			"privatekey",
			"access_token",
			"accesstoken",
			"refresh_token",
			"refreshtoken",
			"installation_token",
			"installationtoken",
			"jira_token",
			"jiratoken",
			"webhook_secret",
			"webhooksecret",
			"authorization",
			"cookie",
			"saga_session",
			"sagasession",
			"csrf",
			"xsrf",
			"x-xsrf-token",
			"session_id",
			"sessionid",
			"redis_password",
			"rabbitmq_password",
			"db_password",
			"mysql_password",
			"mail_password",
			"mailpassword",
			"smtp_password",
			"smtppassword");

	private final ObjectMapper mapper;

	public AuditRedactor(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public String redactJson(String json) {
		if (json == null || json.isBlank()) {
			return json;
		}
		try {
			JsonNode tree = mapper.readTree(json);
			redact(tree);
			return mapper.writeValueAsString(tree);
		} catch (Exception ex) {
			return REDACTED;
		}
	}

	public Map<String, Object> redactMap(Map<String, Object> source) {
		if (source == null) {
			return Map.of();
		}
		try {
			JsonNode tree = mapper.valueToTree(source);
			redact(tree);
			@SuppressWarnings("unchecked")
			Map<String, Object> redacted = mapper.convertValue(tree, Map.class);
			return redacted;
		} catch (IllegalArgumentException ex) {
			return Map.of("redacted", true);
		}
	}

	private void redact(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			ObjectNode object = (ObjectNode) node;
			Iterator<String> names = object.fieldNames();
			while (names.hasNext()) {
				String name = names.next();
				if (isSensitive(name)) {
					object.put(name, REDACTED);
				} else {
					redact(object.get(name));
				}
			}
		} else if (node.isArray()) {
			ArrayNode array = (ArrayNode) node;
			for (JsonNode child : array) {
				redact(child);
			}
		}
	}

	static boolean isSensitive(String rawName) {
		if (rawName == null) {
			return false;
		}
		String key = rawName.toLowerCase(Locale.ROOT).replace("-", "").replace(" ", "");
		if (SENSITIVE_KEYS.contains(key)) {
			return true;
		}
		return key.contains("password")
				|| key.contains("secret")
				|| key.contains("privatekey")
				|| key.contains("accesstoken")
				|| key.contains("refreshtoken")
				|| key.contains("authorization")
				|| key.equals("cookie");
	}
}

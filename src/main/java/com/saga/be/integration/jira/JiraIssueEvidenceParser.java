package com.saga.be.integration.jira;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class JiraIssueEvidenceParser {

	public record IssueRef(String issueId, String issueKey, String projectId, String projectKey) {}

	public record Attachment(String id, String filename, String mimeType, long sizeBytes, String authorAccountId) {}

	public record RemoteLink(String id, String url, String title) {}

	private JiraIssueEvidenceParser() {}

	public static Optional<IssueRef> fromWebhook(JsonNode root) {
		if (root == null) {
			return Optional.empty();
		}
		JsonNode issue = root.path("issue");
		if (issue.isMissingNode() || issue.isNull()) {
			issue = root;
		}
		return fromIssue(issue);
	}

	public static Optional<IssueRef> fromIssue(JsonNode issue) {
		if (issue == null || issue.isMissingNode() || issue.isNull()) {
			return Optional.empty();
		}
		String issueId = text(issue, "id");
		String issueKey = text(issue, "key");
		if (issueId == null && issueKey == null) {
			return Optional.empty();
		}
		JsonNode project = issue.path("fields").path("project");
		return Optional.of(new IssueRef(issueId, issueKey, text(project, "id"), text(project, "key")));
	}

	public static List<Attachment> attachments(JsonNode issue) {
		List<Attachment> out = new ArrayList<>();
		JsonNode node = issue == null ? null : issue.path("fields").path("attachment");
		if (node == null || !node.isArray()) {
			return out;
		}
		for (JsonNode item : node) {
			String id = text(item, "id");
			String filename = text(item, "filename");
			if (id == null || filename == null) {
				continue;
			}
			long size = item.path("size").asLong(0L);
			out.add(new Attachment(id, filename, text(item, "mimeType"), size, text(item.path("author"), "accountId")));
		}
		return out;
	}

	public static List<RemoteLink> remoteLinks(JsonNode array) {
		List<RemoteLink> out = new ArrayList<>();
		if (array == null || !array.isArray()) {
			return out;
		}
		for (JsonNode item : array) {
			String id = item.path("id").isNumber() ? String.valueOf(item.path("id").asLong()) : text(item, "id");
			JsonNode object = item.path("object");
			String url = text(object, "url");
			if (id == null || url == null) {
				continue;
			}
			out.add(new RemoteLink(id, url, text(object, "title")));
		}
		return out;
	}

	public static String summary(JsonNode issue) {
		return text(issue.path("fields"), "summary");
	}

	public static String statusName(JsonNode issue) {
		return text(issue.path("fields").path("status"), "name");
	}

	public static String statusId(JsonNode issue) {
		return text(issue.path("fields").path("status"), "id");
	}

	public static String statusCategory(JsonNode issue) {
		JsonNode category = issue.path("fields").path("status").path("statusCategory");
		String key = text(category, "key");
		return key != null ? key : text(category, "name");
	}

	public static String issueTypeName(JsonNode issue) {
		return text(issue.path("fields").path("issuetype"), "name");
	}

	public static List<String> labels(JsonNode issue) {
		List<String> out = new ArrayList<>();
		JsonNode node = issue.path("fields").path("labels");
		if (node != null && node.isArray()) {
			for (JsonNode label : node) {
				if (label.isTextual() && !label.asText().isBlank()) {
					out.add(label.asText());
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
		if (value.isMissingNode() || value.isNull() || !value.isValueNode()) {
			return null;
		}
		String text = value.asText();
		return text == null || text.isBlank() ? null : text;
	}
}

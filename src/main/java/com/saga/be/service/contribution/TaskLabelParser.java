package com.saga.be.service.contribution;

import java.util.ArrayList;
import java.util.List;

public final class TaskLabelParser {

	private TaskLabelParser() {}

	public static List<String> parse(String labelsJson) {
		if (labelsJson == null || labelsJson.isBlank()) {
			return List.of();
		}
		String trimmed = labelsJson.trim();
		if (trimmed.startsWith("[")) {
			return parseJsonArray(trimmed);
		}
		List<String> labels = new ArrayList<>();
		for (String part : trimmed.split("[,;]")) {
			String label = unquote(part.trim());
			if (!label.isEmpty()) {
				labels.add(label);
			}
		}
		return labels;
	}

	private static List<String> parseJsonArray(String json) {
		List<String> labels = new ArrayList<>();
		int i = 1;
		while (i < json.length()) {
			char ch = json.charAt(i);
			if (ch == '"') {
				int end = json.indexOf('"', i + 1);
				if (end < 0) {
					break;
				}
				labels.add(json.substring(i + 1, end));
				i = end + 1;
			} else {
				i++;
			}
		}
		return labels;
	}

	private static String unquote(String value) {
		if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
			return value.substring(1, value.length() - 1);
		}
		return value;
	}
}

package com.saga.be.graph;

import java.util.UUID;

public final class SagaGraphIds {

	private SagaGraphIds() {}

	public static String student(UUID id) {
		return "student:" + id;
	}

	public static String team(UUID id) {
		return "team:" + id;
	}

	public static String project(UUID id) {
		return "project:" + id;
	}

	public static String sprint(UUID id) {
		return "sprint:" + id;
	}

	public static String task(UUID id) {
		return "task:" + id;
	}

	public static String commit(UUID id) {
		return "commit:" + id;
	}

	public static String identity(UUID projectId, String provider, String subject) {
		return "identity:" + projectId + ":" + provider + ":" + subject;
	}

	public static String criterion(String name) {
		return switch (name) {
			case "CODE" -> "crit_code";
			case "TEST" -> "crit_test";
			case "DOCUMENT" -> "crit_document";
			case "RESEARCH" -> "crit_research";
			default -> throw new IllegalArgumentException("Unknown criterion: " + name);
		};
	}

	public static String edge(String type, String source, String target) {
		return type + ":" + source + ":" + target;
	}

	public static String reviewedEdge(String source, String target, UUID sprintId) {
		return "REVIEWED:" + source + ":" + target + ":" + sprintId;
	}
}

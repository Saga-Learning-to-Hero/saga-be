package com.saga.be.service.assistant;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The facts selected for one question: everything the assistant may cite, already limited to what
 * the asker can see in the app. {@code hints} point at the facts a backend-built (no AI) answer
 * summarises when the AI cannot answer.
 */
public record AssistantFacts(List<Fact> items, Viewer viewer, LocalDate today, Hints hints) {

	public static final String PROJECT = "PROJECT";
	public static final String SPRINT = "SPRINT";
	public static final String TASK = "TASK";
	public static final String MEMBER = "MEMBER";
	public static final String DELAY_CASE = "DELAY_CASE";
	public static final String COMMIT = "COMMIT";

	/**
	 * One citable fact. {@code payload} is what the model reads; {@code label}, {@code taskId} and
	 * {@code sha} are what the FE shows and links to when the fact is cited.
	 */
	public record Fact(String kind, UUID id, String label, UUID taskId, String sha, Map<String, Object> payload) {}

	/** Who asks. {@code teamRole} is LEADER / MEMBER for a student, null for a lecturer. */
	public record Viewer(UUID userId, String role, String teamRole, String name, UUID memberId) {}

	/** Fact ids, in order, that a backend-built answer mentions. */
	public record Hints(
			UUID projectId, List<UUID> sprintIds, List<UUID> overdueTaskIds, List<UUID> matchedTaskIds, List<UUID> matchedMemberIds) {}

	public Optional<Fact> find(String kind, UUID id) {
		return items.stream().filter(fact -> fact.kind().equals(kind) && Objects.equals(fact.id(), id)).findFirst();
	}
}

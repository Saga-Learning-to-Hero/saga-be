package com.saga.be.service.confirmation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

public final class EvidenceHasher {

	private static final ObjectMapper JSON = new ObjectMapper();

	private EvidenceHasher() {}

	public static String sha256(String snapshotJson) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(snapshotJson.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	/**
	 * Deterministic JSON snapshot persisted on {@code contribution_confirmation.evidence_snapshot_json}.
	 * Must be valid JSON: the column is MySQL {@code JSON}, and Hibernate binds with {@code CAST(? AS JSON)}.
	 * A Java {@link TreeMap#toString()} is <em>not</em> JSON and is rejected as MySQL error 3141.
	 *
	 * <p>{@code commitShas} and {@code pullRequests} are evidence <em>sets</em> (which SHAs/PRs were
	 * attested), not ordered timelines. Items are sorted lexicographically so request/provider order
	 * cannot change the hash. Duplicate entries are preserved: the confirm API never documented
	 * uniqueness, so {@code ["a","a"]} stays distinct from {@code ["a"]}.
	 */
	public static String canonical(String taskKey, List<String> commitShas, List<String> prNumbers, String jiraStatus) {
		TreeMap<String, Object> body = new TreeMap<>();
		body.put("commits", canonicalizeItems(commitShas));
		body.put("jiraStatus", jiraStatus);
		body.put("pullRequests", canonicalizeItems(prNumbers));
		body.put("taskKey", taskKey);
		try {
			return JSON.writeValueAsString(body);
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Evidence snapshot JSON unavailable", ex);
		}
	}

	private static List<String> canonicalizeItems(List<String> values) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		List<String> sorted = new ArrayList<>(values);
		sorted.sort(Comparator.nullsFirst(Comparator.naturalOrder()));
		return List.copyOf(sorted);
	}
}

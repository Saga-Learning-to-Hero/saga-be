package com.saga.be.service.confirmation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

public final class EvidenceHasher {

	private EvidenceHasher() {}

	public static String sha256(String snapshotJson) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(snapshotJson.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}

	public static String canonical(String taskKey, List<String> commitShas, List<String> prNumbers, String jiraStatus) {
		TreeMap<String, Object> body = new TreeMap<>();
		body.put("taskKey", taskKey);
		body.put("commits", commitShas == null ? List.of() : List.copyOf(commitShas));
		body.put("pullRequests", prNumbers == null ? List.of() : List.copyOf(prNumbers));
		body.put("jiraStatus", jiraStatus);
		return body.toString();
	}
}

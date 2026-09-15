package com.saga.be.service.confirmation;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

class EvidenceHasherTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SHA = "d362a3532ceaebe36c78f7e151e881daae8c6277";
	private static final String SHA_B = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

	@Test
	void canonicalIsValidJsonWithSortedKeysEmptyPullRequestsAndCommitSha() throws Exception {
		String snapshot = EvidenceHasher.canonical("SAGA-1", List.of(SHA), List.of(), "TODO");
		JsonNode node = MAPPER.readTree(snapshot);
		assertTrue(node.isObject());
		assertEquals(SHA, node.get("commits").get(0).asText());
		assertEquals(0, node.get("pullRequests").size());
		assertEquals("TODO", node.get("jiraStatus").asText());
		assertEquals("SAGA-1", node.get("taskKey").asText());
		assertEquals(
				"{\"commits\":[\"" + SHA + "\"],\"jiraStatus\":\"TODO\",\"pullRequests\":[],\"taskKey\":\"SAGA-1\"}",
				snapshot);
	}

	@Test
	void reversedCommitAndPrOrder_sameCanonicalJsonAndHash() {
		String forward = EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA_B), List.of("12", "3"), "TODO");
		String reversed = EvidenceHasher.canonical("SAGA-1", List.of(SHA_B, SHA), List.of("3", "12"), "TODO");
		assertEquals(forward, reversed);
		assertEquals(EvidenceHasher.sha256(forward), EvidenceHasher.sha256(reversed));
		assertTrue(forward.contains("\"" + SHA_B + "\",\"" + SHA + "\""));
		assertTrue(forward.contains("\"12\",\"3\""));
	}

	@Test
	void duplicatesArePreservedAfterSort() {
		String once = EvidenceHasher.canonical("SAGA-1", List.of(SHA), List.of(), "TODO");
		String twice = EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA), List.of(), "TODO");
		assertFalse(once.equals(twice));
		assertTrue(twice.contains("\"" + SHA + "\",\"" + SHA + "\""));
		assertEquals(
				EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA), List.of(), "TODO"),
				EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA), List.of(), "TODO"));
	}

	@Test
	void canonicalTreatsNullListsAsEmptyArraysAndNullStatusAsJsonNull() throws Exception {
		JsonNode node = MAPPER.readTree(EvidenceHasher.canonical(null, null, null, null));
		assertTrue(node.get("commits").isArray());
		assertEquals(0, node.get("commits").size());
		assertTrue(node.get("pullRequests").isEmpty());
		assertTrue(node.get("jiraStatus").isNull());
		assertTrue(node.get("taskKey").isNull());
		assertEquals(
				EvidenceHasher.canonical(null, List.of(), List.of(), null),
				EvidenceHasher.canonical(null, null, null, null));
	}

	@Test
	void jacksonEscapesQuotesAndBackslashesInTaskKey() throws Exception {
		String snapshot = EvidenceHasher.canonical("SAGA-1\"\\x", List.of(), List.of(), "TODO");
		JsonNode node = MAPPER.readTree(snapshot);
		assertEquals("SAGA-1\"\\x", node.get("taskKey").asText());
		assertTrue(snapshot.contains("\\\""));
		assertTrue(snapshot.contains("\\\\"));
	}

	@Test
	void repeatedInvocationIsByteIdentical() {
		String first = EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA_B), List.of(), "TODO");
		String second = EvidenceHasher.canonical("SAGA-1", List.of(SHA, SHA_B), List.of(), "TODO");
		assertEquals(first, second);
		assertArrayEquals(first.getBytes(StandardCharsets.UTF_8), second.getBytes(StandardCharsets.UTF_8));
	}

	@Test
	void javaMapToString_theProductionBugShape_isNotValidJson() {
		TreeMap<String, Object> body = new TreeMap<>();
		body.put("commits", List.of(SHA));
		body.put("jiraStatus", "TODO");
		body.put("pullRequests", List.of());
		body.put("taskKey", "SAGA-1");
		String legacy = body.toString();
		assertTrue(legacy.startsWith("{commits="));
		assertFalse(legacy.contains("\"commits\""));
		assertThrows(JsonProcessingException.class, () -> MAPPER.readTree(legacy));
	}

	@Test
	void hashIsStableForSameCanonicalJsonAndChangesWhenShaChanges() {
		String first = EvidenceHasher.canonical("SAGA-1", List.of("aaa"), List.of("1"), "DONE");
		String second = EvidenceHasher.canonical("SAGA-1", List.of("aaa"), List.of("1"), "DONE");
		assertEquals(EvidenceHasher.sha256(first), EvidenceHasher.sha256(second));
		assertFalse(EvidenceHasher.sha256(first)
				.equals(EvidenceHasher.sha256(EvidenceHasher.canonical("SAGA-1", List.of("bbb"), List.of("1"), "DONE"))));
	}
}

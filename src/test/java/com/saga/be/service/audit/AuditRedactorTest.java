package com.saga.be.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AuditRedactorTest {

	private final AuditRedactor redactor = new AuditRedactor(new ObjectMapper());

	@Test
	void passwordAndTokenFieldsAreRedacted() {
		String json = redactor.redactJson(
				"""
				{"password":"secret","refresh_token":"rt","access_token":"at","login":"alice","providerSubject":"123"}
				""");
		assertTrue(json.contains(AuditRedactor.REDACTED));
		assertTrue(json.contains("alice"));
		assertFalse(json.contains("secret"));
		assertFalse(json.contains("\"rt\""));
		Map<String, Object> map = redactor.redactMap(Map.of(
				"password_hash", "argon", "client_secret", "cs", "fullName", "Alice"));
		assertEquals(AuditRedactor.REDACTED, map.get("password_hash"));
		assertEquals("Alice", map.get("fullName"));
	}

	@Test
	void cookieAndAuthorizationAreRedacted() {
		String json = redactor.redactJson("{\"Authorization\":\"Bearer abc\",\"Cookie\":\"SAGA_SESSION=xyz\",\"ok\":true}");
		assertFalse(json.contains("Bearer abc"));
		assertFalse(json.contains("SAGA_SESSION=xyz"));
	}
}

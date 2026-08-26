package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AuditApiSurfaceTest {

	@Test
	void noUpdateOrDeleteAuditApiExists() throws Exception {
		Path root = Path.of("src/main/java/com/saga/be");
		StringBuilder sources = new StringBuilder();
		try (var files = Files.walk(root)) {
			files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
				try {
					sources.append(Files.readString(path)).append('\n');
				} catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			});
		}
		String code = sources.toString();
		assertFalse(code.contains("/api/audit") && code.contains("DeleteMapping"));
		assertFalse(code.contains("auditLogs.delete"));
		assertFalse(code.contains("AuditLogRepository") && code.contains(".delete("));
		assertTrue(code.contains("class AuditService"));
	}
}

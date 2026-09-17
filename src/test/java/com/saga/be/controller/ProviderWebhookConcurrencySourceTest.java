package com.saga.be.controller;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProviderWebhookConcurrencySourceTest {

	@Test
	void jiraEvidenceDoesNotUseCommonPool() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/controller/ProviderWebhookController.java"))
				.replace("\r\n", "\n");
		assertFalse(source.contains("CompletableFuture.runAsync"));
		assertFalse(source.contains("commonPool"));
		assertTrue(source.contains("jiraEvidenceJobs.submit"));
		assertTrue(source.contains("RejectedExecutionException"));
		assertTrue(source.contains("SERVICE_UNAVAILABLE"));
	}
}

package com.saga.be.dto.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SelectJiraIntegrationContractTest {

	@Test
	void putJiraUsesNamedRequestProperties() throws Exception {
		String dto = Files.readString(Path.of("src/main/java/com/saga/be/dto/integration/SelectJiraIntegrationRequest.java"));
		assertTrue(dto.contains("String cloudId"));
		assertTrue(dto.contains("String jiraProjectId"));
		assertTrue(dto.contains("String boardId"));
		assertFalse(dto.contains("jiraBoardId"));
		assertFalse(dto.contains("additionalProp"));
		String controller = Files.readString(Path.of("src/main/java/com/saga/be/controller/ProjectIntegrationController.java"));
		assertTrue(controller.contains("@Valid @RequestBody SelectJiraIntegrationRequest"));
		assertFalse(controller.contains("Map<String, String> selection"));
	}
}

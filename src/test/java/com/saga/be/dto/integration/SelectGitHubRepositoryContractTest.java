package com.saga.be.dto.integration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SelectGitHubRepositoryContractTest {

	@Test
	void putRepositoriesUsesNamedRequestProperties() throws Exception {
		String dto = Files.readString(
				Path.of("src/main/java/com/saga/be/dto/integration/SelectGitHubRepositoryRequest.java"));
		assertTrue(dto.contains("Long repositoryId"));
		assertTrue(dto.contains("RepositoryRole role"));
		assertTrue(dto.contains("GitHub repository ID returned by GET /github/repositories"));
		assertFalse(dto.contains("fullName"));
		assertFalse(dto.contains("additionalProp"));
		String controller = Files.readString(
				Path.of("src/main/java/com/saga/be/controller/ProjectIntegrationController.java"));
		assertTrue(controller.contains("List<@Valid SelectGitHubRepositoryRequest>"));
		assertFalse(controller.contains("List<Map<String, Object>> body"));
	}
}

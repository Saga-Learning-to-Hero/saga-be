package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ProjectFetchQueryTest {

	@Test
	void githubCallbackLoadsCourseGraphBeforeAudit() throws Exception {
		String repository = Files.readString(Path.of("src/main/java/com/saga/be/repository/ProjectRepository.java"));
		assertTrue(repository.contains("findFetchedById"));
		assertTrue(repository.contains("JOIN FETCH p.course"));
		assertTrue(repository.contains("JOIN FETCH c.academicClass"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/identity/ProjectIntegrationService.java"));
		assertTrue(service.contains("requireFetchedProject"));
		assertTrue(service.contains("findFetchedById"));
		assertTrue(service.contains("@Transactional"));
		assertTrue(service.contains("public String completeGithubInstallation"));
		assertTrue(service.contains("audit.record"));
	}
}

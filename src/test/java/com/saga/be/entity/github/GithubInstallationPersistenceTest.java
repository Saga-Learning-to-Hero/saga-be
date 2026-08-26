package com.saga.be.entity.github;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class GithubInstallationPersistenceTest {

	@Test
	void installationAccessTokenIsNotADomainColumn() throws Exception {
		String source = Files.readString(Path.of("src/main/java/com/saga/be/entity/github/GithubInstallation.java"));
		assertFalse(source.toLowerCase().contains("access_token"));
		assertFalse(source.toLowerCase().contains("installationtoken"));
		String gitRepo = Files.readString(Path.of("src/main/java/com/saga/be/entity/github/GitRepo.java"));
		assertFalse(gitRepo.toLowerCase().contains("access_token"));
	}
}

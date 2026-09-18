package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class LecturerDirectoryFetchQueryTest {

	@Test
	void directoryQueryFetchesUserAccountInOneJoin() throws Exception {
		String repository = Files.readString(Path.of("src/main/java/com/saga/be/repository/LecturerProfileRepository.java"));
		assertTrue(repository.contains("searchDirectory("));
		assertTrue(repository.contains("searchDirectoryPage("));
		assertTrue(repository.contains("join fetch p.userAccount u"));
		assertTrue(repository.contains("order by coalesce(u.fullName, u.email) asc, u.email asc, p.id asc"));
		assertTrue(repository.contains("select count(p.id)"));
		assertTrue(repository.contains("escape '\\\\'") || repository.contains("escape '\\'"));
		assertFalse(repository.contains("findAll()"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/academic/AdminLecturerService.java"));
		assertTrue(service.contains("searchDirectory("));
		assertTrue(service.contains("searchDirectoryPage("));
		assertFalse(service.contains("findAll("));
		assertFalse(service.contains("findById("));
	}
}

package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class TeamMemberFetchQueryTest {

	@Test
	void integrationMembershipQueryFetchesEnrollmentGraph() throws Exception {
		String members = Files.readString(Path.of("src/main/java/com/saga/be/repository/TeamMemberRepository.java"));
		assertTrue(members.contains("findFetchedByTeam_Id"));
		assertTrue(members.contains("JOIN FETCH m.courseEnrollment"));
		assertTrue(members.contains("JOIN FETCH e.studentProfile"));
		assertTrue(members.contains("JOIN FETCH p.userAccount"));
		assertTrue(members.contains("JOIN FETCH t.course"));
		assertTrue(members.contains("LEFT JOIN FETCH t.project"));
		assertTrue(members.contains("existsByProjectIdAndUserId"));
		String studentTeam = Files.readString(Path.of("src/main/java/com/saga/be/service/student/StudentTeamService.java"));
		assertTrue(studentTeam.contains("findFetchedByTeam_Id"));
		assertFalse(studentTeam.contains("findByTeam_Id("));
		String evidence = Files.readString(Path.of("src/main/java/com/saga/be/service/evidence/TaskEvidenceService.java"));
		assertTrue(evidence.contains("existsByProjectIdAndUserId"));
		assertFalse(evidence.contains("findByTeam_Id("));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/identity/ProjectIntegrationService.java"));
		assertTrue(service.contains("findFetchedByTeam_Id"));
		assertTrue(service.contains("@Transactional(readOnly = true)"));
		assertTrue(service.contains("public OAuthStartResponse startGithub"));
		assertTrue(service.contains("public OAuthStartResponse startJira"));
		String local = Files.readString(Path.of("src/main/resources/application-local.properties"));
		assertTrue(local.contains("spring.jpa.open-in-view=false"));
	}
}

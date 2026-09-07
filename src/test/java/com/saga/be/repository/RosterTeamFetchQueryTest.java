package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class RosterTeamFetchQueryTest {

	@Test
	void rosterStoreBatchesIdentityLookupsAndFetchesCourseGraph() throws Exception {
		String store = Files.readString(Path.of("src/main/java/com/saga/be/service/roster/JpaCourseRosterStore.java"));
		assertTrue(store.contains("findByEmailIn"));
		assertTrue(store.contains("findFetchedByStudentCodeUpperIn"));
		assertTrue(store.contains("findFetchedByUserAccount_IdIn"));
		assertTrue(store.contains("findFetchedByCourse_Id"));
		assertTrue(store.contains("findActiveFetchedById"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/roster/CourseRosterService.java"));
		assertTrue(service.contains("RosterLookups.preload"));
		assertTrue(service.contains("findUsersByEmails"));
		assertTrue(service.contains("listEnrollments"));
		assertTrue(service.contains("listInvitations"));
		assertFalse(service.contains("store.findUserByEmail("));
		assertFalse(service.contains("store.findStudentByCode("));
		String users = Files.readString(Path.of("src/main/java/com/saga/be/repository/UserAccountRepository.java"));
		assertTrue(users.contains("findByEmailIn"));
		String students = Files.readString(Path.of("src/main/java/com/saga/be/repository/StudentProfileRepository.java"));
		assertTrue(students.contains("JOIN FETCH p.userAccount"));
		assertTrue(students.contains("UPPER(p.studentCode) IN :codes"));
	}

	@Test
	void lecturerTeamListFetchesProjectUsedByListTeams() throws Exception {
		String teams = Files.readString(Path.of("src/main/java/com/saga/be/repository/TeamRepository.java"));
		assertTrue(teams.contains("findFetchedByCourse_IdOrderByTeamNoAsc"));
		assertTrue(teams.contains("LEFT JOIN FETCH t.project"));
		String store = Files.readString(Path.of("src/main/java/com/saga/be/service/team/JpaLecturerTeamStore.java"));
		assertTrue(store.contains("findFetchedByCourse_IdOrderByTeamNoAsc"));
		assertTrue(store.contains("findFetchedByCourse_Id"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/team/LecturerTeamService.java"));
		assertTrue(service.contains("store.listActiveEnrollments"));
		assertTrue(service.contains("membershipsByEnrollment"));
		assertTrue(service.contains("store.listTeams"));
	}
}

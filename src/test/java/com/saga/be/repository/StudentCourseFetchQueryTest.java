package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StudentCourseFetchQueryTest {

	@Test
	void studentCourseListFetchesCourseGraphAndOptionalTeam() throws Exception {
		String enrollments = Files.readString(Path.of("src/main/java/com/saga/be/repository/CourseEnrollmentRepository.java"));
		assertTrue(enrollments.contains("findFetchedByStudentProfile_IdAndEnrollmentStatus"));
		assertTrue(enrollments.contains("JOIN FETCH e.course"));
		assertTrue(enrollments.contains("JOIN FETCH c.subject"));
		assertTrue(enrollments.contains("JOIN FETCH c.academicClass"));
		assertTrue(enrollments.contains("JOIN FETCH c.semester"));
		assertTrue(enrollments.contains("e.enrollmentStatus = :status"));
		String members = Files.readString(Path.of("src/main/java/com/saga/be/repository/TeamMemberRepository.java"));
		assertTrue(members.contains("findFetchedByCourseEnrollment_IdIn"));
		assertTrue(members.contains("JOIN FETCH m.team"));
		assertTrue(members.contains("LEFT JOIN FETCH t.project"));
		assertTrue(members.contains("JOIN FETCH m.courseEnrollment"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/student/StudentCourseService.java"));
		assertTrue(service.contains("@Transactional(readOnly = true)"));
		assertTrue(service.contains("findFetchedByStudentProfile_IdAndEnrollmentStatus"));
		assertTrue(service.contains("findFetchedByCourseEnrollment_IdIn"));
		String local = Files.readString(Path.of("src/main/resources/application-local.properties"));
		assertTrue(local.contains("spring.jpa.open-in-view=false"));
	}
}

package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AcademicAdminFetchQueryTest {

	@Test
	void courseSearchFetchesGraphUsedByToCourse() throws Exception {
		String repository = Files.readString(Path.of("src/main/java/com/saga/be/repository/CourseRepository.java"));
		assertTrue(repository.contains("List<Course> search("));
		assertTrue(repository.contains("LEFT JOIN FETCH c.academicClass"));
		assertTrue(repository.contains("LEFT JOIN FETCH c.semester"));
		assertTrue(repository.contains("LEFT JOIN FETCH c.subject"));
		assertTrue(repository.contains("LEFT JOIN FETCH c.syllabusVersion"));
		assertTrue(repository.contains("LEFT JOIN FETCH c.instructor i"));
		assertTrue(repository.contains("LEFT JOIN FETCH i.userAccount"));
		String store = Files.readString(Path.of("src/main/java/com/saga/be/service/academic/JpaAcademicRuntimeStore.java"));
		assertTrue(store.contains("findActiveFetchedById"));
		String service = Files.readString(Path.of("src/main/java/com/saga/be/service/academic/AcademicRuntimeService.java"));
		assertTrue(service.contains("store.listCourses"));
		assertTrue(service.contains("this::toCourse"));
	}

	@Test
	void classListFetchesSemesterUsedByToClass() throws Exception {
		String repository = Files.readString(Path.of("src/main/java/com/saga/be/repository/AcademicClassRepository.java"));
		assertTrue(repository.contains("LEFT JOIN FETCH c.semester"));
		assertTrue(repository.contains("findByDeletedAtIsNullOrderByClassCodeAsc"));
		assertTrue(repository.contains("findBySemester_IdAndDeletedAtIsNullOrderByClassCodeAsc"));
	}

	@Test
	void syllabusDetailFetchesAssociationsUsedByToDetail() throws Exception {
		String syllabi = Files.readString(Path.of("src/main/java/com/saga/be/repository/SubjectSyllabusVersionRepository.java"));
		assertTrue(syllabi.contains("JOIN FETCH s.subject"));
		String activities = Files.readString(Path.of("src/main/java/com/saga/be/repository/SyllabusExpectedActivityRepository.java"));
		assertTrue(activities.contains("JOIN FETCH a.phase"));
		String deliverables =
				Files.readString(Path.of("src/main/java/com/saga/be/repository/SyllabusExpectedDeliverableRepository.java"));
		assertTrue(deliverables.contains("JOIN FETCH d.phase"));
		String phaseLinks =
				Files.readString(Path.of("src/main/java/com/saga/be/repository/SyllabusPhaseLearningOutcomeRepository.java"));
		assertTrue(phaseLinks.contains("JOIN FETCH l.phase"));
		assertTrue(phaseLinks.contains("JOIN FETCH l.learningOutcome"));
		String deliverableLinks = Files.readString(
				Path.of("src/main/java/com/saga/be/repository/SyllabusDeliverableLearningOutcomeRepository.java"));
		assertTrue(deliverableLinks.contains("JOIN FETCH l.deliverable"));
		assertTrue(deliverableLinks.contains("JOIN FETCH l.learningOutcome"));
		String unitLinks =
				Files.readString(Path.of("src/main/java/com/saga/be/repository/SyllabusLearningUnitOutcomeRepository.java"));
		assertTrue(unitLinks.contains("JOIN FETCH l.learningUnit"));
		assertTrue(unitLinks.contains("JOIN FETCH l.learningOutcome"));
	}
}

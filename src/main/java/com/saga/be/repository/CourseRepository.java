package com.saga.be.repository;

import com.saga.be.entity.academic.Course;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseRepository extends JpaRepository<Course, UUID> {

	boolean existsByAcademicClass_IdAndSubject_Id(UUID academicClassId, UUID subjectId);

	boolean existsBySemester_Id(UUID semesterId);

	boolean existsByAcademicClass_Id(UUID academicClassId);

	@Query(
			"""
			SELECT c FROM Course c
			WHERE c.deletedAt IS NULL
			AND (:semesterId IS NULL OR c.semester.id = :semesterId)
			AND (:academicClassId IS NULL OR c.academicClass.id = :academicClassId)
			AND (:subjectId IS NULL OR c.subject.id = :subjectId)
			AND (:lecturerId IS NULL OR c.instructor.id = :lecturerId)
			ORDER BY c.name ASC
			""")
	List<Course> search(
			@Param("semesterId") UUID semesterId,
			@Param("academicClassId") UUID academicClassId,
			@Param("subjectId") UUID subjectId,
			@Param("lecturerId") UUID lecturerId);

	@Query(
			"""
			SELECT c FROM Course c
			LEFT JOIN FETCH c.academicClass
			LEFT JOIN FETCH c.semester
			LEFT JOIN FETCH c.subject
			LEFT JOIN FETCH c.syllabusVersion
			LEFT JOIN FETCH c.instructor i
			LEFT JOIN FETCH i.userAccount
			WHERE c.id = :courseId AND c.deletedAt IS NULL
			""")
	Optional<Course> findActiveFetchedById(@Param("courseId") UUID courseId);
}


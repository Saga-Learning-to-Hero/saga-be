package com.saga.be.repository;

import com.saga.be.entity.academic.Course;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
			LEFT JOIN FETCH c.academicClass
			LEFT JOIN FETCH c.semester
			LEFT JOIN FETCH c.subject
			LEFT JOIN FETCH c.syllabusVersion
			LEFT JOIN FETCH c.instructor i
			LEFT JOIN FETCH i.userAccount
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

	/**
	 * Admin course directory IDs. Callers must pass an unsorted {@link Pageable}; sort is in JPQL.
	 */
	@Query(
			value =
					"""
					select c.id
					from Course c
					where c.deletedAt is null
					  and (:semesterId is null or c.semester.id = :semesterId)
					  and (:academicClassId is null or c.academicClass.id = :academicClassId)
					  and (:subjectId is null or c.subject.id = :subjectId)
					  and (:lecturerId is null or c.instructor.id = :lecturerId)
					order by c.name asc, c.id asc
					""",
			countQuery =
					"""
					select count(c.id)
					from Course c
					where c.deletedAt is null
					  and (:semesterId is null or c.semester.id = :semesterId)
					  and (:academicClassId is null or c.academicClass.id = :academicClassId)
					  and (:subjectId is null or c.subject.id = :subjectId)
					  and (:lecturerId is null or c.instructor.id = :lecturerId)
					""")
	Page<UUID> findPageIds(
			@Param("semesterId") UUID semesterId,
			@Param("academicClassId") UUID academicClassId,
			@Param("subjectId") UUID subjectId,
			@Param("lecturerId") UUID lecturerId,
			Pageable pageable);

	@Query(
			"""
			SELECT c FROM Course c
			LEFT JOIN FETCH c.academicClass
			LEFT JOIN FETCH c.semester
			LEFT JOIN FETCH c.subject
			LEFT JOIN FETCH c.syllabusVersion
			LEFT JOIN FETCH c.instructor i
			LEFT JOIN FETCH i.userAccount
			WHERE c.id in :ids
			  AND c.deletedAt IS NULL
			""")
	List<Course> findFetchedByIdIn(@Param("ids") Collection<UUID> ids);

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

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

	/**
	 * Lecturer course picker IDs (lecturerId null = every course, for ADMIN). {@code q} is an
	 * already LIKE-escaped term matched case-insensitively against course name/code, class code and
	 * subject code/name. Same order as {@link #search}. Pass an unsorted {@link Pageable}.
	 */
	@Query(
			value =
					"""
					select c.id
					from Course c
					left join c.academicClass ac
					left join c.subject s
					where c.deletedAt is null
					  and (:lecturerId is null or c.instructor.id = :lecturerId)
					  and (:semesterId is null or c.semester.id = :semesterId)
					  and (:q is null
					    or lower(coalesce(c.name, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(c.courseCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(ac.classCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.subjectCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.name, '')) like lower(concat('%', :q, '%')) escape '\\')
					order by c.name asc, c.id asc
					""",
			countQuery =
					"""
					select count(c.id)
					from Course c
					left join c.academicClass ac
					left join c.subject s
					where c.deletedAt is null
					  and (:lecturerId is null or c.instructor.id = :lecturerId)
					  and (:semesterId is null or c.semester.id = :semesterId)
					  and (:q is null
					    or lower(coalesce(c.name, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(c.courseCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(ac.classCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.subjectCode, '')) like lower(concat('%', :q, '%')) escape '\\'
					    or lower(coalesce(s.name, '')) like lower(concat('%', :q, '%')) escape '\\')
					""")
	Page<UUID> findLecturerPickerPageIds(
			@Param("lecturerId") UUID lecturerId,
			@Param("semesterId") UUID semesterId,
			@Param("q") String q,
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
			WHERE c.id = :courseId AND c.deletedAt IS NULL
			""")
	Optional<Course> findActiveFetchedById(@Param("courseId") UUID courseId);

	long countBySemester_IdAndDeletedAtIsNull(UUID semesterId);

	/** Course header for reports, without lazy loading: {@code Object[]{String courseCode, String name,
	 * String subjectCode, String subjectName, String semesterName, String lecturerName}}. */
	@org.springframework.data.jpa.repository.Query(
			"""
			select c.courseCode, c.name, s.subjectCode, s.name, sem.name, u.fullName
			from Course c
			left join c.subject s
			left join c.semester sem
			left join c.instructor i
			left join i.userAccount u
			where c.id = :id
			""")
	List<Object[]> findReportHeader(@org.springframework.data.repository.query.Param("id") UUID id);
}

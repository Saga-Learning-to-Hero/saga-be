package com.saga.be.repository;

import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseEnrollmentRepository extends JpaRepository<CourseEnrollment, UUID> {

	boolean existsByCourse_Id(UUID courseId);

	Optional<CourseEnrollment> findByStudentProfile_IdAndCourse_Id(UUID studentProfileId, UUID courseId);

	List<CourseEnrollment> findByCourse_Id(UUID courseId);

	@Query(
			"""
			SELECT e FROM CourseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE e.course.id = :courseId
			""")
	List<CourseEnrollment> findFetchedByCourse_Id(@Param("courseId") UUID courseId);

	List<CourseEnrollment> findByCourse_IdAndEnrollmentStatus(UUID courseId, EnrollmentStatus enrollmentStatus);

	@Query(
			"""
			SELECT e FROM CourseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE e.course.id = :courseId AND e.enrollmentStatus = :status
			ORDER BY p.studentCode ASC
			""")
	List<CourseEnrollment> findFetchedByCourse_IdAndEnrollmentStatus(
			@Param("courseId") UUID courseId, @Param("status") EnrollmentStatus status);

	@Query(
			"""
			SELECT e FROM CourseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			WHERE e.course.id IN :courseIds AND e.enrollmentStatus = :status
			""")
	List<CourseEnrollment> findFetchedByCourse_IdInAndEnrollmentStatus(
			@Param("courseIds") Collection<UUID> courseIds, @Param("status") EnrollmentStatus status);

	@Query(
			"""
			SELECT e FROM CourseEnrollment e
			JOIN FETCH e.course c
			JOIN FETCH c.subject
			JOIN FETCH c.academicClass
			JOIN FETCH c.semester
			WHERE e.studentProfile.id = :studentProfileId
				AND e.enrollmentStatus = :status
				AND c.deletedAt IS NULL
			""")
	List<CourseEnrollment> findFetchedByStudentProfile_IdAndEnrollmentStatus(
			@Param("studentProfileId") UUID studentProfileId, @Param("status") EnrollmentStatus status);

	/**
	 * Student dashboard gate: ACTIVE enrollment on a non-deleted course, with Course/Subject/
	 * Semester and StudentProfile/UserAccount already fetched. Missing profile, missing
	 * enrollment, WITHDRAWN/COMPLETED, deleted course, and nonexistent course all yield empty.
	 */
	@Query(
			"""
			SELECT e FROM CourseEnrollment e
			JOIN FETCH e.studentProfile p
			JOIN FETCH p.userAccount
			JOIN FETCH e.course c
			JOIN FETCH c.subject
			JOIN FETCH c.semester
			WHERE p.userAccount.id = :userId
			  AND c.id = :courseId
			  AND e.enrollmentStatus = com.saga.be.entity.enums.EnrollmentStatus.ACTIVE
			  AND c.deletedAt IS NULL
			""")
	Optional<CourseEnrollment> findFetchedActiveByUserAndCourse(
			@Param("userId") UUID userId, @Param("courseId") UUID courseId);

	/**
	 * Distinct ACTIVE enrollments on non-deleted courses of {@code semesterId}. Does not consult
	 * {@code user_account.account_status}.
	 */
	@Query(
			"""
			select count(distinct e.studentProfile.id)
			from CourseEnrollment e
			join e.course c
			where c.semester.id = :semesterId
			  and c.deletedAt is null
			  and e.enrollmentStatus = :status
			""")
	long countDistinctStudentsBySemesterAndStatus(
			@Param("semesterId") UUID semesterId, @Param("status") EnrollmentStatus status);
}

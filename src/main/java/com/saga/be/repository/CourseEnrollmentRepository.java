package com.saga.be.repository;

import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
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
}

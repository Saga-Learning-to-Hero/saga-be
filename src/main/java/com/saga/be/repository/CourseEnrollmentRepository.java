package com.saga.be.repository;

import com.saga.be.entity.academic.CourseEnrollment;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseEnrollmentRepository extends JpaRepository<CourseEnrollment, UUID> {

	boolean existsByCourse_Id(UUID courseId);

	Optional<CourseEnrollment> findByStudentProfile_IdAndCourse_Id(UUID studentProfileId, UUID courseId);

	List<CourseEnrollment> findByCourse_Id(UUID courseId);
}

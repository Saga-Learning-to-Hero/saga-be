package com.saga.be.repository;

import com.saga.be.entity.academic.CourseEnrollment;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseEnrollmentRepository extends JpaRepository<CourseEnrollment, UUID> {

	Optional<CourseEnrollment> findByStudentProfile_IdAndCourse_Id(UUID studentProfileId, UUID courseId);
}

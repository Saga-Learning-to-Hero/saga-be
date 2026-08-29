package com.saga.be.repository;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.enums.StudentInvitationStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StudentCourseInvitationRepository extends JpaRepository<StudentCourseInvitation, UUID> {

	Optional<StudentCourseInvitation> findByCourse_IdAndEmailIgnoreCase(UUID courseId, String email);

	Optional<StudentCourseInvitation> findByCourse_IdAndStudentCodeIgnoreCase(UUID courseId, String studentCode);

	List<StudentCourseInvitation> findByCourse_IdAndInvitationStatus(UUID courseId, StudentInvitationStatus status);

	List<StudentCourseInvitation> findByEmailIgnoreCaseAndInvitationStatus(String email, StudentInvitationStatus status);

	List<StudentCourseInvitation> findByEmailIgnoreCaseAndInvitationStatusIn(
			String email, Collection<StudentInvitationStatus> statuses);

	List<StudentCourseInvitation> findByCourse_Id(UUID courseId);
}

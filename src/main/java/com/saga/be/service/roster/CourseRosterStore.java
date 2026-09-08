package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.StudentInvitationStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public interface CourseRosterStore {

	Optional<Course> findCourse(UUID courseId);

	Optional<UserAccount> findUserByEmail(String email);

	Optional<StudentProfile> findStudentByUserId(UUID userId);

	Optional<StudentProfile> findStudentByCode(String studentCode);

	Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId);

	List<CourseEnrollment> listEnrollments(UUID courseId);

	CourseEnrollment saveEnrollment(CourseEnrollment enrollment);

	Optional<StudentCourseInvitation> findInvitationByCourseAndEmail(UUID courseId, String email);

	Optional<StudentCourseInvitation> findInvitationByCourseAndStudentCode(UUID courseId, String studentCode);

	List<StudentCourseInvitation> listInvitations(UUID courseId);

	List<StudentCourseInvitation> listPendingByEmail(String email);

	StudentCourseInvitation saveInvitation(StudentCourseInvitation invitation);

	default <T> T inTransaction(Supplier<T> action) {
		return action.get();
	}
}

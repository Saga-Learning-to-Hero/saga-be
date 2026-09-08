package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.StudentCourseInvitationRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class JpaCourseRosterStore implements CourseRosterStore {

	private final CourseRepository courses;
	private final UserAccountRepository users;
	private final StudentProfileRepository students;
	private final CourseEnrollmentRepository enrollments;
	private final StudentCourseInvitationRepository invitations;

	public JpaCourseRosterStore(
			CourseRepository courses,
			UserAccountRepository users,
			StudentProfileRepository students,
			CourseEnrollmentRepository enrollments,
			StudentCourseInvitationRepository invitations) {
		this.courses = courses;
		this.users = users;
		this.students = students;
		this.enrollments = enrollments;
		this.invitations = invitations;
	}

	@Override
	public Optional<Course> findCourse(UUID courseId) {
		return courses.findActiveFetchedById(courseId)
				.or(() -> courses.findById(courseId).filter(course -> course.getDeletedAt() == null));
	}

	@Override
	public Optional<UserAccount> findUserByEmail(String email) {
		return users.findByEmail(email);
	}

	@Override
	public List<UserAccount> findUsersByEmails(Collection<String> emails) {
		if (emails == null || emails.isEmpty()) {
			return List.of();
		}
		return users.findByEmailIn(emails);
	}

	@Override
	public Optional<StudentProfile> findStudentByUserId(UUID userId) {
		return students.findByUserAccount_Id(userId);
	}

	@Override
	public List<StudentProfile> findStudentsByUserIds(Collection<UUID> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return students.findFetchedByUserAccount_IdIn(userIds);
	}

	@Override
	public Optional<StudentProfile> findStudentByCode(String studentCode) {
		return students.findByStudentCodeIgnoreCase(studentCode);
	}

	@Override
	public List<StudentProfile> findStudentsByCodes(Collection<String> studentCodes) {
		if (studentCodes == null || studentCodes.isEmpty()) {
			return List.of();
		}
		return students.findFetchedByStudentCodeUpperIn(studentCodes);
	}

	@Override
	public Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId) {
		return enrollments.findByStudentProfile_IdAndCourse_Id(studentProfileId, courseId);
	}

	@Override
	public List<CourseEnrollment> listEnrollments(UUID courseId) {
		List<CourseEnrollment> fetched = enrollments.findFetchedByCourse_Id(courseId);
		if (!fetched.isEmpty()) {
			return fetched;
		}
		return enrollments.findByCourse_Id(courseId);
	}

	@Override
	public CourseEnrollment saveEnrollment(CourseEnrollment enrollment) {
		return enrollments.save(enrollment);
	}

	@Override
	public Optional<StudentCourseInvitation> findInvitationByCourseAndEmail(UUID courseId, String email) {
		return invitations.findByCourse_IdAndEmailIgnoreCase(courseId, email);
	}

	@Override
	public Optional<StudentCourseInvitation> findInvitationByCourseAndStudentCode(UUID courseId, String studentCode) {
		return invitations.findByCourse_IdAndStudentCodeIgnoreCase(courseId, studentCode);
	}

	@Override
	public List<StudentCourseInvitation> listInvitations(UUID courseId) {
		return invitations.findByCourse_Id(courseId);
	}

	@Override
	public List<StudentCourseInvitation> listPendingByEmail(String email) {
		return invitations.findByEmailIgnoreCaseAndInvitationStatusIn(
				email, List.of(StudentInvitationStatus.PENDING, StudentInvitationStatus.SENT));
	}

	@Override
	public StudentCourseInvitation saveInvitation(StudentCourseInvitation invitation) {
		return invitations.save(invitation);
	}

	@Override
	public StudentProfile saveStudent(StudentProfile profile) {
		return students.save(profile);
	}
}

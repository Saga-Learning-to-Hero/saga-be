package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.StudentCourseInvitationRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
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
	private final TeamMemberRepository teamMembers;

	public JpaCourseRosterStore(
			CourseRepository courses,
			UserAccountRepository users,
			StudentProfileRepository students,
			CourseEnrollmentRepository enrollments,
			StudentCourseInvitationRepository invitations,
			TeamMemberRepository teamMembers) {
		this.courses = courses;
		this.users = users;
		this.students = students;
		this.enrollments = enrollments;
		this.invitations = invitations;
		this.teamMembers = teamMembers;
	}

	@Override
	public Optional<Course> findCourse(UUID courseId) {
		return courses.findById(courseId).filter(course -> course.getDeletedAt() == null);
	}

	@Override
	public Optional<UserAccount> findUserByEmail(String email) {
		return users.findByEmail(email);
	}

	@Override
	public Optional<StudentProfile> findStudentByUserId(UUID userId) {
		return students.findByUserAccount_Id(userId);
	}

	@Override
	public Optional<StudentProfile> findStudentByCode(String studentCode) {
		return students.findByStudentCodeIgnoreCase(studentCode);
	}

	@Override
	public Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId) {
		return enrollments.findByStudentProfile_IdAndCourse_Id(studentProfileId, courseId);
	}

	@Override
	public Optional<CourseEnrollment> findEnrollmentById(UUID enrollmentId, UUID courseId) {
		return enrollments.findById(enrollmentId)
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()));
	}

	@Override
	public List<CourseEnrollment> listEnrollments(UUID courseId) {
		return enrollments.findByCourse_Id(courseId);
	}

	@Override
	public CourseEnrollment saveEnrollment(CourseEnrollment enrollment) {
		return enrollments.save(enrollment);
	}

	@Override
	public boolean deleteTeamMemberByEnrollmentId(UUID enrollmentId) {
		Optional<TeamMember> member = teamMembers.findByCourseEnrollment_Id(enrollmentId);
		if (member.isEmpty()) {
			return false;
		}
		teamMembers.delete(member.get());
		return true;
	}

	@Override
	public Optional<StudentCourseInvitation> findInvitationById(UUID invitationId, UUID courseId) {
		return invitations.findById(invitationId)
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()));
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
}

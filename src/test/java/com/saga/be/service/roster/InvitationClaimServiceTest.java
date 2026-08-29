package com.saga.be.service.roster;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.StudentInvitationStatus;
import com.saga.be.entity.enums.StudentInvitationType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InvitationClaimServiceTest {

	private InMemoryCourseRosterStore store;
	private InvitationClaimService claims;

	@BeforeEach
	void setUp() {
		store = new InMemoryCourseRosterStore();
		claims = new InvitationClaimService(store);
	}

	@Test
	void matchingStudentClaimsPendingInvitationAndActivatesEnrollment() {
		Course course = course("SE1705");
		StudentCourseInvitation invitation = pending(course, "student@gmail.com", "SE123456", "Example");
		UserAccount account = student("student@gmail.com", "SE123456");

		List<StudentCourseInvitation> claimed = claims.claimPendingInvitations(account);

		assertEquals(1, claimed.size());
		assertEquals(StudentInvitationStatus.CLAIMED, invitation.getInvitationStatus());
		assertEquals(account.getId(), invitation.getStudentProfile().getUserAccount().getId());
		CourseEnrollment enrollment = store.findEnrollment(
						store.findStudentByUserId(account.getId()).orElseThrow().getId(), course.getId())
				.orElseThrow();
		assertEquals(EnrollmentStatus.ACTIVE, enrollment.getEnrollmentStatus());
	}

	@Test
	void mismatchedStudentCodeDoesNotClaim() {
		Course course = course("SE1705");
		pending(course, "student@gmail.com", "SE999999", "Example");
		UserAccount account = student("student@gmail.com", "SE123456");

		assertTrue(claims.claimPendingInvitations(account).isEmpty());
		assertEquals(StudentInvitationStatus.PENDING, store.invitations.values().iterator().next().getInvitationStatus());
		assertTrue(store.enrollments.isEmpty());
	}

	@Test
	void sentInvitationIsClaimable() {
		Course course = course("SE1705");
		StudentCourseInvitation invitation = pending(course, "student@gmail.com", "SE123456", "Example");
		invitation.setInvitationStatus(StudentInvitationStatus.SENT);
		UserAccount account = student("student@gmail.com", "SE123456");

		assertEquals(1, claims.claimPendingInvitations(account).size());
		assertEquals(StudentInvitationStatus.CLAIMED, invitation.getInvitationStatus());
		assertEquals(1, store.enrollments.size());
		assertEquals(0, claims.claimPendingInvitations(account).size());
	}

	@Test
	void failedInvitationIsNotClaimableUntilReactivated() {
		Course course = course("SE1705");
		StudentCourseInvitation invitation = pending(course, "student@gmail.com", "SE123456", "Example");
		invitation.setInvitationStatus(StudentInvitationStatus.FAILED);
		UserAccount account = student("student@gmail.com", "SE123456");

		assertTrue(claims.claimPendingInvitations(account).isEmpty());
		assertEquals(StudentInvitationStatus.FAILED, invitation.getInvitationStatus());
		assertTrue(store.enrollments.isEmpty());
	}

	@Test
	void claimedInvitationIsIdempotentNoOp() {
		Course course = course("SE1705");
		StudentCourseInvitation invitation = pending(course, "student@gmail.com", "SE123456", "Example");
		UserAccount account = student("student@gmail.com", "SE123456");
		assertEquals(1, claims.claimPendingInvitations(account).size());
		assertEquals(StudentInvitationStatus.CLAIMED, invitation.getInvitationStatus());
		assertEquals(0, claims.claimPendingInvitations(account).size());
		assertEquals(1, store.enrollments.size());
	}

	@Test
	void cancelledInvitationIsNotClaimed() {
		Course course = course("SE1705");
		StudentCourseInvitation invitation = pending(course, "student@gmail.com", "SE123456", "Example");
		invitation.setInvitationStatus(StudentInvitationStatus.CANCELLED);
		UserAccount account = student("student@gmail.com", "SE123456");

		assertTrue(claims.claimPendingInvitations(account).isEmpty());
		assertTrue(store.enrollments.isEmpty());
	}

	@Test
	void multipleValidInvitationsAreClaimed() {
		Course first = course("SE1705");
		Course second = course("SE1706");
		pending(first, "student@gmail.com", "SE123456", "Example");
		pending(second, "student@gmail.com", "SE123456", "Example");
		UserAccount account = student("student@gmail.com", "SE123456");

		assertEquals(2, claims.claimPendingInvitations(account).size());
		assertEquals(2, store.enrollments.size());
		assertTrue(store.invitations.values().stream().allMatch(row -> row.getInvitationStatus() == StudentInvitationStatus.CLAIMED));
	}

	@Test
	void repeatedClaimIsIdempotent() {
		Course course = course("SE1705");
		pending(course, "student@gmail.com", "SE123456", "Example");
		UserAccount account = student("student@gmail.com", "SE123456");

		assertEquals(1, claims.claimPendingInvitations(account).size());
		assertEquals(0, claims.claimPendingInvitations(account).size());
		assertEquals(1, store.enrollments.size());
		assertEquals(EnrollmentStatus.ACTIVE, store.enrollments.values().iterator().next().getEnrollmentStatus());
	}

	@Test
	void claimQuietlyDoesNotThrowWhenStoreIsEmpty() {
		UserAccount account = student("missing@gmail.com", "SE000000");
		claims.claimQuietly(account);
	}

	private Course course(String classCode) {
		Semester semester = new Semester();
		semester.setId(UUID.randomUUID());
		semester.setCode("FA26");
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode(classCode);
		academicClass.setSemester(semester);
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode("SWP391");
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setName("SWP391 · " + classCode);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSubject(subject);
		store.putCourse(course);
		return course;
	}

	private StudentCourseInvitation pending(Course course, String email, String studentCode, String fullName) {
		StudentCourseInvitation invitation = new StudentCourseInvitation();
		invitation.setCourse(course);
		invitation.setEmail(email);
		invitation.setStudentCode(studentCode);
		invitation.setFullName(fullName);
		invitation.setInvitationType(StudentInvitationType.COURSE_JOIN);
		invitation.setInvitationStatus(StudentInvitationStatus.PENDING);
		invitation.setAttemptCount(0);
		invitation.setVersion(0L);
		store.saveInvitation(invitation);
		return invitation;
	}

	private UserAccount student(String email, String studentCode) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setFullName("Example Student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		profile.setStudentCode(studentCode);
		profile.setVersion(0L);
		store.putUser(account);
		store.putStudent(profile);
		return account;
	}
}

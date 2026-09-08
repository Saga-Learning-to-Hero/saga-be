package com.saga.be.service.roster;

import com.saga.be.entity.account.StudentCourseInvitation;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.StudentInvitationStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

final class InMemoryCourseRosterStore implements CourseRosterStore {

	final Map<UUID, Course> courses = new LinkedHashMap<>();
	final Map<UUID, UserAccount> users = new LinkedHashMap<>();
	final Map<UUID, StudentProfile> students = new LinkedHashMap<>();
	final Map<UUID, CourseEnrollment> enrollments = new LinkedHashMap<>();
	final Map<UUID, StudentCourseInvitation> invitations = new LinkedHashMap<>();

	@Override
	public Optional<Course> findCourse(UUID courseId) {
		return Optional.ofNullable(courses.get(courseId)).filter(course -> course.getDeletedAt() == null);
	}

	@Override
	public Optional<UserAccount> findUserByEmail(String email) {
		if (email == null) {
			return Optional.empty();
		}
		return users.values().stream().filter(user -> email.equalsIgnoreCase(user.getEmail())).findFirst();
	}

	@Override
	public Optional<StudentProfile> findStudentByUserId(UUID userId) {
		return students.values().stream()
				.filter(profile -> profile.getUserAccount() != null && userId.equals(profile.getUserAccount().getId()))
				.findFirst();
	}

	@Override
	public Optional<StudentProfile> findStudentByCode(String studentCode) {
		if (studentCode == null) {
			return Optional.empty();
		}
		return students.values().stream()
				.filter(profile -> profile.getStudentCode() != null
						&& studentCode.equalsIgnoreCase(profile.getStudentCode()))
				.findFirst();
	}

	@Override
	public Optional<CourseEnrollment> findEnrollment(UUID studentProfileId, UUID courseId) {
		return enrollments.values().stream()
				.filter(row -> row.getStudentProfile() != null
						&& studentProfileId.equals(row.getStudentProfile().getId())
						&& row.getCourse() != null
						&& courseId.equals(row.getCourse().getId()))
				.findFirst();
	}

	@Override
	public List<CourseEnrollment> listEnrollments(UUID courseId) {
		return enrollments.values().stream()
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()))
				.toList();
	}

	@Override
	public CourseEnrollment saveEnrollment(CourseEnrollment enrollment) {
		if (enrollment.getId() == null) {
			enrollment.setId(UUID.randomUUID());
		}
		enrollments.put(enrollment.getId(), enrollment);
		return enrollment;
	}

	@Override
	public Optional<StudentCourseInvitation> findInvitationByCourseAndEmail(UUID courseId, String email) {
		if (email == null) {
			return Optional.empty();
		}
		return invitations.values().stream()
				.filter(row -> row.getCourse() != null
						&& courseId.equals(row.getCourse().getId())
						&& email.equalsIgnoreCase(row.getEmail()))
				.findFirst();
	}

	@Override
	public Optional<StudentCourseInvitation> findInvitationByCourseAndStudentCode(UUID courseId, String studentCode) {
		if (studentCode == null) {
			return Optional.empty();
		}
		return invitations.values().stream()
				.filter(row -> row.getCourse() != null
						&& courseId.equals(row.getCourse().getId())
						&& studentCode.equalsIgnoreCase(row.getStudentCode()))
				.findFirst();
	}

	@Override
	public List<StudentCourseInvitation> listInvitations(UUID courseId) {
		return invitations.values().stream()
				.filter(row -> row.getCourse() != null && courseId.equals(row.getCourse().getId()))
				.toList();
	}

	@Override
	public List<StudentCourseInvitation> listPendingByEmail(String email) {
		if (email == null) {
			return List.of();
		}
		return invitations.values().stream()
				.filter(row -> email.equalsIgnoreCase(row.getEmail())
						&& row.getInvitationStatus() != null
						&& row.getInvitationStatus().isOutstanding())
				.toList();
	}

	@Override
	public StudentCourseInvitation saveInvitation(StudentCourseInvitation invitation) {
		if (invitation.getId() == null) {
			invitation.setId(UUID.randomUUID());
		}
		invitations.put(invitation.getId(), invitation);
		return invitation;
	}

	@Override
	public <T> T inTransaction(Supplier<T> action) {
		Snapshot snapshot = snapshot();
		try {
			return action.get();
		} catch (RuntimeException | Error ex) {
			restore(snapshot);
			throw ex;
		}
	}

	void putCourse(Course course) {
		courses.put(course.getId(), course);
	}

	void putUser(UserAccount user) {
		users.put(user.getId(), user);
	}

	void putStudent(StudentProfile profile) {
		students.put(profile.getId(), profile);
	}

	private Snapshot snapshot() {
		return new Snapshot(copyEnrollments(), copyInvitations());
	}

	private void restore(Snapshot snapshot) {
		enrollments.clear();
		enrollments.putAll(snapshot.enrollments);
		invitations.clear();
		invitations.putAll(snapshot.invitations);
	}

	private Map<UUID, CourseEnrollment> copyEnrollments() {
		Map<UUID, CourseEnrollment> copy = new LinkedHashMap<>();
		for (CourseEnrollment row : enrollments.values()) {
			CourseEnrollment clone = new CourseEnrollment();
			clone.setId(row.getId());
			clone.setStudentProfile(row.getStudentProfile());
			clone.setCourse(row.getCourse());
			clone.setEnrollmentStatus(row.getEnrollmentStatus());
			clone.setEnrolledAt(row.getEnrolledAt());
			copy.put(clone.getId(), clone);
		}
		return copy;
	}

	private Map<UUID, StudentCourseInvitation> copyInvitations() {
		Map<UUID, StudentCourseInvitation> copy = new LinkedHashMap<>();
		for (StudentCourseInvitation row : invitations.values()) {
			StudentCourseInvitation clone = new StudentCourseInvitation();
			clone.setId(row.getId());
			clone.setStudentProfile(row.getStudentProfile());
			clone.setEmail(row.getEmail());
			clone.setStudentCode(row.getStudentCode());
			clone.setFullName(row.getFullName());
			clone.setCourse(row.getCourse());
			clone.setInvitationType(row.getInvitationType());
			clone.setInvitationStatus(row.getInvitationStatus());
			clone.setAttemptCount(row.getAttemptCount());
			clone.setLastAttemptAt(row.getLastAttemptAt());
			clone.setProcessingStartedAt(row.getProcessingStartedAt());
			clone.setSentAt(row.getSentAt());
			clone.setFailureCode(row.getFailureCode());
			clone.setVersion(row.getVersion());
			copy.put(clone.getId(), clone);
		}
		return copy;
	}

	private record Snapshot(
			Map<UUID, CourseEnrollment> enrollments, Map<UUID, StudentCourseInvitation> invitations) {}
}

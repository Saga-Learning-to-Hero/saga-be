package com.saga.be.service.lecturer;

import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@Profile("!test")
public class LecturerCourseAuthorization {

	private final CourseRepository courses;
	private final LecturerProfileRepository lecturers;

	public LecturerCourseAuthorization(CourseRepository courses, LecturerProfileRepository lecturers) {
		this.courses = courses;
		this.lecturers = lecturers;
	}

	/** Strictly the assigned lecturer -- unlike {@link #requireCourse}, ADMIN is NOT granted
	 * access. Used for course AI credential management, which is deliberately not a generic
	 * admin capability (section XI): an admin who is not the assigned lecturer must never be able
	 * to configure, read the metadata of, or revoke another lecturer's course AI credentials. */
	public Course requireAssignedLecturerStrict(UserAccount actor, UUID courseId) {
		return requireAssignedLecturerStrict(
				actor, courseId, "Only the assigned lecturer can manage this course's AI credentials.");
	}

	/** Same strict policy (ADMIN is NOT granted) with a caller-specific forbidden message. */
	public Course requireAssignedLecturerStrict(UserAccount actor, UUID courseId, String forbiddenMessage) {
		Course course = courses.findActiveFetchedById(courseId)
				.or(() -> courses.findById(courseId).filter(row -> row.getDeletedAt() == null))
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.COURSE_NOT_FOUND, HttpStatus.NOT_FOUND, "Course was not found."));
		LecturerProfile profile = actor == null
				? null
				: lecturers.findByUserAccount_Id(actor.getId()).orElse(null);
		if (profile == null
				|| course.getInstructor() == null
				|| course.getInstructor().getUserAccount() == null
				|| !actor.getId().equals(course.getInstructor().getUserAccount().getId())
				|| !profile.getId().equals(course.getInstructor().getId())) {
			throw new AcademicException(
					AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					forbiddenMessage);
		}
		return course;
	}

	public Course requireCourse(UserAccount actor, UUID courseId) {
		Course course = courses.findActiveFetchedById(courseId)
				.or(() -> courses.findById(courseId).filter(row -> row.getDeletedAt() == null))
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.COURSE_NOT_FOUND, HttpStatus.NOT_FOUND, "Course was not found."));
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			return course;
		}
		LecturerProfile profile = actor == null
				? null
				: lecturers.findByUserAccount_Id(actor.getId()).orElse(null);
		if (profile == null
				|| course.getInstructor() == null
				|| course.getInstructor().getUserAccount() == null
				|| !actor.getId().equals(course.getInstructor().getUserAccount().getId())
				|| !profile.getId().equals(course.getInstructor().getId())) {
			throw new AcademicException(
					AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Lecturer is not assigned to this course.");
		}
		return course;
	}
}

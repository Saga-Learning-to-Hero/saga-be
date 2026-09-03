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

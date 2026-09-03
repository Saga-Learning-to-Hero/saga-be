package com.saga.be.service.lecturer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class LecturerCourseAuthorizationTest {

	@Mock
	private CourseRepository courses;
	@Mock
	private LecturerProfileRepository lecturers;

	private LecturerCourseAuthorization authorization;
	private Course course;
	private UserAccount lecturerUser;
	private LecturerProfile lecturerProfile;

	@BeforeEach
	void setUp() {
		authorization = new LecturerCourseAuthorization(courses, lecturers);
		lecturerUser = user(AccountRole.LECTURER);
		lecturerProfile = new LecturerProfile();
		lecturerProfile.setId(UUID.randomUUID());
		lecturerProfile.setUserAccount(lecturerUser);
		course = new Course();
		course.setId(UUID.randomUUID());
		course.setInstructor(lecturerProfile);
		lenient().when(courses.findActiveFetchedById(course.getId())).thenReturn(Optional.of(course));
	}

	@Test
	void assignedLecturerIsAllowed() {
		when(lecturers.findByUserAccount_Id(lecturerUser.getId())).thenReturn(Optional.of(lecturerProfile));
		assertEquals(course.getId(), authorization.requireCourse(lecturerUser, course.getId()).getId());
	}

	@Test
	void otherLecturerIsForbidden() {
		UserAccount other = user(AccountRole.LECTURER);
		LecturerProfile otherProfile = new LecturerProfile();
		otherProfile.setId(UUID.randomUUID());
		otherProfile.setUserAccount(other);
		when(lecturers.findByUserAccount_Id(other.getId())).thenReturn(Optional.of(otherProfile));
		AcademicException ex =
				assertThrows(AcademicException.class, () -> authorization.requireCourse(other, course.getId()));
		assertEquals(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
	}

	@Test
	void unassignedCourseIsForbiddenForLecturer() {
		course.setInstructor(null);
		when(lecturers.findByUserAccount_Id(lecturerUser.getId())).thenReturn(Optional.of(lecturerProfile));
		AcademicException ex =
				assertThrows(AcademicException.class, () -> authorization.requireCourse(lecturerUser, course.getId()));
		assertEquals(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, ex.getCode());
	}

	@Test
	void adminBypassesWithoutLecturerProfile() {
		UserAccount admin = user(AccountRole.ADMIN);
		assertEquals(course.getId(), authorization.requireCourse(admin, course.getId()).getId());
	}

	@Test
	void missingCourseIsNotFound() {
		UUID missing = UUID.randomUUID();
		when(courses.findActiveFetchedById(missing)).thenReturn(Optional.empty());
		when(courses.findById(missing)).thenReturn(Optional.empty());
		AcademicException ex =
				assertThrows(AcademicException.class, () -> authorization.requireCourse(lecturerUser, missing));
		assertEquals(AcademicErrorCode.COURSE_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
	}

	private static UserAccount user(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(role.name().toLowerCase() + "@saga.local");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

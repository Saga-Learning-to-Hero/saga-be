package com.saga.be.service.lecturer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.saga.be.dto.team.LecturerActiveRosterResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LecturerCourseServiceTest {

	@Mock
	private LecturerCourseAuthorization authorization;
	@Mock
	private CourseRepository courses;
	@Mock
	private LecturerProfileRepository lecturers;
	@Mock
	private CourseEnrollmentRepository enrollments;

	private LecturerCourseService service;
	private UserAccount lecturerUser;
	private LecturerProfile lecturerProfile;
	private Course assigned;
	private Course other;

	@BeforeEach
	void setUp() {
		service = new LecturerCourseService(authorization, courses, lecturers, enrollments);
		lecturerUser = user(AccountRole.LECTURER, "lecturer@fe.edu.vn");
		lecturerProfile = new LecturerProfile();
		lecturerProfile.setId(UUID.randomUUID());
		lecturerProfile.setUserAccount(lecturerUser);
		assigned = course("Mine");
		assigned.setInstructor(lecturerProfile);
		other = course("Other");
	}

	@Test
	void lecturerListReturnsOnlyAssignedCourses() {
		when(lecturers.findByUserAccount_Id(lecturerUser.getId())).thenReturn(Optional.of(lecturerProfile));
		when(courses.search(null, null, null, lecturerProfile.getId())).thenReturn(List.of(assigned));
		assertEquals(1, service.listCourses(lecturerUser).size());
		assertEquals(assigned.getId(), service.listCourses(lecturerUser).getFirst().id());
	}

	@Test
	void adminListIsExplicitAllCourses() {
		UserAccount admin = user(AccountRole.ADMIN, "admin@saga.local");
		when(courses.search(null, null, null, null)).thenReturn(List.of(assigned, other));
		assertEquals(2, service.listCourses(admin).size());
	}

	@Test
	void activeRosterExcludesNonActiveAndInvitations() {
		when(authorization.requireCourse(lecturerUser, assigned.getId())).thenReturn(assigned);
		CourseEnrollment active = enrollment(assigned, EnrollmentStatus.ACTIVE, "SE111111", "A", "a@gmail.com");
		when(enrollments.findFetchedByCourse_IdAndEnrollmentStatus(assigned.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(active));
		LecturerActiveRosterResponse roster = service.getActiveRoster(lecturerUser, assigned.getId());
		assertEquals(1, roster.enrolledCount());
		assertEquals("SE111111", roster.entries().getFirst().studentCode());
		assertEquals("a@gmail.com", roster.entries().getFirst().email());
		assertTrue(roster.entries().stream().noneMatch(row -> "INVITATION".equals(String.valueOf(row))));
	}

	private static CourseEnrollment enrollment(
			Course course, EnrollmentStatus status, String code, String name, String email) {
		UserAccount user = user(AccountRole.STUDENT, email);
		user.setFullName(name);
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setStudentCode(code);
		profile.setUserAccount(user);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setCourse(course);
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(status);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollment;
	}

	private static Course course(String name) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode("SE1705");
		academicClass.setName("SE1705");
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setName(name);
		course.setAcademicClass(academicClass);
		return course;
	}

	private static UserAccount user(AccountRole role, String email) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

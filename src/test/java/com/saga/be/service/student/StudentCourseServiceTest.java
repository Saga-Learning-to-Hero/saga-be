package com.saga.be.service.student;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.student.StudentCourseResponse;
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
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StudentCourseServiceTest {

	@Mock
	private StudentProfileRepository students;
	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private TeamMemberRepository members;

	private StudentCourseService service;
	private UserAccount student;
	private StudentProfile profile;

	@BeforeEach
	void setUp() {
		service = new StudentCourseService(students, enrollments, members);
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("leader@gmail.com");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setStudentCode("SE111111");
		profile.setUserAccount(student);
	}

	@Test
	void studentSeesOwnActiveCourses() {
		CourseEnrollment mine = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(mine));
		when(members.findFetchedByCourseEnrollment_IdIn(List.of(mine.getId()))).thenReturn(List.of());
		List<StudentCourseResponse> rows = service.listMine(student.getId());
		assertEquals(1, rows.size());
		assertEquals(mine.getCourse().getId(), rows.getFirst().courseId());
		assertEquals("SWP391", rows.getFirst().subjectCode());
		assertEquals("Software Development Project", rows.getFirst().subjectName());
		assertEquals("SE18B01", rows.getFirst().classCode());
		assertEquals("FA26", rows.getFirst().semesterCode());
		assertEquals("Fall 2026", rows.getFirst().semesterName());
		assertEquals("ACTIVE", rows.getFirst().enrollmentStatus());
		verify(enrollments)
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(eq(profile.getId()), eq(EnrollmentStatus.ACTIVE));
	}

	@Test
	void otherStudentCoursesAreExcluded() {
		UUID otherUserId = UUID.randomUUID();
		StudentProfile other = new StudentProfile();
		other.setId(UUID.randomUUID());
		when(students.findByUserAccount_Id(otherUserId)).thenReturn(Optional.of(other));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(other.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of());
		assertTrue(service.listMine(otherUserId).isEmpty());
		verify(enrollments)
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(other.getId(), EnrollmentStatus.ACTIVE);
		verify(enrollments, never())
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(eq(profile.getId()), any());
	}

	@Test
	void withdrawnEnrollmentsAreExcluded() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of());
		assertTrue(service.listMine(student.getId()).isEmpty());
		verify(enrollments, never())
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.WITHDRAWN);
	}

	@Test
	void completedEnrollmentsAreExcluded() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of());
		assertTrue(service.listMine(student.getId()).isEmpty());
		verify(enrollments, never())
				.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.COMPLETED);
	}

	@Test
	void noActiveCoursesReturnsEmptyList() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of());
		List<StudentCourseResponse> rows = service.listMine(student.getId());
		assertTrue(rows.isEmpty());
		verify(members, never()).findFetchedByCourseEnrollment_IdIn(any());
	}

	@Test
	void missingStudentProfileIsForbidden() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(AcademicException.class, () -> service.listMine(student.getId()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
	}

	@Test
	void teamAndProjectAreNullWhenUnassigned() {
		CourseEnrollment mine = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(mine));
		when(members.findFetchedByCourseEnrollment_IdIn(List.of(mine.getId()))).thenReturn(List.of());
		StudentCourseResponse row = service.listMine(student.getId()).getFirst();
		assertEquals(null, row.teamId());
		assertEquals(null, row.teamNo());
		assertEquals(null, row.teamName());
		assertEquals(null, row.projectId());
	}

	@Test
	void teamPresentProjectNull() {
		CourseEnrollment mine = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(2);
		team.setName("SAGA Team");
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(mine);
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(mine));
		when(members.findFetchedByCourseEnrollment_IdIn(List.of(mine.getId()))).thenReturn(List.of(member));
		StudentCourseResponse row = service.listMine(student.getId()).getFirst();
		assertEquals(team.getId(), row.teamId());
		assertEquals(2, row.teamNo());
		assertEquals("SAGA Team", row.teamName());
		assertEquals(null, row.projectId());
	}

	@Test
	void teamAndProjectArePopulated() {
		CourseEnrollment mine = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		Project project = new Project();
		project.setId(UUID.randomUUID());
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(1);
		team.setName("Leaders");
		team.setProject(project);
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(mine);
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(mine));
		when(members.findFetchedByCourseEnrollment_IdIn(List.of(mine.getId()))).thenReturn(List.of(member));
		StudentCourseResponse row = service.listMine(student.getId()).getFirst();
		assertEquals(team.getId(), row.teamId());
		assertEquals(project.getId(), row.projectId());
	}

	@Test
	void recentSemesterComesFirst() {
		CourseEnrollment older = enrollment("SWD392", "SE17A01", "SP26", LocalDateTime.of(2026, 1, 1, 0, 0));
		CourseEnrollment newer = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(older, newer));
		when(members.findFetchedByCourseEnrollment_IdIn(any())).thenReturn(List.of());
		List<StudentCourseResponse> rows = service.listMine(student.getId());
		assertEquals("FA26", rows.get(0).semesterCode());
		assertEquals("SP26", rows.get(1).semesterCode());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
		verify(members).findFetchedByCourseEnrollment_IdIn(ids.capture());
		assertEquals(2, ids.getValue().size());
	}

	@Test
	void pagedKeepsTheUnpagedOrderAndLoadsTeamsOnlyForThePage() {
		CourseEnrollment spring = enrollment("SWD392", "SE17A01", "SP26", LocalDateTime.of(2026, 1, 1, 0, 0));
		CourseEnrollment fallA = enrollment("PRN231", "SE18A01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		CourseEnrollment fallB = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		stubActive(spring, fallB, fallA);
		when(members.findFetchedByCourseEnrollment_IdIn(any())).thenReturn(List.of());

		var first = service.listMinePaged(student.getId(), null, null, 0, 2);
		var second = service.listMinePaged(student.getId(), null, null, 1, 2);

		assertEquals(3, first.total());
		assertEquals(0, first.page());
		assertEquals(2, first.size());
		assertEquals(List.of("PRN231", "SWP391"), first.items().stream().map(StudentCourseResponse::subjectCode).toList());
		assertEquals(List.of("SWD392"), second.items().stream().map(StudentCourseResponse::subjectCode).toList());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.forClass(List.class);
		verify(members, org.mockito.Mockito.times(2)).findFetchedByCourseEnrollment_IdIn(ids.capture());
		assertEquals(List.of(fallA.getId(), fallB.getId()), ids.getAllValues().get(0));
		assertEquals(List.of(spring.getId()), ids.getAllValues().get(1));
	}

	@Test
	void pagedFiltersBySemesterAndCaseInsensitiveSearch() {
		CourseEnrollment spring = enrollment("SWD392", "SE17A01", "SP26", LocalDateTime.of(2026, 1, 1, 0, 0));
		CourseEnrollment fall = enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0));
		stubActive(spring, fall);
		when(members.findFetchedByCourseEnrollment_IdIn(any())).thenReturn(List.of());

		var bySemester = service.listMinePaged(student.getId(), spring.getCourse().getSemester().getId(), null, null, null);
		var byClass = service.listMinePaged(student.getId(), null, "  se18b ", null, null);
		var bySubjectName = service.listMinePaged(student.getId(), null, "development", null, null);
		var noMatch = service.listMinePaged(student.getId(), null, "PRJ301", null, null);

		assertEquals(List.of(spring.getCourse().getId()), bySemester.items().stream().map(StudentCourseResponse::courseId).toList());
		assertEquals(50, bySemester.size());
		assertEquals(List.of(fall.getCourse().getId()), byClass.items().stream().map(StudentCourseResponse::courseId).toList());
		assertEquals(2, bySubjectName.total());
		assertEquals(0, noMatch.total());
		assertTrue(noMatch.items().isEmpty());
	}

	@Test
	void pagedPastTheEndIsEmptyAndInvalidPagingIsRejected() {
		stubActive(enrollment("SWP391", "SE18B01", "FA26", LocalDateTime.of(2026, 9, 1, 0, 0)));
		org.mockito.Mockito.lenient().when(members.findFetchedByCourseEnrollment_IdIn(any())).thenReturn(List.of());

		var beyond = service.listMinePaged(student.getId(), null, null, 5, 10);
		assertTrue(beyond.items().isEmpty());
		assertEquals(1, beyond.total());
		verify(members, never()).findFetchedByCourseEnrollment_IdIn(any());

		assertEquals(
				AcademicErrorCode.REQUEST_INVALID,
				assertThrows(AcademicException.class, () -> service.listMinePaged(student.getId(), null, null, -1, 10)).getCode());
		assertEquals(
				AcademicErrorCode.REQUEST_INVALID,
				assertThrows(AcademicException.class, () -> service.listMinePaged(student.getId(), null, null, 0, 201)).getCode());
	}

	private void stubActive(CourseEnrollment... rows) {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findFetchedByStudentProfile_IdAndEnrollmentStatus(profile.getId(), EnrollmentStatus.ACTIVE))
				.thenReturn(List.of(rows));
	}

	private CourseEnrollment enrollment(String subjectCode, String classCode, String semesterCode, LocalDateTime start) {
		Semester semester = new Semester();
		semester.setId(UUID.randomUUID());
		semester.setCode(semesterCode);
		semester.setName(semesterCode.startsWith("FA") ? "Fall 2026" : "Spring 2026");
		semester.setStartDate(start);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode(classCode);
		academicClass.setName(classCode);
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode(subjectCode);
		subject.setName("Software Development Project");
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setCourseCode(subjectCode + "-" + classCode);
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		return enrollment;
	}
}

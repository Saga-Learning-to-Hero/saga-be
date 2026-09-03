package com.saga.be.service.student;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.saga.be.dto.team.StudentTeamResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StudentTeamServiceTest {

	@Mock
	private StudentProfileRepository students;
	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private TeamMemberRepository members;

	private StudentTeamService service;
	private UserAccount student;
	private StudentProfile profile;
	private Course course;
	private CourseEnrollment enrollment;

	@BeforeEach
	void setUp() {
		service = new StudentTeamService(students, enrollments, members);
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("student@gmail.com");
		student.setFullName("Alpha Student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setStudentCode("SE111111");
		profile.setUserAccount(student);
		course = new Course();
		course.setId(UUID.randomUUID());
		enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setCourse(course);
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
	}

	@Test
	void ownTeamOmitsMemberEmail() {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(1);
		team.setName("Alpha");
		TeamMember mine = member(team, enrollment, RoleInTeam.LEADER);
		when(members.findByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.of(mine));
		when(members.findByTeam_Id(team.getId())).thenReturn(List.of(mine));
		StudentTeamResponse response = service.myTeam(student.getId(), course.getId());
		assertEquals(team.getId(), response.teamId());
		assertEquals(1, response.teamNo());
		assertEquals("LEADER", response.myRole());
		assertEquals(null, response.projectId());
		assertEquals("SE111111", response.members().getFirst().studentCode());
		assertFalse(response.toString().contains("student@gmail.com") && response.members().toString().contains("email"));
		assertEquals("Alpha Student", response.members().getFirst().fullName());
	}

	@Test
	void inactiveEnrollmentIsForbidden() {
		enrollment.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.myTeam(student.getId(), course.getId()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
	}

	@Test
	void ownTeamIncludesProjectIdAfterLeaderCreatesProject() {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(1);
		team.setName("Alpha");
		Project project = new Project();
		project.setId(UUID.randomUUID());
		team.setProject(project);
		TeamMember mine = member(team, enrollment, RoleInTeam.LEADER);
		when(members.findByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.of(mine));
		when(members.findByTeam_Id(team.getId())).thenReturn(List.of(mine));
		StudentTeamResponse response = service.myTeam(student.getId(), course.getId());
		assertEquals(project.getId(), response.projectId());
	}

	@Test
	void missingTeamIsNotFound() {
		when(members.findByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.empty());
		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.myTeam(student.getId(), course.getId()));
		assertEquals(AcademicErrorCode.TEAM_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
	}

	private static TeamMember member(Team team, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}
}

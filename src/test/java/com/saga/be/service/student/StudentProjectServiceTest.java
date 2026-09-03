package com.saga.be.service.student;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.CreateStudentProjectRequest;
import com.saga.be.dto.project.ProjectTypeResponse;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.ProjectType;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.ProjectTypeRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import java.sql.SQLException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class StudentProjectServiceTest {

	@Mock
	private StudentProfileRepository students;
	@Mock
	private CourseEnrollmentRepository enrollments;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private ProjectTypeRepository projectTypes;
	@Mock
	private TeamRepository lockedTeams;
	@Mock
	private AuditService audit;

	private StudentProjectService service;
	private UserAccount student;
	private StudentProfile profile;
	private Course course;
	private CourseEnrollment enrollment;
	private Team team;
	private TeamMember leader;

	@BeforeEach
	void setUp() {
		StudentTeamService teamAccess = new StudentTeamService(students, enrollments, members);
		service = new StudentProjectService(teamAccess, projects, projectTypes, lockedTeams, audit);
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("leader@gmail.com");
		student.setFullName("Alpha Leader");
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
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		team.setTeamNo(1);
		team.setName("SAGA Team");
		leader = member(team, enrollment, RoleInTeam.LEADER);
	}

	@Test
	void catalogReturnsSeededPublicFieldsOnly() {
		ProjectType type = type("DESIGN_ARCHITECTURE", "Design / Architecture");
		type.setCriteriaConfig("{\"secret\":true}");
		when(projectTypes.findAllByOrderByCodeAsc()).thenReturn(List.of(type));
		List<ProjectTypeResponse> catalog = service.listTypes();
		assertEquals(1, catalog.size());
		assertEquals(type.getId(), catalog.getFirst().id());
		assertEquals("DESIGN_ARCHITECTURE", catalog.getFirst().code());
		assertEquals("Design / Architecture", catalog.getFirst().name());
		assertFalse(catalog.getFirst().toString().contains("criteria"));
		assertFalse(catalog.getFirst().toString().contains("secret"));
	}

	@Test
	void activeLeaderCreatesProjectAndAttachesTeam() {
		activeMembership();
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		when(projects.save(any(Project.class))).thenAnswer(this::persistProject);
		when(lockedTeams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
		StudentProjectResponse response = service.create(
				student.getId(),
				course.getId(),
				new CreateStudentProjectRequest("  SAGA Learning Platform  ", null, "  first cohort  "),
				auditReq());
		ArgumentCaptor<Project> saved = ArgumentCaptor.forClass(Project.class);
		verify(projects).save(saved.capture());
		Project project = saved.getValue();
		assertEquals(course.getId(), project.getCourse().getId());
		assertEquals(student.getId(), project.getCreatedBy().getId());
		assertEquals("SAGA Learning Platform", project.getName());
		assertEquals("first cohort", project.getDescription());
		assertNull(project.getProjectType());
		assertNull(project.getRepositoryUrl());
		assertEquals(project.getId(), team.getProject().getId());
		assertEquals(project.getId(), response.projectId());
		assertEquals(course.getId(), response.courseId());
		assertEquals(team.getId(), response.teamId());
		assertEquals(1, response.teamNo());
		assertEquals("SAGA Team", response.teamName());
		assertEquals(student.getId(), response.createdBy().userId());
		assertEquals("Alpha Leader", response.createdBy().fullName());
		assertNull(response.projectType());
		assertFalse(response.toString().toLowerCase().contains("token"));
		assertFalse(response.toString().toLowerCase().contains("secret"));
		verify(audit)
				.record(
						eq(student),
						eq(project),
						eq(team),
						eq(StudentProjectService.PROJECT_CREATED),
						eq("project"),
						eq(project.getId()),
						isNull(),
						any(),
						any(),
						eq(AuditSource.API),
						eq("req-1"),
						eq("127.0.0.1"),
						eq("test"));
	}

	@Test
	void validProjectTypeIsAttached() {
		activeMembership();
		ProjectType type = type("RESEARCH", "Research");
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		when(projectTypes.findById(type.getId())).thenReturn(Optional.of(type));
		when(projects.save(any(Project.class))).thenAnswer(this::persistProject);
		when(lockedTeams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
		StudentProjectResponse response = service.create(
				student.getId(),
				course.getId(),
				new CreateStudentProjectRequest("Research Lab", type.getId(), null),
				auditReq());
		assertEquals(type.getId(), response.projectType().id());
		assertEquals("RESEARCH", response.projectType().code());
		assertFalse(response.projectType().toString().contains("criteria"));
	}

	@Test
	void invalidProjectTypeIsRejected() {
		activeMembership();
		UUID missing = UUID.randomUUID();
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		when(projectTypes.findById(missing)).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", missing, null),
						auditReq()));
		assertEquals(AcademicErrorCode.PROJECT_TYPE_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
		verify(projects, never()).save(any());
	}

	@Test
	void memberCannotCreateProject() {
		leader.setRoleInTeam(RoleInTeam.MEMBER);
		activeMembership();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.NOT_TEAM_LEADER, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
		verify(projects, never()).save(any());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void studentWithoutTeamCannotCreate() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.TEAM_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
	}

	@Test
	void studentOutsideCourseIsForbidden() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.empty());
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
		assertEquals(HttpStatus.FORBIDDEN, ex.getStatus());
	}

	@Test
	void withdrawnLeaderCannotCreate() {
		enrollment.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
	}

	@Test
	void completedLeaderCannotCreate() {
		enrollment.setEnrollmentStatus(EnrollmentStatus.COMPLETED);
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
	}

	@Test
	void blankNameIsRejected() {
		activeMembership();
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("   ", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.PROJECT_NAME_INVALID, ex.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, ex.getStatus());
		verify(projects, never()).save(any());
	}

	@Test
	void secondCreateConflictsAndDoesNotInsertAnotherProject() {
		Project existing = new Project();
		existing.setId(UUID.randomUUID());
		team.setProject(existing);
		activeMembership();
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Second", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.PROJECT_ALREADY_EXISTS, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		verify(projects, never()).save(any());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void uniqueRaceIsMappedToConflictWithoutLeakingSql() {
		activeMembership();
		when(lockedTeams.findByIdForUpdate(team.getId())).thenReturn(Optional.of(team));
		when(projects.save(any(Project.class))).thenAnswer(this::persistProject);
		when(lockedTeams.save(any(Team.class))).thenAnswer(invocation -> invocation.getArgument(0));
		doThrow(new DataIntegrityViolationException(
						"could not execute statement",
						new SQLException("Duplicate entry for key 'uk_team_project'")))
				.when(lockedTeams)
				.flush();
		AcademicException ex = assertThrows(
				AcademicException.class,
				() -> service.create(
						student.getId(),
						course.getId(),
						new CreateStudentProjectRequest("Named", null, null),
						auditReq()));
		assertEquals(AcademicErrorCode.PROJECT_ALREADY_EXISTS, ex.getCode());
		assertEquals(HttpStatus.CONFLICT, ex.getStatus());
		assertFalse(ex.getMessage().toLowerCase().contains("sql"));
		assertFalse(ex.getMessage().contains("Duplicate"));
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void activeLeaderAndMemberCanReadCreatedProject() {
		Project project = persistedProject();
		team.setProject(project);
		activeMembership();
		StudentProjectResponse leaderView = service.getProject(student.getId(), course.getId());
		assertEquals(project.getId(), leaderView.projectId());
		assertEquals("SAGA Learning Platform", leaderView.name());
		leader.setRoleInTeam(RoleInTeam.MEMBER);
		StudentProjectResponse memberView = service.getProject(student.getId(), course.getId());
		assertEquals(project.getId(), memberView.projectId());
		assertEquals(student.getId(), memberView.createdBy().userId());
		assertFalse(memberView.toString().toLowerCase().contains("token"));
	}

	@Test
	void getProjectWhenMissingIsNotFound() {
		activeMembership();
		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.getProject(student.getId(), course.getId()));
		assertEquals(AcademicErrorCode.PROJECT_NOT_FOUND, ex.getCode());
		assertEquals(HttpStatus.NOT_FOUND, ex.getStatus());
	}

	@Test
	void getProjectOutsideCourseIsForbidden() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.empty());
		AcademicException ex =
				assertThrows(AcademicException.class, () -> service.getProject(student.getId(), course.getId()));
		assertEquals(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN, ex.getCode());
	}

	private void activeMembership() {
		when(students.findByUserAccount_Id(student.getId())).thenReturn(Optional.of(profile));
		when(enrollments.findByStudentProfile_IdAndCourse_Id(profile.getId(), course.getId()))
				.thenReturn(Optional.of(enrollment));
		when(members.findByCourseEnrollment_Id(enrollment.getId())).thenReturn(Optional.of(leader));
	}

	private Project persistProject(org.mockito.invocation.InvocationOnMock invocation) {
		Project project = invocation.getArgument(0);
		if (project.getId() == null) {
			project.setId(UUID.randomUUID());
		}
		if (project.getCreatedAt() == null) {
			project.setCreatedAt(LocalDateTime.now());
		}
		return project;
	}

	private Project persistedProject() {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		project.setCourse(course);
		project.setName("SAGA Learning Platform");
		project.setCreatedBy(student);
		project.setCreatedAt(LocalDateTime.now());
		return project;
	}

	private static ProjectType type(String code, String name) {
		ProjectType type = new ProjectType();
		type.setId(UUID.randomUUID());
		type.setCode(code);
		type.setName(name);
		type.setDescription("Canonical SAGA project-type catalog");
		return type;
	}

	private static TeamMember member(Team team, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-1", "127.0.0.1", "test");
	}
}

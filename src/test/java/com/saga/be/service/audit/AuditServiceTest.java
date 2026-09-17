package com.saga.be.service.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.StudentProfileRepository;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class AuditServiceTest {

	@Test
	void actorSnapshotsArePersistedAndProfileActionsHaveNoClassContext() {
		AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
		StudentProfileRepository students = Mockito.mock(StudentProfileRepository.class);
		Mockito.when(logs.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
		UserAccount actor = new UserAccount();
		actor.setId(UUID.randomUUID());
		actor.setFullName("Alice Nguyen");
		actor.setEmail("alice@example.com");
		actor.setAccountRole(AccountRole.STUDENT);
		StudentProfile profile = new StudentProfile();
		profile.setStudentCode("SE123456");
		Mockito.when(students.findByUserAccount_Id(actor.getId())).thenReturn(Optional.of(profile));
		AuditService service = new AuditService(logs, students, new AuditRedactor(new ObjectMapper()), new ObjectMapper());
		AuditLog saved = service.record(
				actor,
				null,
				null,
				"GITHUB_IDENTITY_LINKED",
				"identity_map",
				UUID.randomUUID(),
				Map.of("password", "nope"),
				Map.of("login", "alice-gh"),
				Map.of("refresh_token", "rt"),
				AuditSource.OAUTH,
				"req-1",
				"127.0.0.1",
				"JUnit");
		assertEquals("Alice Nguyen", saved.getActorFullNameSnapshot());
		assertEquals("SE123456", saved.getActorStudentCodeSnapshot());
		assertEquals("STUDENT", saved.getActorRoleSnapshot());
		assertNull(saved.getContextClassId());
		assertTrue(saved.getBeforeData().contains(AuditRedactor.REDACTED));
		assertTrue(saved.getMetadataJson().contains(AuditRedactor.REDACTED));
		ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
		Mockito.verify(logs).save(captor.capture());
		assertEquals("GITHUB_IDENTITY_LINKED", captor.getValue().getAction());
	}

	@Test
	void lazyCourseOnProjectIsNotSwallowed() {
		AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
		StudentProfileRepository students = Mockito.mock(StudentProfileRepository.class);
		Project project = Mockito.mock(Project.class);
		Mockito.when(project.getCourse())
				.thenThrow(new LazyInitializationException("could not initialize proxy [Course] - no Session"));
		AuditService service = new AuditService(logs, students, new AuditRedactor(new ObjectMapper()), new ObjectMapper());
		UserAccount actor = new UserAccount();
		actor.setId(UUID.randomUUID());
		LazyInitializationException ex = assertThrows(
				LazyInitializationException.class,
				() -> service.record(
						actor,
						project,
						null,
						"GITHUB_INSTALLATION_CONNECTED",
						"github_installation",
						UUID.randomUUID(),
						Map.of(),
						Map.of("installationId", 158868603L),
						Map.of(),
						AuditSource.OAUTH,
						null,
						null,
						null));
		assertTrue(ex.getMessage().contains("could not initialize proxy"));
		Mockito.verify(logs, Mockito.never()).save(Mockito.any());
	}

	@Test
	void projectSnapshotIsWrittenAtEventTimeAndOlderRowIsNotRewritten() {
		AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
		StudentProfileRepository students = Mockito.mock(StudentProfileRepository.class);
		Mockito.when(logs.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
		Mockito.when(students.findByUserAccount_Id(Mockito.any())).thenReturn(Optional.empty());
		AuditService service = new AuditService(logs, students, new AuditRedactor(new ObjectMapper()), new ObjectMapper());
		UserAccount actor = actor();
		Project project = project("SAGA V1");
		Team team = team(2, "Alpha");

		AuditLog first = service.record(
				actor,
				project,
				team,
				"PROJECT_CREATED",
				"project",
				project.getId(),
				null,
				Map.of("name", "SAGA V1"),
				Map.of("note", "create"),
				AuditSource.API,
				"req-1",
				null,
				null);
		assertEquals(project.getId(), first.getContextProjectId());
		assertEquals("SAGA V1", first.getContextProjectNameSnapshot());
		assertEquals(team.getId(), first.getContextTeamId());
		assertEquals(2, first.getContextTeamNoSnapshot());
		assertEquals("Alpha", first.getContextTeamNameSnapshot());
		assertTrue(first.getMetadataJson().contains("\"note\":\"create\""));
		assertTrue(!first.getMetadataJson().contains("projectSnapshot"));
		assertTrue(!first.getMetadataJson().contains("teamSnapshot"));

		project.setName("SAGA V2");
		AuditLog second = service.record(
				actor,
				project,
				team,
				"PROJECT_UPDATED",
				"project",
				project.getId(),
				Map.of("name", "SAGA V1"),
				Map.of("name", "SAGA V2"),
				Map.of(),
				AuditSource.API,
				"req-2",
				null,
				null);
		assertEquals("SAGA V1", first.getContextProjectNameSnapshot());
		assertEquals("SAGA V2", second.getContextProjectNameSnapshot());
		assertTrue(second.getBeforeData().contains("SAGA V1"));
		assertTrue(second.getAfterData().contains("SAGA V2"));
		assertNull(second.getMetadataJson());
		Mockito.verify(logs, Mockito.times(2)).save(Mockito.any());
	}

	@Test
	void teamOnlyWriterDoesNotInventProjectContextOrTouchTeamProject() {
		AuditLogRepository logs = Mockito.mock(AuditLogRepository.class);
		StudentProfileRepository students = Mockito.mock(StudentProfileRepository.class);
		Mockito.when(logs.save(Mockito.any())).thenAnswer(invocation -> invocation.getArgument(0));
		Mockito.when(students.findByUserAccount_Id(Mockito.any())).thenReturn(Optional.empty());
		Team team = Mockito.mock(Team.class);
		UUID teamId = UUID.randomUUID();
		Mockito.when(team.getId()).thenReturn(teamId);
		Mockito.when(team.getTeamNo()).thenReturn(7);
		Mockito.when(team.getName()).thenReturn("Gamma");
		AuditService service = new AuditService(logs, students, new AuditRedactor(new ObjectMapper()), new ObjectMapper());

		AuditLog saved = service.record(
				actor(),
				null,
				team,
				"TEAM_RENAMED",
				"team",
				teamId,
				Map.of("name", "Old"),
				Map.of("name", "Gamma"),
				Map.of(),
				AuditSource.API,
				null,
				null,
				null);
		assertEquals(teamId, saved.getContextTeamId());
		assertEquals(7, saved.getContextTeamNoSnapshot());
		assertEquals("Gamma", saved.getContextTeamNameSnapshot());
		assertNull(saved.getContextProjectId());
		assertNull(saved.getContextProjectNameSnapshot());
		Mockito.verify(team, Mockito.never()).getProject();
	}

	private static UserAccount actor() {
		UserAccount actor = new UserAccount();
		actor.setId(UUID.randomUUID());
		actor.setFullName("Alice Nguyen");
		actor.setEmail("alice@example.com");
		actor.setAccountRole(AccountRole.STUDENT);
		return actor;
	}

	private static Project project(String name) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setId(UUID.randomUUID());
		academicClass.setClassCode("SE1801");
		academicClass.setName("SE1801");
		Course course = new Course();
		course.setId(UUID.randomUUID());
		course.setAcademicClass(academicClass);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		project.setName(name);
		project.setCourse(course);
		return project;
	}

	private static Team team(int teamNo, String name) {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(teamNo);
		team.setName(name);
		return team;
	}
}

package com.saga.be.service.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class TaskEvidenceServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWorkSessionRepository sessions;
	@Mock
	private ContributionConfirmationRepository confirmations;
	@Mock
	private TeamByProjectRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private UserAccountRepository users;
	@Mock
	private PasswordEncoder passwordEncoder;

	private TaskEvidenceService service;
	private UserAccount student;
	private Project project;
	private Team team;
	private Task task;

	@BeforeEach
	void setUp() {
		IntegrationProperties properties = new IntegrationProperties();
		properties.setReauthWindow(Duration.ofMinutes(10));
		service = new TaskEvidenceService(
				tasks, sessions, confirmations, teams, members, users, passwordEncoder, properties);
		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setAccountRole(AccountRole.STUDENT);
		project = new Project();
		project.setId(UUID.randomUUID());
		Course course = new Course();
		course.setId(UUID.randomUUID());
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		team.setProject(project);
		task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
	}

	@Test
	void startAuthorizesWithTargetedMembershipQuery() {
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(true);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(sessions.save(any(TaskWorkSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.start(student.getId(), task.getId());

		verify(members, times(1)).existsByProjectIdAndUserId(project.getId(), student.getId());
		verify(members, never()).findByTeam_Id(any());
	}

	@Test
	void startRejectsNonMemberWithoutLoadingRoster() {
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.existsByProjectIdAndUserId(project.getId(), student.getId())).thenReturn(false);

		IntegrationException ex =
				assertThrows(IntegrationException.class, () -> service.start(student.getId(), task.getId()));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
		verify(members, never()).findByTeam_Id(any());
		verify(sessions, never()).save(any());
	}
}

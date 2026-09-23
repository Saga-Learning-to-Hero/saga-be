package com.saga.be.service.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TaskWebLinkServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWebLinkRepository links;
	@Mock
	private TeamByProjectRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private UserAccountRepository users;
	@Mock
	private ProjectRepository projects;

	private TaskWebLinkService service;
	private UUID userId;
	private UUID projectId;
	private UUID taskId;

	@BeforeEach
	void setUp() {
		service = new TaskWebLinkService(
				tasks, links, teams, members, users, new ProjectDataAuthorization(users, members, projects));
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		taskId = UUID.randomUUID();
		Project project = new Project();
		project.setId(projectId);
		Task task = new Task();
		task.setId(taskId);
		task.setProject(project);
		when(tasks.findActiveFetchedById(taskId)).thenReturn(Optional.of(task));
	}

	@Test
	void assignedLecturer_canListTaskWebLinks() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		when(links.findByTask_IdOrderByCreatedAtAsc(taskId)).thenReturn(List.of());

		assertThat(service.list(userId, taskId)).isEmpty();
	}

	@Test
	void unassignedLecturer_cannotListTaskWebLinks() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);

		assertThatThrownBy(() -> service.list(userId, taskId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		verify(links, never()).findByTask_IdOrderByCreatedAtAsc(taskId);
	}

	@Test
	void lecturerWriteRemainsSubjectToTheExistingTeamMemberPolicy() {
		assertThatThrownBy(() -> service.delete(userId, taskId, UUID.randomUUID())).isInstanceOf(RuntimeException.class);
		verify(projects, never()).existsAssignedToLecturerUser(projectId, userId);
	}

	private void stubRole(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}
}

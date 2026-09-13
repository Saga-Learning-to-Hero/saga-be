package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.CreatedIssue;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ProjectJiraTaskCommandServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWorkSessionRepository workSessions;
	@Mock
	private ContributionConfirmationRepository confirmations;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private JiraTeamTokenService tokens;
	@Mock
	private JiraIssueWriteClient jiraWrite;
	@Mock
	private JiraTaskProjectionService projection;
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private ApplicationEventPublisher events;

	private ProjectJiraTaskCommandService service;
	private UUID userId;
	private UUID projectId;
	private final AtomicReference<ProjectRealtimeEvent> lastEvent = new AtomicReference<>();

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		ProjectRealtimePublisher realtime = new ProjectRealtimePublisher(event -> {
			lastEvent.set((ProjectRealtimeEvent) event);
			events.publishEvent(event);
		});
		service = new ProjectJiraTaskCommandService(
				authorization,
				projects,
				jiraIntegrations,
				tasks,
				workSessions,
				confirmations,
				links,
				tokens,
				jiraWrite,
				projection,
				realtime,
				transactionManager);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		lastEvent.set(null);
	}

	@Test
	void create_callsJiraThenPersistsCanonicalProjection() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		Task saved = new Task();
		saved.setId(UUID.randomUUID());
		saved.setExternalId("10001");
		saved.setExternalKey("SAGA-1");
		saved.setTitle("Login");
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(saved);

		ProjectTaskResponse response = service.create(
				userId, projectId, new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null));

		assertThat(response.externalKey()).isEqualTo("SAGA-1");
		verify(jiraWrite).createIssue(eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any());
		verify(projection).upsertOne(project, "SAGA", canonical);
		assertThat(lastEvent.get()).isNotNull();
		assertThat(lastEvent.get().type()).isEqualTo(ProjectRealtimeEventType.TASKS_CHANGED);
	}

	@Test
	void create_providerFailure_doesNotPersistLocalTask() {
		stubLeader();
		JiraIntegration integration = activeJira();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project()));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_ISSUE_CREATE_FAILED,
						org.springframework.http.HttpStatus.BAD_GATEWAY,
						"fail"));

		assertThatThrownBy(() -> service.create(
						userId,
						projectId,
						new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_ISSUE_CREATE_FAILED);
		verify(projection, never()).upsertOne(any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void transition_usesTransitionApi() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.listTransitions("token", "cloud", "10001"))
				.thenReturn(List.of(new TransitionOption("21", "Start", "3", "In Progress")));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.transition(userId, projectId, task.getId(), new TransitionProjectTaskRequest(null, "3"));

		verify(jiraWrite).transitionIssue("token", "cloud", "10001", "21");
	}

	@Test
	void delete_blockedByProtectedEvidence() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(workSessions.existsByTask_Id(task.getId())).thenReturn(true);

		assertThatThrownBy(() -> service.delete(userId, projectId, task.getId()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_EVIDENCE);
		verify(jiraWrite, never()).deleteIssue(any(), any(), any());
	}

	@Test
	void delete_callsJiraThenSoftDeletesProjection() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(workSessions.existsByTask_Id(task.getId())).thenReturn(false);
		when(confirmations.existsByTask_Id(task.getId())).thenReturn(false);
		when(tokens.accessToken(integration)).thenReturn("token");

		service.delete(userId, projectId, task.getId());

		verify(jiraWrite).deleteIssue("token", "cloud", "10001");
		verify(projection).softDelete(eq(project), eq("10001"), any());
	}

	@Test
	void patch_moveToSprint_usesAgileApiThenReconciles() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(null, null, null, null, null, null, null, "31", null, null, null));

		verify(jiraWrite).moveIssuesToSprint("token", "cloud", "31", List.of("10001"));
		verify(projection).upsertOne(project, "SAGA", canonical);
	}

	@Test
	void putSprint_movesToBacklogWhenNull() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.moveSprint(userId, projectId, task.getId(), new PutProjectTaskSprintRequest(null));

		verify(jiraWrite).moveIssuesToBacklog("token", "cloud", "68", List.of("10001"));
	}

	@Test
	void patch_storyPoints_usesAgileEstimationApi() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(null, null, null, null, null, null, 5, null, null, null, null));

		verify(jiraWrite).setIssueEstimation("token", "cloud", "68", "10001", 5);
	}

	@Test
	void patch_summaryOnly_sendsOnlySummaryField() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "New title");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest("New title", null, null, null, null, null, null, null, null, null, null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsOnlyKeys("summary");
		assertThat(captor.getValue()).containsEntry("summary", "New title");
		verify(jiraWrite, never()).setIssueEstimation(any(), any(), any(), any(), any());
		verify(jiraWrite, never()).moveIssuesToSprint(any(), any(), any(), any());
		verify(jiraWrite, never()).moveIssuesToBacklog(any(), any(), any(), any());
		verify(jiraWrite, never()).transitionIssue(any(), any(), any(), any());
	}

	@Test
	void patch_assigneeOnly_sendsOnlyAssigneeField() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(null, null, null, "acct-99", null, null, null, null, null, null, null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsOnlyKeys("assignee");
	}

	@Test
	void patch_priorityOnly_sendsOnlyPriorityField() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(null, null, null, null, null, "3", null, null, null, null, null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsOnlyKeys("priority");
	}

	@Test
	void patch_moveToBacklog_usesAgileApiThenReconciles() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, Boolean.TRUE, null, null));

		verify(jiraWrite).moveIssuesToBacklog("token", "cloud", "68", List.of("10001"));
		verify(jiraWrite, never()).moveIssuesToSprint(any(), any(), any(), any());
	}

	@Test
	void patch_neverSendsStatusThroughGenericFields_evenWhenTransitionFieldsProvided() {
		// PatchProjectTaskRequest carries transitionId/targetStatusId only so the DTO can be reused
		// as validation input elsewhere; patch() itself must never act on them or call the
		// transitions API -- status changes require the dedicated POST .../transition endpoint.
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(null, null, null, null, null, null, null, null, null, "21", "3"));

		verify(jiraWrite, never()).transitionIssue(any(), any(), any(), any());
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
	}

	@Test
	void patch_jiraRejectsFieldUpdate_localTaskNotMutated() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project()));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		org.mockito.Mockito.doThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_FIELD_INVALID,
						org.springframework.http.HttpStatus.BAD_REQUEST,
						"Jira field(s) not editable for this issue: customfield_10016"))
				.when(jiraWrite)
				.updateIssueFields(any(), any(), any(), any());

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								"New title", null, null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(jiraWrite, never()).getIssue(any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void nonLeader_cannotCreate() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		assertThatThrownBy(() -> service.create(
						userId,
						projectId,
						new CreateProjectTaskRequest("X", null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
	}

	private void stubLeader() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	private JiraIntegration activeJira() {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setCloudId("cloud");
		integration.setJiraProjectId("10067");
		integration.setProjectKey("SAGA");
		integration.setJiraBoardId("68");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		return integration;
	}

	private Project project() {
		Project project = new Project();
		project.setId(projectId);
		return project;
	}

	private Task taskRow() {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalId("10001");
		task.setExternalKey("SAGA-1");
		task.setTitle("Login");
		return task;
	}

	private static IssueSummary summary(String id, String key, String title) {
		return new IssueSummary(
				id, key, title, "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null, null, null,
				null, null, "2026-01-02T10:00:00Z");
	}
}

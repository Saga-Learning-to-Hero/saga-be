package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.JiraWriteIncompleteDetails;
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
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
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
import com.saga.be.repository.TaskParentIdentity;
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
				new TaskHierarchyService(projects, tasks, transactionManager),
				transactionManager);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		lastEvent.set(null);
		org.mockito.Mockito.lenient().when(projects.lockById(any())).thenAnswer(inv -> {
			Project locked = new Project();
			locked.setId(inv.getArgument(0));
			return Optional.of(locked);
		});
	}

	@Test
	void create_callsJiraThenPersistsCanonicalProjection() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(), any(),
						any(), any()))
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
		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(), any(),
						any(), any());
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
		when(jiraWrite.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
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
	void create_withLabels_passesLabelsToJira() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						eq(List.of("backend", "urgent")), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		service.create(
				userId,
				projectId,
				new CreateProjectTaskRequest(
						"Login", null, null, null, null, null, null, null, List.of("backend", "urgent")));

		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						eq(List.of("backend", "urgent")), any(), any());
	}

	@Test
	void create_withoutLabels_doesNotSendAccidentalLabels() {
		// labels omitted on create must reach the write client as null (Jira's own default for
		// the issue type), never as an implicit empty list that would clear a template's labels.
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						isNull(), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		service.create(
				userId, projectId, new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null));

		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						isNull(), any(), any());
	}

	@Test
	void patch_labelsOmitted_preservesExistingLabels() {
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
		assertThat(captor.getValue()).doesNotContainKey("labels");
	}

	@Test
	void patch_labelsEmptyList_clearsAllLabels() {
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
						null, null, null, null, null, null, null, null, null, null, null, List.of()));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsEntry("labels", List.of());
	}

	@Test
	void patch_labelsSupplied_replacesWithExactList() {
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
						null, null, null, null, null, null, null, null, null, null, null,
						List.of("backend", "urgent")));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsEntry("labels", List.of("backend", "urgent"));
	}

	@Test
	void patch_jiraRejectsLabelUpdate_localTaskNotMutated() {
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
						"Jira field(s) not editable for this issue: labels"))
				.when(jiraWrite)
				.updateIssueFields(any(), any(), any(), any());

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null,
								List.of("backend"))))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(jiraWrite, never()).getIssue(any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void create_withDueDate_passesDueDateToJira() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		java.time.LocalDate dueDate = java.time.LocalDate.of(2026, 9, 18);
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), eq(dueDate), isNull()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		service.create(
				userId,
				projectId,
				new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null, null, dueDate));

		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), eq(dueDate), isNull());
	}

	@Test
	void patch_dueDateOmitted_preservesExistingDueDate() {
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
				new PatchProjectTaskRequest(
						"New title", null, null, null, null, null, null, null, null, null, null, null, null, null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).doesNotContainKey("duedate");
	}

	@Test
	void patch_dueDateSupplied_sendsDuedateField() {
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
						null, null, null, null, null, null, null, null, null, null, null, null,
						java.time.LocalDate.of(2026, 9, 18), null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsEntry("duedate", "2026-09-18");
	}

	@Test
	void patch_clearDueDate_sendsExplicitNullDuedate() {
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
						null, null, null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsKey("duedate");
		assertThat(captor.getValue().get("duedate")).isNull();
	}

	@Test
	void patch_jiraRejectsDueDateUpdate_localTaskNotMutated() {
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
						"Jira field(s) not editable for this issue: duedate"))
				.when(jiraWrite)
				.updateIssueFields(any(), any(), any(), any());

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null,
								java.time.LocalDate.of(2026, 9, 18), null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(jiraWrite, never()).getIssue(any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void create_withStartDate_passesStartDateToJira() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		java.time.LocalDate startDate = java.time.LocalDate.of(2026, 9, 14);
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), isNull(), eq(startDate)))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		service.create(
				userId,
				projectId,
				new CreateProjectTaskRequest(
						"Login", null, null, null, null, null, null, null, null, null, startDate));

		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), isNull(), eq(startDate));
	}

	@Test
	void create_withoutStartDate_doesNotSendAccidentalStartDate() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), any(), isNull()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		service.create(
				userId, projectId, new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null));

		verify(jiraWrite)
				.createIssue(
						eq("token"), eq("cloud"), eq("10067"), eq("Login"), any(), any(), any(), any(), any(),
						any(), any(), isNull());
	}

	@Test
	void patch_startDateOmitted_preservesExistingStartDate() {
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
				new PatchProjectTaskRequest(
						"New title", null, null, null, null, null, null, null, null, null, null, null, null, null,
						null, null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue().keySet())
				.noneMatch(key -> key.startsWith("customfield_") || "startDate".equalsIgnoreCase(key));
		verify(jiraWrite, never()).requireStartDateFieldId(any(), any());
		verify(jiraWrite, never()).resolveStartDateFieldId(any(), any());
	}

	@Test
	void patch_startDateSupplied_sendsResolvedStartDateField() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.requireStartDateFieldId("token", "cloud")).thenReturn("customfield_10015");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						java.time.LocalDate.of(2026, 9, 14), null));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsEntry("customfield_10015", "2026-09-14");
	}

	@Test
	void patch_clearStartDate_sendsExplicitNullOnResolvedField() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.requireStartDateFieldId("token", "cloud")).thenReturn("customfield_10015");
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						Boolean.TRUE));

		@SuppressWarnings("unchecked")
		org.mockito.ArgumentCaptor<java.util.Map<String, Object>> captor =
				org.mockito.ArgumentCaptor.forClass(java.util.Map.class);
		verify(jiraWrite).updateIssueFields(eq("token"), eq("cloud"), eq("10001"), captor.capture());
		assertThat(captor.getValue()).containsKey("customfield_10015");
		assertThat(captor.getValue().get("customfield_10015")).isNull();
	}

	@Test
	void patch_unresolvedStartDateField_doesNotGuessOrWrite() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project()));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.requireStartDateFieldId("token", "cloud"))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_FIELD_INVALID,
						org.springframework.http.HttpStatus.BAD_REQUEST,
						"Start Date field could not be resolved for this Jira site."));

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								java.time.LocalDate.of(2026, 9, 14), null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(jiraWrite, never()).getIssue(any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void patch_jiraRejectsStartDateUpdate_localTaskNotMutated() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project()));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.requireStartDateFieldId("token", "cloud")).thenReturn("customfield_10015");
		org.mockito.Mockito.doThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_FIELD_INVALID,
						org.springframework.http.HttpStatus.BAD_REQUEST,
						"Jira field(s) not editable for this issue: customfield_10015"))
				.when(jiraWrite)
				.updateIssueFields(any(), any(), any(), any());

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								java.time.LocalDate.of(2026, 9, 14), null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(jiraWrite, never()).getIssue(any(), any(), any());
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

	// ==================== AUTHORIZATION MATRIX (section 1/2/3 audit) ====================
	// requireStudentLeader is shared, identical code across every mutation method below --
	// verified once per role here for create/patch/delete/transition/moveSprint (member, the
	// specifically reported gap, on every category) plus representative coverage for
	// lecturer/admin/unrelated-student (same guard, so exhaustive per-role x per-category
	// duplication adds no additional coverage of the actual authorization code).

	@Test
	void member_cannotPatchTask() {
		stubMember();
		UUID taskId = UUID.randomUUID();

		assertThatThrownBy(() -> service.patch(
						userId, projectId, taskId,
						new PatchProjectTaskRequest("New title", null, null, null, null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void member_cannotDeleteTask() {
		stubMember();
		UUID taskId = UUID.randomUUID();

		assertThatThrownBy(() -> service.delete(userId, projectId, taskId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verify(jiraWrite, never()).deleteIssue(any(), any(), any());
	}

	@Test
	void member_cannotTransitionTask() {
		stubMember();
		UUID taskId = UUID.randomUUID();

		assertThatThrownBy(() -> service.transition(userId, projectId, taskId, new TransitionProjectTaskRequest(null, "3")))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void member_cannotMoveTaskSprint() {
		stubMember();
		UUID taskId = UUID.randomUUID();

		assertThatThrownBy(() -> service.moveSprint(userId, projectId, taskId, new PutProjectTaskSprintRequest(31L)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void unrelatedStudent_cannotCreateTask() {
		stubUnrelatedStudent();
		assertThatThrownBy(() -> service.create(
						userId, projectId, new CreateProjectTaskRequest("X", null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verifyZeroProviderInteraction();
	}

	@Test
	void assignedLecturer_cannotCreateTask() {
		stubLecturer();
		assertThatThrownBy(() -> service.create(
						userId, projectId, new CreateProjectTaskRequest("X", null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class);
		verifyZeroProviderInteraction();
	}

	@Test
	void admin_cannotCreateTask() {
		stubAdmin();
		assertThatThrownBy(() -> service.create(
						userId, projectId, new CreateProjectTaskRequest("X", null, null, null, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verifyZeroProviderInteraction();
	}

	@Test
	void admin_cannotDeleteTask() {
		stubAdmin();
		assertThatThrownBy(() -> service.delete(userId, projectId, UUID.randomUUID()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verify(jiraWrite, never()).deleteIssue(any(), any(), any());
	}

	@Test
	void create_withoutParent_doesNotAssignNativeParent() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(taskRow());

		ProjectTaskResponse response = service.create(
				userId, projectId, new CreateProjectTaskRequest("Login", null, null, null, null, null, null, null));

		assertThat(response.parentTask()).isNull();
		verify(tasks, never()).save(any());
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
	}

	@Test
	void create_invalidParent_failsBeforeJiraCreateIssue() {
		stubLeader();
		UUID parentId = UUID.randomUUID();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(activeJira()));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project()));
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(
						userId,
						projectId,
						new CreateProjectTaskRequest(
								"Login", null, null, null, null, null, null, null, null, null, null, parentId)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(tokens, never()).accessToken(any());
	}

	@Test
	void create_withParent_assignsAfterLocalUpsert() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		UUID parentId = UUID.randomUUID();
		Task parent = new Task();
		parent.setId(parentId);
		parent.setTitle("Parent");
		parent.setProject(project);
		Task saved = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, null)));
		when(tasks.findParentIdentity(saved.getId())).thenReturn(Optional.of(identity(saved.getId(), projectId, null)));
		when(tasks.findParentTaskIdById(parentId)).thenReturn(Optional.empty());
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(saved.getId(), projectId)).thenReturn(Optional.of(saved));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(parentId, projectId)).thenReturn(Optional.of(parent));
		when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(saved);

		ProjectTaskResponse response = service.create(
				userId,
				projectId,
				new CreateProjectTaskRequest(
						"Login", null, null, null, null, null, null, null, null, null, null, parentId));

		assertThat(response.parentTask()).isNotNull();
		assertThat(response.parentTask().id()).isEqualTo(parentId);
		verify(jiraWrite)
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
	}

	@Test
	void create_jiraSucceeds_finalParentValidationFails_commitsProjectionWithoutParent() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		UUID parentId = UUID.randomUUID();
		Task saved = taskRow();
		Task parent = new Task();
		parent.setId(parentId);
		parent.setTitle("Parent");
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findParentIdentity(parentId))
				.thenReturn(Optional.of(identity(parentId, projectId, null)))
				.thenReturn(Optional.of(identity(parentId, projectId, java.time.LocalDateTime.now())));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(saved.getId(), projectId)).thenReturn(Optional.of(saved));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new CreatedIssue("10001", "SAGA-1"));
		IssueSummary canonical = summary("10001", "SAGA-1", "Login");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(saved);

		IntegrationException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> service.create(
						userId,
						projectId,
						new CreateProjectTaskRequest(
								"Login", null, null, null, null, null, null, null, null, null, null, parentId)),
				IntegrationException.class);

		assertThat(thrown.getCode()).isEqualTo(IntegrationErrorCode.JIRA_WRITE_INCOMPLETE);
		assertThat(thrown.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
		assertThat(thrown.getMessage()).contains("Local Task already exists");
		assertThat(thrown.getMessage()).contains("do not retry create");
		assertThat(thrown.getDetails()).isEqualTo(JiraWriteIncompleteDetails.nativeParentNotApplied(saved.getId()));
		verify(jiraWrite, times(1))
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(projection, times(1)).upsertOne(project, "SAGA", canonical);
		verify(tasks, never()).save(any());
		assertThat(saved.getParentTask()).isNull();

		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, null)));
		when(tasks.findParentIdentity(saved.getId())).thenReturn(Optional.of(identity(saved.getId(), projectId, null)));
		when(tasks.findParentTaskIdById(parentId)).thenReturn(Optional.empty());
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(parentId, projectId)).thenReturn(Optional.of(parent));
		when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		UUID recoveredTaskId = ((JiraWriteIncompleteDetails) thrown.getDetails()).taskId();
		ProjectTaskResponse recovered = service.patch(
				userId,
				projectId,
				recoveredTaskId,
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						parentId, null));

		assertThat(recovered.parentTask()).isNotNull();
		assertThat(recovered.parentTask().id()).isEqualTo(parentId);
		verify(jiraWrite, times(1))
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
		verify(tokens, times(1)).accessToken(any());
	}

	@Test
	void patch_parentOnly_performsZeroJiraCalls() {
		stubLeader();
		Task task = taskRow();
		UUID parentId = UUID.randomUUID();
		Task parent = new Task();
		parent.setId(parentId);
		parent.setTitle("Parent");
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(parentId, projectId)).thenReturn(Optional.of(parent));
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, null)));
		when(tasks.findParentIdentity(task.getId())).thenReturn(Optional.of(identity(task.getId(), projectId, null)));
		when(tasks.findParentTaskIdById(parentId)).thenReturn(Optional.empty());
		when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						parentId, null));

		verifyZeroProviderInteraction();
		verify(jiraIntegrations, never()).findByProject_Id(any());
		verify(projection, never()).upsertOne(any(), any(), any());
		verify(projects).lockById(projectId);
		verify(tasks).save(task);
	}

	@Test
	void patch_clearParent_clearsNativeParentWithoutJira() {
		stubLeader();
		Task task = taskRow();
		Task parent = new Task();
		parent.setId(UUID.randomUUID());
		task.setParentTask(parent);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse response = service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						null, true));

		assertThat(response.parentTask()).isNull();
		verifyZeroProviderInteraction();
	}

	@Test
	void patch_mixed_validatesNativeParentBeforeProviderUpdate() {
		stubLeader();
		Task task = taskRow();
		UUID parentId = UUID.randomUUID();
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								"New title",
								null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								parentId,
								null)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
		verify(tokens, never()).accessToken(any());
	}

	@Test
	void patch_mixed_jiraSucceeds_finalParentValidationFails_commitsProjectionWithoutParent() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		UUID parentId = UUID.randomUUID();
		Task parent = new Task();
		parent.setId(parentId);
		parent.setTitle("Parent");
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(tasks.findParentIdentity(parentId))
				.thenReturn(Optional.of(identity(parentId, projectId, null)))
				.thenReturn(Optional.of(identity(parentId, projectId, java.time.LocalDateTime.now())));
		when(tasks.findParentIdentity(task.getId())).thenReturn(Optional.of(identity(task.getId(), projectId, null)));
		when(tasks.findParentTaskIdById(parentId)).thenReturn(Optional.empty());
		when(tokens.accessToken(integration)).thenReturn("token");
		IssueSummary canonical = summary("10001", "SAGA-1", "New title");
		when(jiraWrite.getIssue("token", "cloud", "10001")).thenReturn(canonical);
		when(projection.upsertOne(project, "SAGA", canonical)).thenReturn(task);

		IntegrationException thrown = org.assertj.core.api.Assertions.catchThrowableOfType(
				() -> service.patch(
						userId,
						projectId,
						task.getId(),
						new PatchProjectTaskRequest(
								"New title",
								null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								parentId,
								null)),
				IntegrationException.class);

		assertThat(thrown.getCode()).isEqualTo(IntegrationErrorCode.JIRA_WRITE_INCOMPLETE);
		assertThat(thrown.getStatus()).isEqualTo(org.springframework.http.HttpStatus.BAD_GATEWAY);
		assertThat(thrown.getDetails()).isEqualTo(JiraWriteIncompleteDetails.nativeParentNotApplied(task.getId()));
		verify(jiraWrite, times(1)).updateIssueFields(any(), any(), any(), any());
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(projection, times(1)).upsertOne(project, "SAGA", canonical);
		verify(tasks, never()).save(any());
		assertThat(task.getParentTask()).isNull();

		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, null)));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(parentId, projectId)).thenReturn(Optional.of(parent));
		when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(links.countLinksByProjectGrouped(projectId)).thenReturn(List.of());

		ProjectTaskResponse recovered = service.patch(
				userId,
				projectId,
				task.getId(),
				new PatchProjectTaskRequest(
						null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
						parentId, null));

		assertThat(recovered.parentTask()).isNotNull();
		assertThat(recovered.parentTask().id()).isEqualTo(parentId);
		verify(jiraWrite, times(1)).updateIssueFields(any(), any(), any(), any());
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(tokens, times(1)).accessToken(any());
	}

	@Test
	void patch_contradictoryParentInputRejected() {
		stubLeader();
		UUID taskId = UUID.randomUUID();
		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						taskId,
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								UUID.randomUUID(),
								true)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		verifyZeroProviderInteraction();
	}

	@Test
	void delete_blockedByActiveNativeChildren_beforeJiraDelete() {
		stubLeader();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(activeJira()));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(workSessions.existsByTask_Id(task.getId())).thenReturn(false);
		when(confirmations.existsByTask_Id(task.getId())).thenReturn(false);
		when(tasks.existsByParentTask_IdAndDeletedAtIsNull(task.getId())).thenReturn(true);

		assertThatThrownBy(() -> service.delete(userId, projectId, task.getId()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_SUBTASKS);
		verify(jiraWrite, never()).deleteIssue(any(), any(), any());
	}

	@Test
	void delete_softDeletedNativeChildDoesNotBlock() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Project project = project();
		Task task = taskRow();
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(integration));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(workSessions.existsByTask_Id(task.getId())).thenReturn(false);
		when(confirmations.existsByTask_Id(task.getId())).thenReturn(false);
		when(tasks.existsByParentTask_IdAndDeletedAtIsNull(task.getId())).thenReturn(false);
		when(tokens.accessToken(integration)).thenReturn("token");

		service.delete(userId, projectId, task.getId());

		verify(tasks).existsByParentTask_IdAndDeletedAtIsNull(task.getId());
		verify(jiraWrite).deleteIssue("token", "cloud", "10001");
		verify(projection).softDelete(eq(project), eq("10001"), any());
	}

	@Test
	void lecturer_cannotPatchNativeParent() {
		stubLecturer();
		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						UUID.randomUUID(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								UUID.randomUUID(),
								null)))
				.isInstanceOf(IntegrationException.class);
		verifyZeroProviderInteraction();
	}

	@Test
	void admin_cannotPatchNativeParent() {
		stubAdmin();
		assertThatThrownBy(() -> service.patch(
						userId,
						projectId,
						UUID.randomUUID(),
						new PatchProjectTaskRequest(
								null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
								UUID.randomUUID(),
								null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
	}

	private void verifyZeroProviderInteraction() {
		verify(tokens, never()).accessToken(any());
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
		verify(jiraWrite, never()).updateIssueFields(any(), any(), any(), any());
		verify(jiraWrite, never()).transitionIssue(any(), any(), any(), any());
		verify(jiraWrite, never()).moveIssuesToSprint(any(), any(), any(), any());
		verify(jiraWrite, never()).moveIssuesToBacklog(any(), any(), any(), any());
	}

	private void stubLeader() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	private void stubMember() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
	}

	private void stubUnrelatedStudent() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());
	}

	private void stubLecturer() {
		UserAccount lecturer = new UserAccount();
		lecturer.setId(userId);
		lecturer.setAccountRole(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
	}

	private void stubAdmin() {
		UserAccount admin = new UserAccount();
		admin.setId(userId);
		admin.setAccountRole(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
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

	private static TaskParentIdentity identity(UUID id, UUID projectId, java.time.LocalDateTime deletedAt) {
		return new TaskParentIdentity() {
			@Override
			public UUID getId() {
				return id;
			}

			@Override
			public UUID getProjectId() {
				return projectId;
			}

			@Override
			public java.time.LocalDateTime getDeletedAt() {
				return deletedAt;
			}
		};
	}

	private static IssueSummary summary(String id, String key, String title) {
		return new IssueSummary(
				id, key, title, "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null, null, null,
				null, null, "2026-01-02T10:00:00Z");
	}
}

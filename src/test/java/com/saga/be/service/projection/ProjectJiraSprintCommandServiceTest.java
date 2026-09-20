package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.CreateProjectSprintRequest;
import com.saga.be.dto.project.PatchProjectSprintRequest;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.SprintDetail;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ProjectJiraSprintCommandServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private SprintRepository sprints;
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

	private ProjectJiraSprintCommandService service;
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
		service = new ProjectJiraSprintCommandService(
				authorization, jiraIntegrations, sprints, tokens, jiraWrite, projection, realtime, transactionManager);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		lastEvent.set(null);
	}

	@Test
	void patch_renameOnly_sendsOnlyNameToProvider() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail updated = new SprintDetail(31L, "Sprint Renamed", "active", null, null, null, null, null);
		when(jiraWrite.updateSprint("token", "cloud", "31", "Sprint Renamed", null, null, null, null))
				.thenReturn(updated);
		when(jiraWrite.getSprint("token", "cloud", "31")).thenReturn(updated);
		when(projection.upsertSprint(eq(integration), eq("31"), eq("Sprint Renamed"), eq("active"), any(), any(), any(), any()))
				.thenReturn(local);

		service.patch(userId, projectId, local.getId(), new PatchProjectSprintRequest("Sprint Renamed", null, null, null, null));

		verify(jiraWrite).updateSprint("token", "cloud", "31", "Sprint Renamed", null, null, null, null);
	}

	@Test
	void patch_goalOnly_sendsOnlyGoalToProvider() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail updated = new SprintDetail(31L, "Sprint 1", "active", null, null, null, "Ship auth", null);
		when(jiraWrite.updateSprint("token", "cloud", "31", null, "Ship auth", null, null, null))
				.thenReturn(updated);
		when(jiraWrite.getSprint("token", "cloud", "31")).thenReturn(updated);
		when(projection.upsertSprint(eq(integration), eq("31"), any(), any(), any(), any(), any(), any()))
				.thenReturn(local);

		service.patch(userId, projectId, local.getId(), new PatchProjectSprintRequest(null, "Ship auth", null, null, null));

		verify(jiraWrite).updateSprint("token", "cloud", "31", null, "Ship auth", null, null, null);
	}

	@Test
	void patch_datesOnly_sendsOnlyDatesToProvider() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail updated =
				new SprintDetail(31L, "Sprint 1", "active", "2026-02-01T00:00:00.000Z", "2026-02-14T00:00:00.000Z", null, null, null);
		when(jiraWrite.updateSprint(
						"token", "cloud", "31", null, null, null, "2026-02-01T00:00:00.000Z", "2026-02-14T00:00:00.000Z"))
				.thenReturn(updated);
		when(jiraWrite.getSprint("token", "cloud", "31")).thenReturn(updated);
		when(projection.upsertSprint(eq(integration), eq("31"), any(), any(), any(), any(), any(), any()))
				.thenReturn(local);

		service.patch(
				userId,
				projectId,
				local.getId(),
				new PatchProjectSprintRequest(null, null, null, "2026-02-01T00:00:00.000Z", "2026-02-14T00:00:00.000Z"));

		verify(jiraWrite)
				.updateSprint("token", "cloud", "31", null, null, null, "2026-02-01T00:00:00.000Z", "2026-02-14T00:00:00.000Z");
	}

	@Test
	void patch_stateChange_forwardsToProviderWhichEnforcesLifecycleRules() {
		// SAGA never invents its own lifecycle validation -- Jira's own sprint update endpoint
		// enforces valid state transitions (future -> active -> closed); this only verifies the
		// requested state is forwarded as-is, not reinterpreted or blocked locally.
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail updated = new SprintDetail(31L, "Sprint 1", "active", null, null, null, null, null);
		when(jiraWrite.updateSprint("token", "cloud", "31", null, null, "active", null, null)).thenReturn(updated);
		when(jiraWrite.getSprint("token", "cloud", "31")).thenReturn(updated);
		when(projection.upsertSprint(eq(integration), eq("31"), any(), eq("active"), any(), any(), any(), any()))
				.thenReturn(local);

		service.patch(userId, projectId, local.getId(), new PatchProjectSprintRequest(null, null, "active", null, null));

		verify(jiraWrite).updateSprint("token", "cloud", "31", null, null, "active", null, null);
	}

	@Test
	void patch_jiraRejectsSprintUpdate_localSprintNotMutated() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraWrite.updateSprint(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED, HttpStatus.BAD_GATEWAY, "Jira sprint update failed."));

		assertThatThrownBy(() -> service.patch(
						userId, projectId, local.getId(), new PatchProjectSprintRequest("New name", null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_SPRINT_WRITE_FAILED);
		verify(projection, never()).upsertSprint(any(), any(), any(), any(), any(), any(), any(), any());
		assertThat(lastEvent.get()).isNull();
	}

	@Test
	void create_leader_callsJiraThenPersistsCanonicalProjection() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow();
		when(jiraIntegrations.findAllByProject_Id(projectId)).thenReturn(List.of(integration));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail created = new SprintDetail(31L, "Sprint 1", "future", null, null, null, null, null);
		when(jiraWrite.createSprint("token", "cloud", "68", "Sprint 1", null, null, null)).thenReturn(created);
		when(jiraWrite.getSprint("token", "cloud", "31")).thenReturn(created);
		when(projection.upsertSprint(eq(integration), eq("31"), eq("Sprint 1"), eq("future"), any(), any(), any(), any()))
				.thenReturn(local);

		ProjectSprintResponse response =
				service.create(userId, projectId, new CreateProjectSprintRequest("Sprint 1", null, null, null));

		assertThat(response.externalSprintId()).isEqualTo(local.getExternalSprintId());
		verify(jiraWrite).createSprint("token", "cloud", "68", "Sprint 1", null, null, null);
	}

	@Test
	void delete_leader_callsJiraThenSoftDeletesProjection() {
		stubLeader();
		JiraIntegration integration = activeJira();
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");

		service.delete(userId, projectId, local.getId());

		verify(jiraWrite).deleteSprint("token", "cloud", "31");
	}

	@Test
	void member_cannotCreateSprint() {
		stubMember();

		assertThatThrownBy(() -> service.create(userId, projectId, new CreateProjectSprintRequest("Sprint X", null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void member_cannotPatchSprint() {
		stubMember();
		UUID sprintId = UUID.randomUUID();

		assertThatThrownBy(() -> service.patch(
						userId, projectId, sprintId, new PatchProjectSprintRequest("New name", null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void member_cannotDeleteSprint() {
		stubMember();
		UUID sprintId = UUID.randomUUID();

		assertThatThrownBy(() -> service.delete(userId, projectId, sprintId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verifyZeroProviderInteraction();
	}

	@Test
	void unrelatedStudent_cannotCreateSprint() {
		stubUnrelatedStudent();

		assertThatThrownBy(() -> service.create(userId, projectId, new CreateProjectSprintRequest("Sprint X", null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verifyZeroProviderInteraction();
	}

	@Test
	void assignedLecturer_cannotCreateSprint() {
		stubLecturer();

		assertThatThrownBy(() -> service.create(userId, projectId, new CreateProjectSprintRequest("Sprint X", null, null, null)))
				.isInstanceOf(IntegrationException.class);
		verifyZeroProviderInteraction();
	}

	@Test
	void admin_cannotCreateSprint() {
		stubAdmin();

		assertThatThrownBy(() -> service.create(userId, projectId, new CreateProjectSprintRequest("Sprint X", null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verifyZeroProviderInteraction();
	}

	@Test
	void admin_cannotDeleteSprint() {
		stubAdmin();

		assertThatThrownBy(() -> service.delete(userId, projectId, UUID.randomUUID()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
		verifyZeroProviderInteraction();
	}

	@Test
	void create_withExplicitJiraIntegrationId_usesThatSource() {
		stubLeader();
		JiraIntegration integration = activeJira();
		integration.setCloudId("cloud-b");
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail created = new SprintDetail(31L, "Sprint 1", "future", null, null, null, null, null);
		when(jiraWrite.createSprint("token", "cloud-b", "68", "Sprint 1", null, null, null)).thenReturn(created);
		when(jiraWrite.getSprint("token", "cloud-b", "31")).thenReturn(created);
		when(projection.upsertSprint(eq(integration), eq("31"), eq("Sprint 1"), eq("future"), any(), any(), any(), any()))
				.thenReturn(local);

		service.create(
				userId,
				projectId,
				new CreateProjectSprintRequest("Sprint 1", null, null, null, integration.getId()));

		verify(jiraWrite).createSprint("token", "cloud-b", "68", "Sprint 1", null, null, null);
		verify(jiraIntegrations, never()).findAllByProject_Id(any());
	}

	@Test
	void create_omittedSource_withTwoIntegrations_requiresExplicitId() {
		stubLeader();
		when(jiraIntegrations.findAllByProject_Id(projectId)).thenReturn(List.of(activeJira(), activeJira()));

		assertThatThrownBy(() -> service.create(userId, projectId, new CreateProjectSprintRequest("Sprint X", null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_SOURCE_REQUIRED);
		verifyZeroProviderInteraction();
	}

	@Test
	void patch_usesSprintProvenanceCloudId() {
		stubLeader();
		JiraIntegration integration = activeJira();
		integration.setCloudId("sprint-cloud");
		Sprint local = sprintRow(integration);
		when(jiraIntegrations.findByIdAndProject_Id(integration.getId(), projectId)).thenReturn(Optional.of(integration));
		when(sprints.findActiveByIdAndProject_Id(local.getId(), projectId)).thenReturn(Optional.of(local));
		when(tokens.accessToken(integration)).thenReturn("token");
		SprintDetail updated = new SprintDetail(31L, "Sprint Renamed", "active", null, null, null, null, null);
		when(jiraWrite.updateSprint("token", "sprint-cloud", "31", "Sprint Renamed", null, null, null, null))
				.thenReturn(updated);
		when(jiraWrite.getSprint("token", "sprint-cloud", "31")).thenReturn(updated);
		when(projection.upsertSprint(eq(integration), eq("31"), eq("Sprint Renamed"), eq("active"), any(), any(), any(), any()))
				.thenReturn(local);

		service.patch(userId, projectId, local.getId(), new PatchProjectSprintRequest("Sprint Renamed", null, null, null, null));

		verify(jiraWrite).updateSprint("token", "sprint-cloud", "31", "Sprint Renamed", null, null, null, null);
		verify(jiraIntegrations, never()).findAllByProject_Id(any());
		verify(jiraIntegrations, never()).findByProject_Id(any());
	}

	@Test
	void syncAndList_multipleActiveSources_requiresExplicitId() {
		stubReader();
		JiraIntegration first = activeJira();
		JiraIntegration second = activeJira();
		when(jiraIntegrations.findAllByProject_Id(projectId)).thenReturn(List.of(first, second));

		assertThatThrownBy(() -> service.syncAndList(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_SOURCE_REQUIRED);
		verify(jiraWrite, never()).listBoardSprints(any(), any(), any());
	}

	@Test
	void syncAndList_zeroActive_returnsLocalOnly() {
		stubReader();
		JiraIntegration revoked = activeJira();
		revoked.setConnectionStatus(IntegrationStatus.REVOKED);
		when(jiraIntegrations.findAllByProject_Id(projectId)).thenReturn(List.of(revoked));
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of());

		assertThat(service.syncAndList(userId, projectId)).isEmpty();
		verify(jiraWrite, never()).listBoardSprints(any(), any(), any());
	}

	private void verifyZeroProviderInteraction() {
		verify(tokens, never()).accessToken(any());
		verify(jiraWrite, never()).createSprint(any(), any(), any(), any(), any(), any(), any());
		verify(jiraWrite, never()).updateSprint(any(), any(), any(), any(), any(), any(), any(), any());
		verify(jiraWrite, never()).deleteSprint(any(), any(), any());
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

	private void stubLeader() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	private void stubReader() {
		UserAccount student = new UserAccount();
		student.setId(userId);
		student.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
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

	private Sprint sprintRow() {
		return sprintRow(activeJira());
	}

	private Sprint sprintRow(JiraIntegration integration) {
		Project project = new Project();
		project.setId(projectId);
		integration.setProject(project);
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setExternalSprintId("31");
		sprint.setName("Sprint 1");
		sprint.setState("active");
		sprint.setJiraIntegration(integration);
		return sprint;
	}
}

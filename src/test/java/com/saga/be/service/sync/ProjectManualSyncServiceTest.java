package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.ProjectSyncEnqueueResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyncJobLogRepository;
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
class ProjectManualSyncServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private IntegrationInitialSyncLauncher launcher;
	@Mock
	private JiraIntegrationCredentialService credentials;
	@Mock
	private SyncJobClaimService claims;

	private ProjectManualSyncService service;
	private UUID projectId;
	private UUID userId;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new ProjectManualSyncService(
				authorization, jiraIntegrations, repos, syncJobs, launcher, credentials, claims);
		projectId = UUID.randomUUID();
		userId = UUID.randomUUID();
	}

	@Test
	void leader_enqueuesBothWithoutProviderHttp() {
		stubLeader();
		JiraIntegration jira = activeJira();
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jira));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		when(claims.tryReserveEnqueue("JIRA", projectId)).thenReturn(true);
		GitRepo repo = new GitRepo();
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of(repo));
		when(claims.tryReserveEnqueue("GITHUB", projectId)).thenReturn(true);

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		assertThat(response.github()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		verify(launcher).enqueueJiraInitialSync(projectId);
		verify(launcher).enqueueGithubInitialSync(projectId);
	}

	@Test
	void admin_forbidden() {
		UserAccount admin = account(AccountRole.ADMIN);
		when(users.findById(userId)).thenReturn(Optional.of(admin));
		assertThatThrownBy(() -> service.enqueue(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
	}

	@Test
	void member_forbidden() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		assertThatThrownBy(() -> service.enqueue(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
	}

	@Test
	void lecturer_forbidden() {
		UserAccount lecturer = account(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));
		assertThatThrownBy(() -> service.enqueue(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
	}

	@Test
	void refreshOnlyCredential_stillQueuesJira() {
		stubLeader();
		JiraIntegration jira = activeJira();
		jira.setEncryptedAccessToken(null);
		jira.setEncryptedRefreshToken("refresh-envelope");
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jira));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		when(claims.tryReserveEnqueue("JIRA", projectId)).thenReturn(true);
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		verify(launcher).enqueueJiraInitialSync(projectId);
	}

	@Test
	void jiraOnlyActive_githubSkipped() {
		stubLeader();
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(activeJira()));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		when(claims.tryReserveEnqueue("JIRA", projectId)).thenReturn(true);
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		assertThat(response.github()).isEqualTo(ProjectSyncEnqueueResponse.SKIPPED_NOT_CONFIGURED);
		verify(launcher).enqueueJiraInitialSync(projectId);
		verify(launcher, never()).enqueueGithubInitialSync(any());
	}

	@Test
	void alreadyRunning_skipsDuplicateEnqueue() {
		stubLeader();
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(activeJira()));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		when(claims.tryReserveEnqueue("JIRA", projectId)).thenReturn(false);
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.SKIPPED_ALREADY_RUNNING);
		verify(launcher, never()).enqueueJiraInitialSync(any());
	}

	@Test
	void leader_skipsJiraWhenRevoked() {
		stubLeader();
		JiraIntegration jira = activeJira();
		jira.setConnectionStatus(IntegrationStatus.REVOKED);
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jira));
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.SKIPPED_NOT_ACTIVE);
		verify(launcher, never()).enqueueJiraInitialSync(any());
	}

	@Test
	void leader_enqueuesJiraAfterReconnectActive() {
		stubLeader();
		JiraIntegration jira = activeJira();
		when(jiraIntegrations.findFetchedByProject_Id(projectId)).thenReturn(Optional.of(jira));
		when(credentials.hasRefreshOrAccessCredential(projectId)).thenReturn(true);
		when(claims.tryReserveEnqueue("JIRA", projectId)).thenReturn(true);
		when(repos.findByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)).thenReturn(List.of());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());

		ProjectSyncEnqueueResponse response = service.enqueue(userId, projectId);

		assertThat(response.jira()).isEqualTo(ProjectSyncEnqueueResponse.QUEUED);
		verify(launcher).enqueueJiraInitialSync(projectId);
	}

	private void stubLeader() {
		UserAccount student = account(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(student));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	private JiraIntegration activeJira() {
		Project project = new Project();
		project.setId(projectId);
		UserAccount connected = new UserAccount();
		connected.setId(UUID.randomUUID());
		JiraIntegration jira = new JiraIntegration();
		jira.setId(UUID.randomUUID());
		jira.setProject(project);
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setEncryptedAccessToken("access-envelope");
		jira.setConnectedBy(connected);
		return jira;
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}
}

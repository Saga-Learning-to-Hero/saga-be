package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.integration.failover.JiraFailoverPreviewRequest;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraFailoverValidationServiceTest {

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

	private JiraFailoverValidationService service;
	private UUID userId;
	private UUID projectId;
	private UUID sourceId;
	private UUID targetId;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new JiraFailoverValidationService(authorization, jiraIntegrations, sprints);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		sourceId = UUID.randomUUID();
		targetId = UUID.randomUUID();
	}

	@Test
	void memberIsRejected() {
		stubStudent(RoleInTeam.MEMBER);
		assertThatThrownBy(() -> service.validatePreview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verify(jiraIntegrations, never()).findByIdAndProject_Id(any(), any());
	}

	@Test
	void sameSourceAndTargetRejected() {
		stubStudentLeader();
		assertThatThrownBy(() -> service.validatePreview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(sourceId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FAILOVER_SOURCE_TARGET_SAME);
	}

	@Test
	void targetNotActiveRejected() {
		stubStudentLeader();
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId))
				.thenReturn(Optional.of(integration(sourceId, IntegrationStatus.ACTIVE)));
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId))
				.thenReturn(Optional.of(integration(targetId, IntegrationStatus.REVOKED)));

		assertThatThrownBy(() -> service.validatePreview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FAILOVER_TARGET_NOT_ACTIVE);
	}

	@Test
	void targetIdentityIncompleteRejected() {
		stubStudentLeader();
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId))
				.thenReturn(Optional.of(integration(sourceId, IntegrationStatus.ACTIVE)));
		JiraIntegration target = integration(targetId, IntegrationStatus.ACTIVE);
		target.setCloudId(null);
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId)).thenReturn(Optional.of(target));

		assertThatThrownBy(() -> service.validatePreview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FAILOVER_TARGET_IDENTITY_INCOMPLETE);
	}

	@Test
	void sprintOnWrongIntegrationRejected() {
		stubStudentLeader();
		JiraIntegration source = integration(sourceId, IntegrationStatus.ACTIVE);
		JiraIntegration target = integration(targetId, IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId)).thenReturn(Optional.of(source));
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId)).thenReturn(Optional.of(target));

		UUID sprintId = UUID.randomUUID();
		Sprint sprint = new Sprint();
		sprint.setId(sprintId);
		JiraIntegration other = integration(UUID.randomUUID(), IntegrationStatus.ACTIVE);
		sprint.setJiraIntegration(other);
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.of(sprint));

		assertThatThrownBy(() -> service.validatePreview(
						userId,
						projectId,
						sourceId,
						new JiraFailoverPreviewRequest(targetId, sprintId, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FAILOVER_SPRINT_INVALID);
	}

	@Test
	void validSourceTargetAndSprintAccepted() {
		stubStudentLeader();
		JiraIntegration source = integration(sourceId, IntegrationStatus.ACTIVE);
		JiraIntegration target = integration(targetId, IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId)).thenReturn(Optional.of(source));
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId)).thenReturn(Optional.of(target));

		UUID sprintId = UUID.randomUUID();
		Sprint sprint = new Sprint();
		sprint.setId(sprintId);
		sprint.setJiraIntegration(target);
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.of(sprint));

		var validated = service.validatePreview(
				userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, sprintId, "10001", null, null));
		assertThat(validated.source().getId()).isEqualTo(sourceId);
		assertThat(validated.target().getId()).isEqualTo(targetId);
		assertThat(validated.targetSprint().getId()).isEqualTo(sprintId);
	}

	@Test
	void missingSourceUsesJiraSourceNotFound() {
		stubStudentLeader();
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.validatePreview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND);
	}

	private void stubStudentLeader() {
		stubStudent(RoleInTeam.LEADER);
	}

	private void stubStudent(RoleInTeam role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(account));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(role));
	}

	private JiraIntegration integration(UUID id, IntegrationStatus status) {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(id);
		integration.setConnectionStatus(status);
		integration.setCloudId("cloud-" + id);
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		Project project = new Project();
		project.setId(projectId);
		integration.setProject(project);
		return integration;
	}
}

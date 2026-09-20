package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverRun;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.JiraTaskFailoverRunRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraFailoverExecutionServiceTest {

	@Mock private UserAccountRepository users;
	@Mock private TeamMemberRepository members;
	@Mock private ProjectRepository projects;
	@Mock private JiraIntegrationRepository integrations;
	@Mock private SprintRepository sprints;
	@Mock private TaskRepository tasks;
	@Mock private JiraTaskFailoverRunRepository runs;
	@Mock private JiraFailoverPersistenceService state;
	@Mock private JiraFailoverWorker worker;
	@Mock private ProjectIntegrationService integrationCommands;
	@Mock private JiraTeamTokenService tokens;
	@Mock private JiraIssueWriteClient jira;

	private JiraFailoverExecutionService service;
	private UUID userId;
	private UUID projectId;
	private UUID sourceId;
	private UUID runId;

	@BeforeEach
	void setUp() {
		service = new JiraFailoverExecutionService(
				new ProjectDataAuthorization(users, members, projects), users, projects, integrations, sprints, tasks,
				runs, state, worker, integrationCommands, tokens, jira);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		sourceId = UUID.randomUUID();
		runId = UUID.randomUUID();
		UserAccount leader = new UserAccount();
		leader.setId(userId);
		leader.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(leader));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
	}

	@Test
	void retryRefusesRemoteCreationWhenSourceIsNoLongerRevoked() {
		JiraIntegration source = new JiraIntegration();
		source.setId(sourceId);
		source.setConnectionStatus(IntegrationStatus.ACTIVE);
		JiraTaskFailoverRun run = new JiraTaskFailoverRun();
		run.setId(runId);
		run.setSourceJiraIntegration(source);
		run.setProject(new Project());
		when(runs.findFetchedByIdAndProject_Id(runId, projectId)).thenReturn(Optional.of(run));

		assertThatThrownBy(() -> service.retry(userId, projectId, sourceId, runId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FAILOVER_INVALID_REQUEST);

		verify(worker, never()).processRun(runId);
	}
}

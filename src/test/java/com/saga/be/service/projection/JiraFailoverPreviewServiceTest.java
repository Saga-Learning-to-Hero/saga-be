package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.integration.failover.JiraFailoverPreviewRequest;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.ItemClassification;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.JiraTaskFailoverRun;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.AssignableUserOption;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.integration.jira.JiraIssueWriteClient.PriorityOption;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraFailoverSupersedeQueries;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraFailoverPreviewServiceTest {

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
	private TaskRepository tasks;
	@Mock
	private JiraTaskFailoverItemRepository failoverItems;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private JiraTeamTokenService tokens;
	@Mock
	private JiraIssueWriteClient jiraWrite;

	private JiraFailoverPreviewService service;
	private UUID userId;
	private UUID projectId;
	private UUID sourceId;
	private UUID targetId;
	private JiraIntegration source;
	private JiraIntegration target;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		JiraFailoverValidationService validation =
				new JiraFailoverValidationService(authorization, jiraIntegrations, sprints);
		service = new JiraFailoverPreviewService(
				validation, tasks, failoverItems, identities, tokens, jiraWrite);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		sourceId = UUID.randomUUID();
		targetId = UUID.randomUUID();
		source = integration(sourceId);
		target = integration(targetId);
	}

	@Test
	void memberCannotPreview() {
		stubStudent(RoleInTeam.MEMBER);
		assertThatThrownBy(() -> service.preview(
						userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void previewClassifiesTasksAndNeverCreatesIssues() {
		stubStudentLeader();
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId)).thenReturn(Optional.of(source));
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId)).thenReturn(Optional.of(target));

		Task eligible = task("SAGA-1", TaskStatus.TODO, TaskType.TASK, "Task", Priority.HIGH);
		Task done = task("SAGA-2", TaskStatus.DONE, TaskType.TASK, "Task", Priority.MEDIUM);
		Task subtask = task("SAGA-3", TaskStatus.IN_PROGRESS, TaskType.SUBTASK, "Sub-task", Priority.LOW);
		Task supersededSource = task("SAGA-4", TaskStatus.TODO, TaskType.STORY, "Story", Priority.HIGH);
		Task supersededTarget = task("SAGA-B-9", TaskStatus.TODO, TaskType.STORY, "Story", Priority.HIGH);
		supersededTarget.setId(UUID.randomUUID());

		UserAccount assigneeUser = new UserAccount();
		assigneeUser.setId(UUID.randomUUID());
		StudentProfile assignee = new StudentProfile();
		assignee.setId(UUID.randomUUID());
		assignee.setUserAccount(assigneeUser);
		eligible.setAssigneeStudent(assignee);

		when(tasks.findActiveFetchedByProjectAndJiraIntegration(projectId, sourceId))
				.thenReturn(List.of(eligible, done, subtask, supersededSource));

		JiraTaskFailoverRun run = new JiraTaskFailoverRun();
		run.setId(UUID.randomUUID());
		JiraTaskFailoverItem succeeded = new JiraTaskFailoverItem();
		succeeded.setSourceTask(supersededSource);
		succeeded.setTargetTask(supersededTarget);
		succeeded.setStatus(JiraFailoverItemStatus.SUCCEEDED);
		succeeded.setRun(run);
		when(failoverItems.findFetchedBySourceTask_IdInAndStatusIn(
						ArgumentMatchers.<Collection<UUID>>any(),
						eq(JiraFailoverSupersedeQueries.CLAIM_HOLDING_STATUSES)))
				.thenReturn(List.of(succeeded));

		IdentityMap map = new IdentityMap();
		map.setUserAccount(assigneeUser);
		map.setExternalAccountId("acc-1");
		map.setMappingStatus(IdentityMappingStatus.ACTIVE);
		map.setProvider(IntegrationProvider.JIRA);
		when(identities.findFetchedByUserAccount_IdInAndProviderAndMappingStatusIn(
						any(), eq(IntegrationProvider.JIRA), any()))
				.thenReturn(List.of(map));

		when(tokens.accessToken(target)).thenReturn("token-b");
		when(jiraWrite.listProjectIssueTypes("token-b", target.getCloudId(), target.getJiraProjectId()))
				.thenReturn(List.of(new IssueTypeOption("1", "Task", null), new IssueTypeOption("2", "Story", null)));
		when(jiraWrite.listPriorities("token-b", target.getCloudId()))
				.thenReturn(List.of(new PriorityOption("p1", "High"), new PriorityOption("p2", "Medium")));
		when(jiraWrite.listAssignableUsers(eq("token-b"), eq(target.getCloudId()), anyString(), eq(50)))
				.thenReturn(List.of(new AssignableUserOption("acc-1", "Ada")));

		JiraFailoverPreviewResponse response = service.preview(
				userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, null, null));

		assertThat(response.dependencyRemap()).isEqualTo(JiraFailoverPreviewResponse.DEPENDENCY_REMAP_DEFERRED);
		assertThat(response.counts().totalActive()).isEqualTo(4);
		assertThat(response.counts().eligible()).isEqualTo(1);
		assertThat(response.counts().blocked()).isEqualTo(1);
		assertThat(response.counts().alreadySuperseded()).isEqualTo(1);
		assertThat(response.counts().skippedDone()).isEqualTo(1);
		assertThat(response.totalEligible()).isEqualTo(1);
		assertThat(response.page()).isEqualTo(0);
		assertThat(response.size()).isEqualTo(JiraFailoverPreviewResponse.ITEM_PAGE_DEFAULT);
		assertThat(response.totalItems()).isEqualTo(4);
		assertThat(response.totalPages()).isEqualTo(1);
		assertThat(response.hasNext()).isFalse();

		assertThat(response.items())
				.anySatisfy(item -> {
					assertThat(item.classification()).isEqualTo(ItemClassification.ELIGIBLE);
					assertThat(item.readiness())
							.isEqualTo(JiraFailoverPreviewResponse.Readiness.WARNING);
					assertThat(item.plannedStatusHandling())
							.isEqualTo(JiraFailoverPreviewResponse.PlannedStatusHandling.PROVIDER_DEFAULT);
					assertThat(item.plannedSprint())
							.isEqualTo(JiraFailoverPreviewResponse.PlannedSprintPlacement.BACKLOG);
					assertThat(item.mapping().resolvedIssueTypeName()).isEqualTo("Task");
					assertThat(item.mapping().resolvedPriorityName()).isEqualTo("High");
					assertThat(item.mapping().resolvedAssigneeAccountId()).isEqualTo("acc-1");
					assertThat(item.warnings()).contains(JiraFailoverPreviewService.WARNING_CONTRIBUTION_REQUIRES_SPRINT);
				})
				.anySatisfy(item -> {
					assertThat(item.classification()).isEqualTo(ItemClassification.BLOCKED);
					assertThat(item.readiness())
							.isEqualTo(JiraFailoverPreviewResponse.Readiness.BLOCKED);
					assertThat(item.blockers())
							.contains(JiraFailoverPreviewService.BLOCKER_PROVIDER_SUBTASK_UNSUPPORTED);
				})
				.anySatisfy(item -> assertThat(item.classification()).isEqualTo(ItemClassification.SKIP_DONE))
				.anySatisfy(item -> {
					assertThat(item.classification()).isEqualTo(ItemClassification.ALREADY_SUPERSEDED);
					assertThat(item.supersededTargetTaskId()).isEqualTo(supersededTarget.getId());
					assertThat(item.supersededRunId()).isEqualTo(run.getId());
				});

		verify(tokens, times(1)).accessToken(target);
		verify(tokens, never()).accessToken(source);
		verify(jiraWrite, times(1))
				.listProjectIssueTypes("token-b", target.getCloudId(), target.getJiraProjectId());
		verify(jiraWrite, times(1)).listPriorities("token-b", target.getCloudId());
		verify(jiraWrite, times(1))
				.listAssignableUsers(eq("token-b"), eq(target.getCloudId()), anyString(), eq(50));
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void pagesItemsBeyondRequestedSize() {
		stubStudentLeader();
		when(jiraIntegrations.findByIdAndProject_Id(sourceId, projectId)).thenReturn(Optional.of(source));
		when(jiraIntegrations.findByIdAndProject_Id(targetId, projectId)).thenReturn(Optional.of(target));

		List<Task> many = new ArrayList<>();
		for (int i = 0; i < 205; i++) {
			many.add(task("SAGA-" + i, TaskStatus.TODO, TaskType.TASK, "Task", Priority.MEDIUM));
		}
		when(tasks.findActiveFetchedByProjectAndJiraIntegration(projectId, sourceId)).thenReturn(many);
		when(failoverItems.findFetchedBySourceTask_IdInAndStatusIn(
						ArgumentMatchers.<Collection<UUID>>any(),
						eq(JiraFailoverSupersedeQueries.CLAIM_HOLDING_STATUSES)))
				.thenReturn(List.of());
		when(tokens.accessToken(target)).thenReturn("token-b");
		when(jiraWrite.listProjectIssueTypes(anyString(), anyString(), anyString()))
				.thenReturn(List.of(new IssueTypeOption("1", "Task", null)));
		when(jiraWrite.listPriorities(anyString(), anyString()))
				.thenReturn(List.of(new PriorityOption("p1", "Medium")));
		when(jiraWrite.listAssignableUsers(anyString(), anyString(), anyString(), anyInt()))
				.thenReturn(List.of());

		JiraFailoverPreviewResponse response = service.preview(
				userId, projectId, sourceId, new JiraFailoverPreviewRequest(targetId, null, null, 0, 50));

		assertThat(response.items()).hasSize(50);
		assertThat(response.page()).isEqualTo(0);
		assertThat(response.size()).isEqualTo(50);
		assertThat(response.totalItems()).isEqualTo(205);
		assertThat(response.totalPages()).isEqualTo(5);
		assertThat(response.hasNext()).isTrue();
		assertThat(response.totalEligible()).isEqualTo(205);
		assertThat(response.counts().eligible()).isEqualTo(205);
		verify(jiraWrite, times(1)).listProjectIssueTypes(anyString(), anyString(), anyString());
		verify(jiraWrite, never())
				.createIssue(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
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

	private JiraIntegration integration(UUID id) {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(id);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setCloudId("cloud");
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		Project project = new Project();
		project.setId(projectId);
		integration.setProject(project);
		return integration;
	}

	private Task task(String key, TaskStatus status, TaskType type, String issueTypeName, Priority priority) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		task.setTitle(key);
		task.setStatus(status);
		task.setTaskType(type);
		task.setIssueTypeName(issueTypeName);
		task.setPriority(priority);
		task.setJiraIntegration(source);
		return task;
	}
}

package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.integration.failover.JiraFailoverExecuteRequest;
import com.saga.be.dto.integration.failover.JiraFailoverExecuteResponse;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.JiraFailoverRunStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.service.projection.JiraAutoFailoverService.Outcome;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JiraAutoFailoverServiceTest {

	private static final LocalDateTime DEAD_SINCE = LocalDateTime.of(2026, 10, 4, 8, 0);

	private JiraIntegrationRepository integrations;
	private TaskRepository tasks;
	private JiraTaskFailoverItemRepository failoverItems;
	private TeamByProjectRepository teams;
	private TeamMemberRepository members;
	private JiraTeamTokenService tokens;
	private JiraIssueWriteClient jira;
	private JiraFailoverExecutionService execution;
	private ProjectIntegrationService integrationCommands;
	private NotificationService notifications;
	private JiraAutoFailoverService service;
	private Project project;
	private JiraIntegration source;
	private JiraIntegration target;
	private UserAccount leader;
	private UserAccount member;
	private List<Task> sourceTasks;

	@BeforeEach
	void setUp() {
		integrations = mock(JiraIntegrationRepository.class);
		tasks = mock(TaskRepository.class);
		failoverItems = mock(JiraTaskFailoverItemRepository.class);
		teams = mock(TeamByProjectRepository.class);
		members = mock(TeamMemberRepository.class);
		tokens = mock(JiraTeamTokenService.class);
		jira = mock(JiraIssueWriteClient.class);
		execution = mock(JiraFailoverExecutionService.class);
		integrationCommands = mock(ProjectIntegrationService.class);
		notifications = mock(NotificationService.class);
		service = new JiraAutoFailoverService(integrations, tasks, failoverItems, teams, members, tokens, jira, execution,
				integrationCommands, notifications);
		project = new Project();
		project.setId(UUID.randomUUID());
		source = integration("SAGA", "old-site");
		target = integration("SG", "new-site");
		Team team = new Team();
		team.setId(UUID.randomUUID());
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		leader = account();
		member = account();
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(teamMember(leader, RoleInTeam.LEADER), teamMember(member, RoleInTeam.MEMBER)));
		sourceTasks = new ArrayList<>();
		when(tasks.findActiveFetchedByProjectAndJiraIntegration(project.getId(), source.getId())).thenReturn(sourceTasks);
		when(tokens.accessToken(target)).thenReturn("new-token");
		when(jira.listProjectIssueTypes("new-token", "cloud-SG", "jp-SG")).thenReturn(List.of(
				new IssueTypeOption("10001", "Epic", null, false, 1),
				new IssueTypeOption("10002", "Story", null, false, 0),
				new IssueTypeOption("10003", "Task", null, false, 0),
				new IssueTypeOption("10004", "Sub-task", null, true, -1)));
		when(execution.execute(any(), any(), any(), any())).thenAnswer(inv -> new JiraFailoverExecuteResponse(
				UUID.randomUUID(), JiraFailoverRunStatus.PENDING, ((JiraFailoverExecuteRequest) inv.getArgument(3)).sourceTaskIds().size()));
	}

	@Test
	void unfinishedTasksGoToTheTargetBacklogAsTheLeaderAndTheTeamIsTold() {
		Task later = task("SAGA-2", TaskStatus.IN_PROGRESS, "STANDARD", LocalDateTime.of(2026, 10, 9, 0, 0));
		Task sooner = task("SAGA-1", TaskStatus.TODO, "STANDARD", LocalDateTime.of(2026, 10, 6, 0, 0));
		Task done = task("SAGA-3", TaskStatus.DONE, "STANDARD", null);
		Task subtask = task("SAGA-4", TaskStatus.TODO, "SUBTASK", null);
		Task epic = task("SAGA-5", TaskStatus.TODO, "EPIC", null);
		Task alreadyMoving = task("SAGA-6", TaskStatus.TODO, "STANDARD", null);
		Task alreadyMoved = task("SAGA-7", TaskStatus.TODO, "STANDARD", null);
		sourceTasks.addAll(List.of(later, sooner, done, subtask, epic, alreadyMoving, alreadyMoved));
		when(failoverItems.existsOutboundClaim(alreadyMoving.getId())).thenReturn(true);
		when(failoverItems.existsSuccessfullySuperseded(alreadyMoved.getId())).thenReturn(true);

		JiraAutoFailoverService.Result result = service.failover(source.getId(), target.getId(), DEAD_SINCE);

		assertThat(result.outcome()).isEqualTo(Outcome.MOVED);
		assertThat(result.movedTasks()).isEqualTo(2);
		ArgumentCaptor<JiraFailoverExecuteRequest> request = ArgumentCaptor.forClass(JiraFailoverExecuteRequest.class);
		verify(execution).execute(eq(leader.getId()), eq(project.getId()), eq(source.getId()), request.capture());
		assertThat(request.getValue().targetIntegrationId()).isEqualTo(target.getId());
		assertThat(request.getValue().targetSprintId()).isNull(); // backlog
		assertThat(request.getValue().revokeSource()).isTrue();
		assertThat(request.getValue().defaultIssueTypeId()).isEqualTo("10003");
		assertThat(request.getValue().sourceTaskIds()).containsExactly(sooner.getId(), later.getId());
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications, times(2)).createNotification(any(), eq(NotificationType.INTEGRATION),
				eq("Đã tự chuyển task sang site Jira SG (new-site)"), message.capture(), any(), anyString());
		assertThat(message.getValue()).contains("08:00 04/10/2026").contains("2 task").contains("backlog").contains("giao người");
		verify(notifications).createNotification(eq(member.getId()), any(), any(), any(), any(), anyString());
	}

	@Test
	void withNothingToMoveTheDeadSourceIsOnlyDisconnected() {
		sourceTasks.add(task("SAGA-3", TaskStatus.DONE, "STANDARD", null));

		JiraAutoFailoverService.Result result = service.failover(source.getId(), target.getId(), DEAD_SINCE);

		assertThat(result.outcome()).isEqualTo(Outcome.REVOKED_ONLY);
		verify(integrationCommands).disconnectJiraSource(leader.getId(), project.getId(), source.getId());
		verify(execution, never()).execute(any(), any(), any(), any());
	}

	@Test
	void noLeaderOrAnInactiveSourceSkipsWithoutTouchingJira() {
		when(members.findFetchedByTeam_Id(any())).thenReturn(List.of(teamMember(member, RoleInTeam.MEMBER)));
		assertThat(service.failover(source.getId(), target.getId(), DEAD_SINCE).reason()).isEqualTo("NO_TEAM_LEADER");

		when(members.findFetchedByTeam_Id(any())).thenReturn(List.of(teamMember(leader, RoleInTeam.LEADER)));
		source.setConnectionStatus(IntegrationStatus.REVOKED);
		assertThat(service.failover(source.getId(), target.getId(), DEAD_SINCE).outcome()).isEqualTo(Outcome.SKIPPED);

		verify(execution, never()).execute(any(), any(), any(), any());
		verify(integrationCommands, never()).disconnectJiraSource(any(), any(), any());
	}

	@Test
	void aFailureTellsTheLeaderToMoveTasksByHand() {
		sourceTasks.add(task("SAGA-1", TaskStatus.TODO, "STANDARD", null));
		org.mockito.Mockito.doThrow(new IllegalStateException("claim conflict")).when(execution).execute(any(), any(), any(), any());

		JiraAutoFailoverService.Result result = service.failover(source.getId(), target.getId(), DEAD_SINCE);

		assertThat(result.outcome()).isEqualTo(Outcome.FAILED);
		verify(notifications).createNotification(eq(leader.getId()), eq(NotificationType.INTEGRATION),
				eq("Không tự chuyển được task khỏi site Jira SAGA (old-site)"), anyString(), any(), anyString());
		verify(notifications, never()).createNotification(eq(member.getId()), any(), any(), any(), any(), anyString());
	}

	@Test
	void theTargetsTaskTypeIsPreferredThenAnyStandardType() {
		assertThat(JiraAutoFailoverService.standardIssueType(List.of(
				new IssueTypeOption("1", "Epic", null, false, 1),
				new IssueTypeOption("2", "Story", null, false, 0),
				new IssueTypeOption("3", "Task", null, false, 0)))).contains("3");
		assertThat(JiraAutoFailoverService.standardIssueType(List.of(
				new IssueTypeOption("1", "Epic", null, false, 1),
				new IssueTypeOption("2", "Công việc", null, false, 0),
				new IssueTypeOption("4", "Subtask", null, true, -1)))).contains("2");
		assertThat(JiraAutoFailoverService.standardIssueType(List.of(
				new IssueTypeOption("1", "Epic", null, false, 1),
				new IssueTypeOption("4", "Subtask", null, true, -1)))).isEmpty();
	}

	@Test
	void aTargetWithoutAStandardTypeFailsBeforeAnyChange() {
		sourceTasks.add(task("SAGA-1", TaskStatus.TODO, "STANDARD", null));
		when(jira.listProjectIssueTypes(any(), any(), any())).thenReturn(List.of(new IssueTypeOption("1", "Epic", null, false, 1)));

		assertThat(service.failover(source.getId(), target.getId(), DEAD_SINCE).reason()).isEqualTo("TARGET_ISSUE_TYPE_MISSING");
		verify(execution, never()).execute(any(), any(), any(), any());
	}

	// ------------------------------------------------------------------ fixtures

	private JiraIntegration integration(String key, String site) {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setProjectKey(key);
		integration.setSiteName(site);
		integration.setCloudId("cloud-" + key);
		integration.setJiraProjectId("jp-" + key);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(integrations.findById(integration.getId())).thenReturn(Optional.of(integration));
		return integration;
	}

	private Task task(String key, TaskStatus status, String level, LocalDateTime due) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		task.setStatus(status);
		task.setIssueTypeLevel(level);
		task.setDueDate(due);
		task.setJiraIntegration(source);
		return task;
	}

	private static UserAccount account() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		return account;
	}

	private static TeamMember teamMember(UserAccount account, RoleInTeam role) {
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		TeamMember member = new TeamMember();
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}
}

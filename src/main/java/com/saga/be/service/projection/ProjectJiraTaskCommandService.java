package com.saga.be.service.projection;

import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.CreatedIssue;
import com.saga.be.integration.jira.JiraIssueWriteClient.EstimationInfo;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class ProjectJiraTaskCommandService {

	private final ProjectDataAuthorization authorization;
	private final ProjectRepository projects;
	private final JiraIntegrationRepository jiraIntegrations;
	private final TaskRepository tasks;
	private final TaskWorkSessionRepository workSessions;
	private final ContributionConfirmationRepository confirmations;
	private final TaskGitCommitLinkRepository links;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraWrite;
	private final JiraTaskProjectionService projection;
	private final ProjectRealtimePublisher realtime;
	private final TransactionTemplate writes;

	public ProjectJiraTaskCommandService(
			ProjectDataAuthorization authorization,
			ProjectRepository projects,
			JiraIntegrationRepository jiraIntegrations,
			TaskRepository tasks,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations,
			TaskGitCommitLinkRepository links,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraWrite,
			JiraTaskProjectionService projection,
			ProjectRealtimePublisher realtime,
			PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.projects = projects;
		this.jiraIntegrations = jiraIntegrations;
		this.tasks = tasks;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
		this.links = links;
		this.tokens = tokens;
		this.jiraWrite = jiraWrite;
		this.projection = projection;
		this.realtime = realtime;
		this.writes = new TransactionTemplate(transactionManager);
	}

	public ProjectTaskOptionsResponse options(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		String access = tokens.accessToken(integration);
		EstimationInfo estimation = jiraWrite.boardEstimationCapability(
				access, integration.getCloudId(), integration.getJiraBoardId());
		List<ProjectTaskOptionsResponse.IssueTypeOption> issueTypes = jiraWrite
				.listProjectIssueTypes(access, integration.getCloudId(), integration.getJiraProjectId())
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.IssueTypeOption(item.id(), item.name(), item.description()))
				.toList();
		List<ProjectTaskOptionsResponse.PriorityOption> priorities = jiraWrite
				.listPriorities(access, integration.getCloudId())
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.PriorityOption(item.id(), item.name()))
				.toList();
		String projectRef = integration.getProjectKey() != null ? integration.getProjectKey() : integration.getJiraProjectId();
		List<ProjectTaskOptionsResponse.AssignableUserOption> users = jiraWrite
				.listAssignableUsers(access, integration.getCloudId(), projectRef, 50)
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.AssignableUserOption(item.accountId(), item.displayName()))
				.toList();
		List<ProjectTaskOptionsResponse.SprintOption> sprints = List.of();
		if (integration.getJiraBoardId() != null && !integration.getJiraBoardId().isBlank()) {
			sprints = jiraWrite.listBoardSprints(access, integration.getCloudId(), integration.getJiraBoardId()).stream()
					.filter(item -> item != null && item.id() != null)
					.map(item -> new ProjectTaskOptionsResponse.SprintOption(
							String.valueOf(item.id()), item.name(), item.state()))
					.toList();
		}
		return new ProjectTaskOptionsResponse(
				issueTypes,
				priorities,
				users,
				new ProjectTaskOptionsResponse.EstimationOption(
						estimation.supported(), estimation.fieldId(), estimation.fieldName()),
				sprints);
	}

	public ProjectTaskResponse create(UUID userId, UUID projectId, CreateProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Project project = requireProject(projectId);
		String access = tokens.accessToken(integration);

		CreatedIssue created = jiraWrite.createIssue(
				access,
				integration.getCloudId(),
				integration.getJiraProjectId(),
				request.summary(),
				request.description(),
				request.issueTypeId(),
				request.assigneeAccountId(),
				request.priorityId(),
				null);

		boolean secondaryFailed = false;
		if (request.storyPoints() != null) {
			try {
				jiraWrite.setIssueEstimation(
						access,
						integration.getCloudId(),
						integration.getJiraBoardId(),
						created.id(),
						request.storyPoints());
			} catch (IntegrationException ex) {
				secondaryFailed = true;
			}
		}
		String sprintId = request.resolvedSprintId();
		if (sprintId != null) {
			try {
				jiraWrite.moveIssuesToSprint(access, integration.getCloudId(), sprintId, List.of(created.id()));
			} catch (IntegrationException ex) {
				secondaryFailed = true;
			}
		}

		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), created.id());
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(project, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintId != null) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		if (secondaryFailed) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
					HttpStatus.BAD_GATEWAY,
					"Jira issue was created but a secondary field update failed. Local state reflects provider truth; retry the failed field.");
		}
		return ProjectProjectionReadService.toTask(saved, 0L);
	}

	public ProjectTaskResponse patch(UUID userId, UUID projectId, UUID taskId, PatchProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Project project = requireProject(projectId);
		Task task = requireTask(projectId, taskId);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);

		Map<String, Object> fields = new HashMap<>();
		if (request.summary() != null) {
			fields.put("summary", request.summary());
		}
		if (request.description() != null) {
			Map<String, Object> doc = Map.of(
					"type",
					"doc",
					"version",
					1,
					"content",
					List.of(Map.of(
							"type",
							"paragraph",
							"content",
							List.of(Map.of("type", "text", "text", request.description())))));
			fields.put("description", doc);
		}
		if (request.issueTypeId() != null && !request.issueTypeId().isBlank()) {
			fields.put("issuetype", Map.of("id", request.issueTypeId()));
		}
		if (Boolean.TRUE.equals(request.clearAssignee())) {
			fields.put("assignee", null);
		} else if (request.assigneeAccountId() != null) {
			fields.put(
					"assignee",
					request.assigneeAccountId().isBlank()
							? null
							: Map.of("accountId", request.assigneeAccountId()));
		}
		if (request.priorityId() != null && !request.priorityId().isBlank()) {
			fields.put("priority", Map.of("id", request.priorityId()));
		}
		if (!fields.isEmpty()) {
			jiraWrite.updateIssueFields(access, integration.getCloudId(), issueRef, fields);
		}
		if (request.storyPoints() != null) {
			jiraWrite.setIssueEstimation(
					access, integration.getCloudId(), integration.getJiraBoardId(), issueRef, request.storyPoints());
		}

		boolean sprintChanged = false;
		if (Boolean.TRUE.equals(request.moveToBacklog())) {
			requireBoard(integration);
			jiraWrite.moveIssuesToBacklog(
					access, integration.getCloudId(), integration.getJiraBoardId(), List.of(issueRef));
			sprintChanged = true;
		} else if (request.sprintExternalId() != null && !request.sprintExternalId().isBlank()) {
			jiraWrite.moveIssuesToSprint(
					access, integration.getCloudId(), request.sprintExternalId(), List.of(issueRef));
			sprintChanged = true;
		}

		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		boolean sprintMembershipChanged = sprintChanged;
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(project, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintMembershipChanged) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public ProjectTaskResponse moveSprint(
			UUID userId, UUID projectId, UUID taskId, PutProjectTaskSprintRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Project project = requireProject(projectId);
		Task task = requireTask(projectId, taskId);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);
		if (request == null || request.sprintId() == null) {
			requireBoard(integration);
			jiraWrite.moveIssuesToBacklog(
					access, integration.getCloudId(), integration.getJiraBoardId(), List.of(issueRef));
		} else {
			jiraWrite.moveIssuesToSprint(
					access, integration.getCloudId(), String.valueOf(request.sprintId()), List.of(issueRef));
		}
		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(project, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			return row;
		});
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public ProjectTaskResponse transition(
			UUID userId, UUID projectId, UUID taskId, TransitionProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Project project = requireProject(projectId);
		Task task = requireTask(projectId, taskId);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);
		transitionInternal(
				access, integration.getCloudId(), issueRef, request.transitionId(), request.targetStatusId());
		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(project, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			return row;
		});
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public void delete(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Task task = requireTask(projectId, taskId);
		if (workSessions.existsByTask_Id(taskId) || confirmations.existsByTask_Id(taskId)) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_EVIDENCE,
					HttpStatus.CONFLICT,
					"Cannot delete Jira issue while work sessions or contribution confirmations exist for this task.");
		}
		String access = tokens.accessToken(integration);
		String externalId = task.getExternalId();
		jiraWrite.deleteIssue(access, integration.getCloudId(), issueRef(task));
		Project project = requireProject(projectId);
		writes.executeWithoutResult(status -> {
			projection.softDelete(project, externalId, LocalDateTime.now());
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, taskId.toString());
			realtime.publish(ProjectRealtimeEventType.TASK_LINKS_CHANGED, projectId, taskId.toString());
		});
	}

	public List<TransitionOption> listTransitions(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Task task = requireTask(projectId, taskId);
		String access = tokens.accessToken(integration);
		return jiraWrite.listTransitions(access, integration.getCloudId(), issueRef(task));
	}

	private void transitionInternal(
			String access, String cloudId, String issueRef, String transitionId, String targetStatusId) {
		String resolved = transitionId;
		if (resolved == null || resolved.isBlank()) {
			if (targetStatusId == null || targetStatusId.isBlank()) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE,
						HttpStatus.BAD_REQUEST,
						"transitionId or targetStatusId is required.");
			}
			resolved = jiraWrite.listTransitions(access, cloudId, issueRef).stream()
					.filter(item -> targetStatusId.equals(item.toStatusId()))
					.map(TransitionOption::id)
					.findFirst()
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE,
							HttpStatus.CONFLICT,
							"No Jira transition is available for the requested status."));
		}
		jiraWrite.transitionIssue(access, cloudId, issueRef, resolved);
	}

	private JiraIntegration requireActiveJira(UUID projectId) {
		JiraIntegration integration = jiraIntegrations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.BAD_REQUEST, "Jira is not connected."));
		if (integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.BAD_REQUEST, "Jira integration is not active.");
		}
		if (integration.getCloudId() == null
				|| integration.getJiraProjectId() == null
				|| integration.getProjectKey() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Jira integration is incomplete.");
		}
		return integration;
	}

	private void requireBoard(JiraIntegration integration) {
		if (integration.getJiraBoardId() == null || integration.getJiraBoardId().isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SPRINT_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira board is required for backlog moves.");
		}
	}

	private Project requireProject(UUID projectId) {
		return projects.findFetchedById(projectId).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Project was not found."));
	}

	private Task requireTask(UUID projectId, UUID taskId) {
		return tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
	}

	private long linkedCount(UUID projectId, UUID taskId) {
		for (Object[] row : links.countLinksByProjectGrouped(projectId)) {
			if (taskId.equals(row[0])) {
				return (Long) row[1];
			}
		}
		return 0L;
	}

	private static String issueRef(Task task) {
		if (task.getExternalId() != null && !task.getExternalId().isBlank()) {
			return task.getExternalId();
		}
		return task.getExternalKey();
	}
}

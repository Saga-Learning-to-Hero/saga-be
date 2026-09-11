package com.saga.be.service.projection;

import com.saga.be.dto.project.CreateProjectSprintRequest;
import com.saga.be.dto.project.PatchProjectSprintRequest;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.SprintDetail;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SprintRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class ProjectJiraSprintCommandService {

	private final ProjectDataAuthorization authorization;
	private final JiraIntegrationRepository jiraIntegrations;
	private final SprintRepository sprints;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraWrite;
	private final JiraTaskProjectionService projection;
	private final ProjectRealtimePublisher realtime;
	private final TransactionTemplate writes;

	public ProjectJiraSprintCommandService(
			ProjectDataAuthorization authorization,
			JiraIntegrationRepository jiraIntegrations,
			SprintRepository sprints,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraWrite,
			JiraTaskProjectionService projection,
			ProjectRealtimePublisher realtime,
			PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.jiraIntegrations = jiraIntegrations;
		this.sprints = sprints;
		this.tokens = tokens;
		this.jiraWrite = jiraWrite;
		this.projection = projection;
		this.realtime = realtime;
		this.writes = new TransactionTemplate(transactionManager);
	}

	public List<ProjectSprintResponse> syncAndList(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		if (integration.getJiraBoardId() == null || integration.getJiraBoardId().isBlank()) {
			return sprints.findActiveByProject_Id(projectId).stream().map(this::toResponse).toList();
		}
		String access = tokens.accessToken(integration);
		List<SprintDetail> remote = jiraWrite.listBoardSprints(access, integration.getCloudId(), integration.getJiraBoardId());
		for (SprintDetail detail : remote) {
			if (detail == null || detail.id() == null) {
				continue;
			}
			writes.executeWithoutResult(status -> projection.upsertSprint(
					integration,
					String.valueOf(detail.id()),
					detail.name(),
					detail.state(),
					ProjectionMappings.parseInstant(detail.startDate()),
					ProjectionMappings.parseInstant(detail.endDate()),
					detail.goal(),
					ProjectionMappings.parseInstant(detail.completeDate())));
		}
		return sprints.findActiveByProject_Id(projectId).stream().map(this::toResponse).toList();
	}

	public ProjectSprintResponse create(UUID userId, UUID projectId, CreateProjectSprintRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		requireBoard(integration);
		String access = tokens.accessToken(integration);
		SprintDetail created = jiraWrite.createSprint(
				access,
				integration.getCloudId(),
				integration.getJiraBoardId(),
				request.name(),
				request.goal(),
				request.startDate(),
				request.endDate());
		SprintDetail canonical =
				jiraWrite.getSprint(access, integration.getCloudId(), String.valueOf(created.id()));
		Sprint saved = writes.execute(status -> {
			Sprint row = projection.upsertSprint(
					integration,
					String.valueOf(canonical.id()),
					canonical.name(),
					canonical.state(),
					ProjectionMappings.parseInstant(canonical.startDate()),
					ProjectionMappings.parseInstant(canonical.endDate()),
					canonical.goal(),
					ProjectionMappings.parseInstant(canonical.completeDate()));
			realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId, row.getId().toString());
			return row;
		});
		return toResponse(saved);
	}

	public ProjectSprintResponse patch(UUID userId, UUID projectId, UUID sprintId, PatchProjectSprintRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Sprint local = sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Sprint was not found for this project."));
		String access = tokens.accessToken(integration);
		SprintDetail updated = jiraWrite.updateSprint(
				access,
				integration.getCloudId(),
				local.getExternalSprintId(),
				request.name(),
				request.goal(),
				request.state(),
				request.startDate(),
				request.endDate());
		SprintDetail canonical =
				jiraWrite.getSprint(access, integration.getCloudId(), String.valueOf(updated.id()));
		Sprint saved = writes.execute(status -> {
			Sprint row = projection.upsertSprint(
					integration,
					String.valueOf(canonical.id()),
					canonical.name(),
					canonical.state(),
					ProjectionMappings.parseInstant(canonical.startDate()),
					ProjectionMappings.parseInstant(canonical.endDate()),
					canonical.goal(),
					ProjectionMappings.parseInstant(canonical.completeDate()));
			realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId, row.getId().toString());
			return row;
		});
		return toResponse(saved);
	}

	public void delete(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = requireActiveJira(projectId);
		Sprint local = sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Sprint was not found for this project."));
		String access = tokens.accessToken(integration);
		String externalSprintId = local.getExternalSprintId();
		jiraWrite.deleteSprint(access, integration.getCloudId(), externalSprintId);
		writes.executeWithoutResult(status -> {
			projection.softDeleteSprint(integration, externalSprintId, LocalDateTime.now());
			realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId, sprintId.toString());
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId);
		});
	}

	private JiraIntegration requireActiveJira(UUID projectId) {
		JiraIntegration integration = jiraIntegrations.findByProject_Id(projectId).orElseThrow(() -> new IntegrationException(
				IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.BAD_REQUEST, "Jira is not connected."));
		if (integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.BAD_REQUEST, "Jira integration is not active.");
		}
		return integration;
	}

	private void requireBoard(JiraIntegration integration) {
		if (integration.getJiraBoardId() == null || integration.getJiraBoardId().isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SPRINT_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira board is required to manage sprints.");
		}
	}

	private ProjectSprintResponse toResponse(Sprint sprint) {
		return new ProjectSprintResponse(
				sprint.getId(),
				sprint.getExternalSprintId(),
				sprint.getName(),
				sprint.getState(),
				sprint.getGoal(),
				sprint.getStartDate(),
				sprint.getEndDate(),
				sprint.getCompleteDate());
	}
}

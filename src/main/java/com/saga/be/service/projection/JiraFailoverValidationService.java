package com.saga.be.service.projection;

import com.saga.be.dto.integration.failover.JiraFailoverPreviewRequest;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SprintRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class JiraFailoverValidationService {

	private final ProjectDataAuthorization authorization;
	private final JiraIntegrationRepository jiraIntegrations;
	private final SprintRepository sprints;

	public JiraFailoverValidationService(
			ProjectDataAuthorization authorization,
			JiraIntegrationRepository jiraIntegrations,
			SprintRepository sprints) {
		this.authorization = authorization;
		this.jiraIntegrations = jiraIntegrations;
		this.sprints = sprints;
	}

	public ValidatedFailoverTargets validatePreview(
			UUID userId, UUID projectId, UUID sourceIntegrationId, JiraFailoverPreviewRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		if (request == null || request.targetIntegrationId() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FAILOVER_INVALID_REQUEST,
					HttpStatus.BAD_REQUEST,
					"targetIntegrationId is required.");
		}
		return validateSourceTargetSprint(
				projectId, sourceIntegrationId, request.targetIntegrationId(), request.targetSprintId());
	}

	public ValidatedFailoverTargets validateSourceTargetSprint(
			UUID projectId, UUID sourceIntegrationId, UUID targetIntegrationId, UUID targetSprintId) {
		if (sourceIntegrationId == null || targetIntegrationId == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FAILOVER_INVALID_REQUEST,
					HttpStatus.BAD_REQUEST,
					"sourceIntegrationId and targetIntegrationId are required.");
		}
		if (Objects.equals(sourceIntegrationId, targetIntegrationId)) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FAILOVER_SOURCE_TARGET_SAME,
					HttpStatus.BAD_REQUEST,
					"Source and target Jira integrations must differ.");
		}

		JiraIntegration source = jiraIntegrations
				.findByIdAndProject_Id(sourceIntegrationId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Source Jira integration was not found for this project."));

		JiraIntegration target = jiraIntegrations
				.findByIdAndProject_Id(targetIntegrationId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Target Jira integration was not found for this project."));

		if (target.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FAILOVER_TARGET_NOT_ACTIVE,
					HttpStatus.BAD_REQUEST,
					"Target Jira integration is not active.");
		}
		if (isBlank(target.getCloudId()) || isBlank(target.getJiraProjectId())) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_FAILOVER_TARGET_IDENTITY_INCOMPLETE,
					HttpStatus.BAD_REQUEST,
					"Target Jira integration is missing cloudId or jiraProjectId.");
		}

		Sprint targetSprint = null;
		if (targetSprintId != null) {
			targetSprint = sprints
					.findActiveByIdAndProject_Id(targetSprintId, projectId)
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.JIRA_FAILOVER_SPRINT_INVALID,
							HttpStatus.BAD_REQUEST,
							"Target sprint was not found for this project."));
			if (targetSprint.getJiraIntegration() == null
					|| !Objects.equals(targetSprint.getJiraIntegration().getId(), target.getId())) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_FAILOVER_SPRINT_INVALID,
						HttpStatus.BAD_REQUEST,
						"Target sprint does not belong to the target Jira integration.");
			}
		}

		return new ValidatedFailoverTargets(source, target, targetSprint);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public record ValidatedFailoverTargets(
			JiraIntegration source, JiraIntegration target, Sprint targetSprint) {}
}

package com.saga.be.service.identity;

import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Hard-deletes Jira Task/Sprint projection rows for a SAGA project so a different Jira source
 * cannot resurrect stale {@code (project_id, external_id)} Task rows, or leave stale Sprint rows
 * misattributed to the new source. Never deletes protected evidence ({@code task_work_session},
 * {@code contribution_confirmation}); those must block replacement.
 */
@Service
@Profile("!test")
public class JiraTaskProjectionHardReset {

	private final TaskRepository tasks;
	private final TaskWorkSessionRepository workSessions;
	private final ContributionConfirmationRepository confirmations;
	private final SprintRepository sprints;

	public JiraTaskProjectionHardReset(
			TaskRepository tasks,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations,
			SprintRepository sprints) {
		this.tasks = tasks;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
		this.sprints = sprints;
	}

	public boolean protectedEvidenceExists(UUID projectId) {
		return workSessions.existsByProject_Id(projectId)
				|| workSessions.existsByTask_Project_Id(projectId)
				|| confirmations.existsByProject_Id(projectId)
				|| confirmations.existsByTask_Project_Id(projectId);
	}

	/**
	 * Clears self-FK then hard-deletes all tasks. CASCADE removes regenerable link children.
	 * Protected evidence FKs are RESTRICT — callers must check evidence first; concurrent inserts
	 * surface as integrity violations that roll the replacement transaction back.
	 */
	public void hardDeleteAllTasksForProject(UUID projectId) {
		tasks.clearBlocksTaskReferencesByProjectId(projectId);
		tasks.deleteByProject_Id(projectId);
	}

	/**
	 * Sprint is reached only via {@code jira_integration_id}, never {@code project_id} (see
	 * {@link com.saga.be.entity.jira.Sprint}), so a source replacement that reuses the SAME
	 * {@code jira_integration} row (same id, new cloudId/jiraProjectId) would otherwise leave the
	 * old source's Sprint rows still attached to that id — indistinguishable from the new source's
	 * future Sprints. Must be called for the same {@code integrationId} whose Task rows were just
	 * hard-deleted by {@link #hardDeleteAllTasksForProject}, inside the same transaction/try block,
	 * so a protected-evidence FK violation here is caught and mapped the same way.
	 */
	public void hardDeleteAllSprintsForIntegration(UUID integrationId) {
		sprints.deleteByJiraIntegration_Id(integrationId);
	}
}

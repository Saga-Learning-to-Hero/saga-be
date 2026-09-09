package com.saga.be.service.identity;

import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Hard-deletes Jira Task projection rows for a SAGA project so a different Jira source cannot
 * resurrect stale {@code (project_id, external_id)} rows. Never deletes protected evidence
 * ({@code task_work_session}, {@code contribution_confirmation}); those must block replacement.
 */
@Service
@Profile("!test")
public class JiraTaskProjectionHardReset {

	private final TaskRepository tasks;
	private final TaskWorkSessionRepository workSessions;
	private final ContributionConfirmationRepository confirmations;

	public JiraTaskProjectionHardReset(
			TaskRepository tasks,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations) {
		this.tasks = tasks;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
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
}

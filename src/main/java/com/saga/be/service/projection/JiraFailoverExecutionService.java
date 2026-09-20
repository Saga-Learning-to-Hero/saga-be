package com.saga.be.service.projection;

import com.saga.be.dto.integration.failover.JiraFailoverExecuteRequest;
import com.saga.be.dto.integration.failover.JiraFailoverExecuteResponse;
import com.saga.be.dto.integration.failover.JiraFailoverReconcileRequest;
import com.saga.be.dto.integration.failover.JiraFailoverRunResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.JiraTaskFailoverRun;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.JiraTaskFailoverRunRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.identity.ProjectIntegrationService;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/** Explicit cutover API orchestration. Provider HTTP is delegated only after snapshot persistence. */
@Service
@Profile("!test")
public class JiraFailoverExecutionService {
	private final ProjectDataAuthorization authorization;
	private final UserAccountRepository users;
	private final ProjectRepository projects;
	private final JiraIntegrationRepository integrations;
	private final SprintRepository sprints;
	private final TaskRepository tasks;
	private final JiraTaskFailoverRunRepository runs;
	private final JiraFailoverPersistenceService state;
	private final JiraFailoverWorker worker;
	private final ProjectIntegrationService integrationCommands;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jira;

	public JiraFailoverExecutionService(ProjectDataAuthorization authorization, UserAccountRepository users,
			ProjectRepository projects, JiraIntegrationRepository integrations, SprintRepository sprints, TaskRepository tasks,
			JiraTaskFailoverRunRepository runs, JiraFailoverPersistenceService state, JiraFailoverWorker worker,
			ProjectIntegrationService integrationCommands, JiraTeamTokenService tokens, JiraIssueWriteClient jira) {
		this.authorization = authorization; this.users = users; this.projects = projects; this.integrations = integrations;
		this.sprints = sprints; this.tasks = tasks; this.runs = runs; this.state = state; this.worker = worker;
		this.integrationCommands = integrationCommands; this.tokens = tokens; this.jira = jira;
	}

	public JiraFailoverExecuteResponse execute(UUID userId, UUID projectId, UUID sourceIntegrationId,
			JiraFailoverExecuteRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		if (request.sourceTaskIds() == null || request.sourceTaskIds().isEmpty()
				|| new HashSet<>(request.sourceTaskIds()).size() != request.sourceTaskIds().size()) throw invalid("Select distinct source tasks.");
		Project project = projects.findById(projectId).orElseThrow(() -> invalid("Project was not found."));
		JiraIntegration source = requireIntegration(projectId, sourceIntegrationId);
		JiraIntegration target = requireIntegration(projectId, request.targetIntegrationId());
		if (source.getId().equals(target.getId())) throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_SOURCE_TARGET_SAME,
				HttpStatus.BAD_REQUEST, "Source and target Jira integrations must differ.");
		if (target.getConnectionStatus() != IntegrationStatus.ACTIVE) throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_TARGET_NOT_ACTIVE,
				HttpStatus.CONFLICT, "Target Jira integration must be active.");
		if (blank(target.getCloudId()) || blank(target.getJiraProjectId())) throw new IntegrationException(
				IntegrationErrorCode.JIRA_FAILOVER_TARGET_IDENTITY_INCOMPLETE, HttpStatus.BAD_REQUEST, "Target Jira identity is incomplete.");
		if (source.getConnectionStatus() != IntegrationStatus.ACTIVE && source.getConnectionStatus() != IntegrationStatus.REVOKED)
			throw invalid("Source Jira integration is not eligible for cutover.");
		if (source.getConnectionStatus() == IntegrationStatus.ACTIVE && !request.revokeSource())
			throw invalid("Active source must be explicitly revoked before failover execution.");
		Sprint targetSprint = resolveTargetSprint(projectId, target, request.targetSprintId());
		List<Task> selected = request.sourceTaskIds().stream().map(id -> requireEligibleTask(projectId, source, id)).toList();
		UserAccount actor = users.findById(userId).orElseThrow();
		JiraTaskFailoverRun run = state.createSnapshot(project, source, target, actor, targetSprint,
				request.defaultIssueTypeId(), request.revokeSource(), selected);
		// Existing disconnect is deliberate soft revoke: preserves source identity/tasks/evidence and
		// treats webhook unregister as best effort. It happens before the async B worker is queued.
		if (source.getConnectionStatus() == IntegrationStatus.ACTIVE) integrationCommands.disconnectJiraSource(userId, projectId, source.getId());
		worker.processRun(run.getId());
		return new JiraFailoverExecuteResponse(run.getId(), run.getStatus(), selected.size());
	}

	public JiraFailoverExecuteResponse retry(UUID userId, UUID projectId, UUID sourceIntegrationId, UUID runId) {
		authorization.requireStudentLeader(userId, projectId);
		JiraTaskFailoverRun run = requireRun(projectId, sourceIntegrationId, runId);
		worker.processRun(run.getId());
		List<Task> safeFailedSources = state.loadRunItems(run.getId()).stream()
				.filter(item -> item.getStatus() == JiraFailoverItemStatus.FAILED)
				.map(JiraTaskFailoverItem::getSourceTask).toList();
		if (safeFailedSources.isEmpty()) return new JiraFailoverExecuteResponse(run.getId(), run.getStatus(), 0);
		UserAccount actor = users.findById(userId).orElseThrow();
		JiraTaskFailoverRun retry = state.createSnapshot(run.getProject(), run.getSourceJiraIntegration(),
				run.getTargetJiraIntegration(), actor, run.getTargetSprint(), run.getDefaultIssueTypeId(), false, safeFailedSources);
		worker.processRun(retry.getId());
		return new JiraFailoverExecuteResponse(retry.getId(), retry.getStatus(), safeFailedSources.size());
	}

	public void reconcile(UUID userId, UUID projectId, UUID sourceIntegrationId, UUID runId, UUID itemId,
			JiraFailoverReconcileRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraTaskFailoverRun run = requireRun(projectId, sourceIntegrationId, runId);
		JiraTaskFailoverItem item = state.loadItem(itemId);
		if (!item.getRun().getId().equals(run.getId()) || item.getStatus() != JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN)
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_RECONCILIATION_REQUIRED, HttpStatus.CONFLICT,
					"Only an unknown failover item can be reconciled.");
		JiraIntegration target = run.getTargetJiraIntegration();
		String access = tokens.accessToken(target); // short token DB work completes before provider GET
		if (!jira.issueBelongsToProject(access, target.getCloudId(), request.remoteIssueIdOrKey(), target.getJiraProjectId())) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_REMOTE_ISSUE_INVALID, HttpStatus.BAD_REQUEST,
					"The remote issue is not in the target Jira project.");
		}
		IssueSummary remote = jira.getIssue(access, target.getCloudId(), request.remoteIssueIdOrKey());
		if (remote.id() == null || remote.id().isBlank()) throw new IntegrationException(
				IntegrationErrorCode.JIRA_FAILOVER_REMOTE_ISSUE_INVALID, HttpStatus.BAD_REQUEST, "The remote issue is invalid.");
		state.bindRemote(item.getId(), remote.id(), remote.key());
		worker.recoverBound(item.getId()); // GET/upsert only; never create
	}

	public JiraFailoverRunResponse read(UUID userId, UUID projectId, UUID sourceIntegrationId, UUID runId, int page, int size) {
		authorization.requireReader(userId, projectId);
		JiraTaskFailoverRun run = requireRun(projectId, sourceIntegrationId, runId);
		List<JiraTaskFailoverItem> all = state.loadRunItems(run.getId());
		Map<JiraFailoverItemStatus, Long> counts = new EnumMap<>(JiraFailoverItemStatus.class);
		for (JiraTaskFailoverItem row : all) counts.merge(row.getStatus(), 1L, Long::sum);
		int safePage = Math.max(0, page), safeSize = Math.max(1, Math.min(200, size));
		int total = all.size(), pages = total == 0 ? 0 : (total + safeSize - 1) / safeSize;
		int from = Math.min(total, safePage * safeSize), to = Math.min(total, from + safeSize);
		List<JiraFailoverRunResponse.Item> items = all.subList(from, to).stream().map(this::toItem).toList();
		return new JiraFailoverRunResponse(run.getId(), run.getSourceJiraIntegration().getId(), run.getTargetJiraIntegration().getId(),
			run.getStatus(), Map.copyOf(counts), run.getCreatedAt(), run.getStartedAt(), run.getCompletedAt(), items,
			safePage, safeSize, total, pages, safePage + 1 < pages);
	}

	private JiraFailoverRunResponse.Item toItem(JiraTaskFailoverItem row) {
		Task source = row.getSourceTask(), target = row.getTargetTask();
		return new JiraFailoverRunResponse.Item(row.getId(), source.getId(), source.getExternalKey(), source.getTitle(), row.getStatus(),
			target == null ? null : target.getId(), target == null ? null : target.getExternalKey(), row.getRemoteIssueId(),
			row.getRemoteIssueKey(), row.getErrorCode(), row.getStatus() == JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
	}

	private JiraTaskFailoverRun requireRun(UUID projectId, UUID sourceId, UUID runId) {
		JiraTaskFailoverRun run = runs.findFetchedByIdAndProject_Id(runId, projectId).orElseThrow(() -> invalid("Failover run was not found."));
		if (!run.getSourceJiraIntegration().getId().equals(sourceId)) throw invalid("Failover run does not belong to this source.");
		return run;
	}
	private JiraIntegration requireIntegration(UUID projectId, UUID id) { return integrations.findByIdAndProject_Id(id, projectId)
		.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Jira source was not found.")); }
	private Sprint resolveTargetSprint(UUID projectId, JiraIntegration target, UUID sprintId) {
		if (sprintId == null) return null;
		Sprint sprint = sprints.findByIdAndJiraIntegration_Project_IdAndDeletedAtIsNull(sprintId, projectId).orElseThrow(() ->
			new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_SPRINT_INVALID, HttpStatus.BAD_REQUEST, "Target sprint is invalid."));
		if (!target.getId().equals(sprint.getJiraIntegration().getId())) throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_SPRINT_INVALID,
			HttpStatus.BAD_REQUEST, "Target sprint belongs to another Jira source.");
		return sprint;
	}
	private Task requireEligibleTask(UUID projectId, JiraIntegration source, UUID taskId) {
		Task task = tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId).orElseThrow(() -> invalid("Selected task is not active."));
		if (task.getStatus() == com.saga.be.entity.enums.TaskStatus.DONE || task.getJiraIntegration() == null
				|| !source.getId().equals(task.getJiraIntegration().getId())) throw invalid("Selected task is not eligible for this source.");
		return task;
	}
	private static boolean blank(String value) { return value == null || value.isBlank(); }
	private static IntegrationException invalid(String message) { return new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_INVALID_REQUEST, HttpStatus.BAD_REQUEST, message); }
}

package com.saga.be.service.projection;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.CreatedIssue;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.service.contribution.TaskLabelParser;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

/** Bounded, single-executor failover worker. Every JDBC operation is delegated to a short TX. */
@Service
@Profile("!test")
public class JiraFailoverWorker {
	private final JiraFailoverPersistenceService state;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jira;
	private final JiraTaskProjectionService projection;

	public JiraFailoverWorker(JiraFailoverPersistenceService state, JiraTeamTokenService tokens,
			JiraIssueWriteClient jira, JiraTaskProjectionService projection) {
		this.state = state; this.tokens = tokens; this.jira = jira; this.projection = projection;
	}

	@Async("integrationSyncExecutor")
	public void processRun(UUID runId) {
		// A process dying after the durable CREATING transition must never recreate remotely.
		for (JiraTaskFailoverItem item : state.loadRunItems(runId)) {
			if (item.getStatus() == JiraFailoverItemStatus.CREATING && item.getRemoteIssueId() == null) state.recoverCreating(item.getId());
		}
		for (JiraTaskFailoverItem item : state.loadRunItems(runId)) {
			if (item.getStatus() == JiraFailoverItemStatus.PENDING) attemptCreate(item.getId());
			else if (item.getStatus() == JiraFailoverItemStatus.REMOTE_BOUND && item.getTargetTask() == null) recoverBound(item.getId());
		}
		state.refreshRun(runId);
	}

	private void attemptCreate(UUID itemId) {
		JiraFailoverPersistenceService.WorkItem work = state.claimPendingForCreate(itemId); // TX commits here
		if (work == null) return;
		JiraIntegration target = target(work);
		final String access;
		try { access = tokens.accessToken(target); }
		catch (RuntimeException ex) { state.markCreateFailed(itemId, "TARGET_TOKEN_UNAVAILABLE"); return; }
		final CreatedIssue created;
		try {
			created = jira.createIssue(access, work.cloudId(), work.jiraProjectId(), work.title(), work.description(),
					work.defaultIssueTypeId(), null, null, work.storyPoint(), TaskLabelParser.parse(work.labelsJson()),
					work.dueDate() == null ? null : work.dueDate().toLocalDate(),
					work.startDate() == null ? null : work.startDate().toLocalDate());
		} catch (IntegrationException ex) {
			// Only a Jira 400 is a proved validation rejection. Every other write failure may have
			// crossed the provider boundary and is deliberately retained for reconciliation.
			if (ex.getStatus().value() == 400) state.markCreateFailed(itemId, ex.getCode().name());
			else state.markCreateUnknown(itemId, ex.getCode().name());
			return;
		} catch (RuntimeException ex) {
			state.markCreateUnknown(itemId, "CREATE_OUTCOME_UNKNOWN");
			return;
		}
		state.bindRemote(itemId, created.id(), created.key()); // durable REMOTE_BOUND before any GET/upsert
		// Sprint placement is deliberately secondary: identity is already durable, so a failure
		// leaves REMOTE_BOUND for GET/upsert recovery and can never trigger another CREATE.
		if (work.targetSprintExternalId() != null && !work.targetSprintExternalId().isBlank()) {
			try { jira.moveIssuesToSprint(access, work.cloudId(), work.targetSprintExternalId(), List.of(created.id())); }
			catch (RuntimeException ignored) { return; }
		}
		recoverBound(itemId);
	}

	public void recoverBound(UUID itemId) {
		JiraTaskFailoverItem item = state.loadItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.REMOTE_BOUND || item.getTargetTask() != null) return;
		JiraIntegration target = item.getRun().getTargetJiraIntegration();
		try {
			String access = tokens.accessToken(target);
			IssueSummary issue = jira.getIssue(access, target.getCloudId(), item.getRemoteIssueId() != null
					? item.getRemoteIssueId() : item.getRemoteIssueKey());
			Task saved = projection.upsertOne(target, target.getProjectKey(), issue);
			state.completeBound(itemId, saved);
		} catch (RuntimeException ignored) {
			// The remote identity is already durable; retain REMOTE_BOUND for GET/upsert recovery.
		}
	}

	private static JiraIntegration target(JiraFailoverPersistenceService.WorkItem work) {
		JiraIntegration target = new JiraIntegration();
		target.setId(work.targetIntegrationId()); target.setCloudId(work.cloudId());
		target.setJiraProjectId(work.jiraProjectId()); target.setProjectKey(work.targetProjectKey());
		return target;
	}
}

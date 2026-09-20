package com.saga.be.service.projection;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.enums.JiraFailoverRunStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.JiraTaskFailoverRemoteIssueBinding;
import com.saga.be.entity.jira.JiraTaskFailoverRun;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.JiraTaskFailoverRemoteIssueBindingRepository;
import com.saga.be.repository.JiraTaskFailoverRunRepository;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

/** Short database-only failover state transitions. Never performs provider HTTP. */
@Service
@Profile("!test")
public class JiraFailoverPersistenceService {
	private final JiraTaskFailoverRunRepository runs;
	private final JiraTaskFailoverItemRepository items;
	private final JiraTaskFailoverRemoteIssueBindingRepository remoteBindings;

	public JiraFailoverPersistenceService(JiraTaskFailoverRunRepository runs, JiraTaskFailoverItemRepository items,
			JiraTaskFailoverRemoteIssueBindingRepository remoteBindings) {
		this.runs = runs;
		this.items = items;
		this.remoteBindings = remoteBindings;
	}

	@Transactional
	public JiraTaskFailoverRun createSnapshot(Project project, JiraIntegration source, JiraIntegration target,
			UserAccount actor, Sprint targetSprint, String defaultIssueTypeId, boolean revokeSource, List<Task> sources) {
		JiraTaskFailoverRun run = new JiraTaskFailoverRun();
		run.setProject(project);
		run.setSourceJiraIntegration(source);
		run.setTargetJiraIntegration(target);
		run.setRequestedByUser(actor);
		run.setTargetSprint(targetSprint);
		run.setDefaultIssueTypeId(defaultIssueTypeId);
		run.setRevokeSourceRequested(revokeSource);
		run.setStatus(JiraFailoverRunStatus.PENDING);
		runs.save(run);
		for (Task sourceTask : sources) {
			JiraTaskFailoverItem item = new JiraTaskFailoverItem();
			item.setRun(run);
			item.setSourceTask(sourceTask);
			item.setSourceStatusSnapshot(sourceTask.getStatus());
			item.setSourceExternalKeySnapshot(sourceTask.getExternalKey());
			item.setStatus(JiraFailoverItemStatus.PENDING);
			items.save(item);
		}
		try {
			items.flush(); // generated V25 unique claim is the cross-run authority
		} catch (DataIntegrityViolationException ex) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_SOURCE_ALREADY_CLAIMED,
					HttpStatus.CONFLICT, "A selected source task is already claimed by another failover run.");
		}
		return run;
	}

	@Transactional
	public WorkItem claimPendingForCreate(UUID itemId) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.PENDING) return null;
		item.transitionTo(JiraFailoverItemStatus.CREATING);
		item.setStartedAt(LocalDateTime.now());
		item.getRun().setStatus(JiraFailoverRunStatus.RUNNING);
		if (item.getRun().getStartedAt() == null) item.getRun().setStartedAt(LocalDateTime.now());
		return WorkItem.from(item);
	}

	@Transactional
	public void markCreateFailed(UUID itemId, String code) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.CREATING) return;
		item.transitionTo(JiraFailoverItemStatus.FAILED);
		item.setErrorCode(code);
		item.setCompletedAt(LocalDateTime.now());
		refreshRun(item.getRun());
	}

	@Transactional
	public void markCreateUnknown(UUID itemId, String code) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.CREATING) return;
		item.transitionTo(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
		item.setErrorCode(code);
		refreshRun(item.getRun());
	}

	@Transactional
	public void bindRemote(UUID itemId, String remoteId, String remoteKey) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.CREATING
				&& item.getStatus() != JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_REMOTE_IDENTITY_IMMUTABLE,
					HttpStatus.CONFLICT, "This failover item already has an immutable remote Jira identity.");
		}
		JiraIntegration target = item.getRun().getTargetJiraIntegration();
		JiraTaskFailoverRemoteIssueBinding binding = new JiraTaskFailoverRemoteIssueBinding();
		binding.setItem(item);
		binding.setTargetJiraIntegration(target);
		binding.setRemoteIssueId(remoteId);
		try {
			remoteBindings.saveAndFlush(binding);
		} catch (DataIntegrityViolationException ex) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_REMOTE_ISSUE_ALREADY_BOUND,
					HttpStatus.CONFLICT, "This target Jira issue is already bound to another failover item.");
		}
		item.transitionTo(JiraFailoverItemStatus.REMOTE_BOUND);
		item.setRemoteIssueId(remoteId);
		item.setRemoteIssueKey(remoteKey);
		item.setErrorCode(null);
		refreshRun(item.getRun());
	}

	@Transactional
	public void completeBound(UUID itemId, Task targetTask) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() != JiraFailoverItemStatus.REMOTE_BOUND) return;
		if (targetTask == null || targetTask.getJiraIntegration() == null
				|| !item.getRun().getTargetJiraIntegration().getId().equals(targetTask.getJiraIntegration().getId())
				|| item.getRemoteIssueId() == null || !item.getRemoteIssueId().equals(targetTask.getExternalId())) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_FAILOVER_REMOTE_ISSUE_INVALID,
					HttpStatus.CONFLICT, "Target task does not match this item's bound target Jira issue.");
		}
		item.setTargetTask(targetTask);
		item.transitionTo(JiraFailoverItemStatus.SUCCEEDED);
		item.setCompletedAt(LocalDateTime.now());
		refreshRun(item.getRun());
	}

	@Transactional
	public void recoverCreating(UUID itemId) {
		JiraTaskFailoverItem item = requireItem(itemId);
		if (item.getStatus() == JiraFailoverItemStatus.CREATING && item.getRemoteIssueId() == null) {
			item.transitionTo(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
			item.setErrorCode("CREATE_OUTCOME_UNKNOWN_AFTER_RECOVERY");
			refreshRun(item.getRun());
		}
	}

	@Transactional(readOnly = true)
	public List<JiraTaskFailoverItem> loadRunItems(UUID runId) { return items.findAllFetchedByRun_Id(runId); }

	@Transactional(readOnly = true)
	public JiraTaskFailoverItem loadItem(UUID itemId) { return requireItem(itemId); }

	@Transactional
	public void refreshRun(UUID runId) { refreshRun(runs.findById(runId).orElseThrow()); }

	private JiraTaskFailoverItem requireItem(UUID id) { return items.findFetchedById(id).orElseThrow(); }

	private void refreshRun(JiraTaskFailoverRun run) {
		List<JiraTaskFailoverItem> rows = items.findAllFetchedByRun_Id(run.getId());
		Map<JiraFailoverItemStatus, Integer> counts = new EnumMap<>(JiraFailoverItemStatus.class);
		for (JiraTaskFailoverItem row : rows) counts.merge(row.getStatus(), 1, Integer::sum);
		boolean unknown = counts.containsKey(JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN);
		boolean incomplete = counts.containsKey(JiraFailoverItemStatus.PENDING) || counts.containsKey(JiraFailoverItemStatus.CREATING)
				|| counts.containsKey(JiraFailoverItemStatus.REMOTE_BOUND);
		boolean success = counts.containsKey(JiraFailoverItemStatus.SUCCEEDED);
		boolean safeFailure = counts.containsKey(JiraFailoverItemStatus.FAILED) || counts.containsKey(JiraFailoverItemStatus.SKIPPED)
				|| counts.containsKey(JiraFailoverItemStatus.ABANDONED);
		if (unknown) run.setStatus(JiraFailoverRunStatus.RECONCILIATION_REQUIRED);
		else if (incomplete) run.setStatus(counts.size() == 1 && counts.containsKey(JiraFailoverItemStatus.PENDING)
				? JiraFailoverRunStatus.PENDING : JiraFailoverRunStatus.RUNNING);
		else if (success && safeFailure) run.setStatus(JiraFailoverRunStatus.PARTIAL);
		else if (success) run.setStatus(JiraFailoverRunStatus.SUCCEEDED);
		else run.setStatus(JiraFailoverRunStatus.FAILED);
		if (!unknown && !incomplete) run.setCompletedAt(LocalDateTime.now());
	}

	public record WorkItem(UUID itemId, UUID runId, UUID targetIntegrationId, String cloudId, String jiraProjectId,
			String targetProjectKey, String defaultIssueTypeId, String title, String description, String labelsJson,
			Integer storyPoint, LocalDateTime dueDate, LocalDateTime startDate, String targetSprintExternalId,
			String remoteIssueId, String remoteIssueKey) {
		static WorkItem from(JiraTaskFailoverItem item) {
			Task source = item.getSourceTask(); JiraIntegration target = item.getRun().getTargetJiraIntegration();
			return new WorkItem(item.getId(), item.getRun().getId(), target.getId(), target.getCloudId(), target.getJiraProjectId(),
				target.getProjectKey(), item.getRun().getDefaultIssueTypeId(), source.getTitle(), source.getDescription(),
				source.getLabelsJson(), source.getStoryPoint(), source.getDueDate(), source.getStartDate(),
				item.getRun().getTargetSprint() == null ? null : item.getRun().getTargetSprint().getExternalSprintId(),
				item.getRemoteIssueId(), item.getRemoteIssueKey());
		}
	}
}

package com.saga.be.service.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class JiraTaskProjectionService {

	private static final List<IdentityMappingStatus> ACTIVE_STATUSES =
			List.of(IdentityMappingStatus.ACTIVE, IdentityMappingStatus.VERIFIED, IdentityMappingStatus.PENDING);

	private final TaskRepository tasks;
	private final IdentityMapRepository identities;
	private final StudentProfileRepository students;
	private final CommitTaskAutoLinkService autoLink;
	private final SprintRepository sprints;
	private final ObjectMapper mapper;

	public JiraTaskProjectionService(
			TaskRepository tasks,
			IdentityMapRepository identities,
			StudentProfileRepository students,
			CommitTaskAutoLinkService autoLink,
			SprintRepository sprints,
			ObjectMapper mapper) {
		this.tasks = tasks;
		this.identities = identities;
		this.students = students;
		this.autoLink = autoLink;
		this.sprints = sprints;
		this.mapper = mapper;
	}

	/**
	 * Provider-backed upsert identity is {@code (jiraIntegrationId, externalId)}. Same Jira issue id
	 * from two sources under one Project remains two distinct Task UUIDs.
	 */
	@Transactional
	public int upsertBatch(JiraIntegration integration, String jiraProjectKey, List<IssueSummary> issues) {
		if (integration == null
				|| integration.getId() == null
				|| integration.getProject() == null
				|| integration.getProject().getId() == null
				|| issues == null
				|| issues.isEmpty()) {
			return 0;
		}
		assertIntegrationBelongsToItsProject(integration);
		Project project = integration.getProject();
		List<IssueSummary> valid = issues.stream()
				.filter(item -> item != null && item.id() != null && !item.id().isBlank())
				.toList();
		if (valid.isEmpty()) {
			return 0;
		}
		Set<String> externalIds = valid.stream().map(IssueSummary::id).collect(Collectors.toCollection(HashSet::new));
		Map<String, Task> existing = tasks
				.findByJiraIntegration_IdAndExternalIdIn(integration.getId(), externalIds)
				.stream()
				.collect(Collectors.toMap(Task::getExternalId, Function.identity(), (a, b) -> a));
		Map<String, StudentProfile> assignees = resolveJiraAssignees(valid);
		Map<String, Sprint> sprintByExternalId = resolveSprints(integration, valid);
		List<Task> toSave = new ArrayList<>();
		Set<String> reverseLinkExternalIds = new HashSet<>();
		for (IssueSummary issue : valid) {
			Task task = existing.getOrDefault(issue.id(), new Task());
			LocalDateTime incoming = ProjectionMappings.parseInstant(issue.updated());
			if (!shouldApply(task, incoming)) {
				continue;
			}
			boolean newlyCreated = task.getId() == null;
			String previousKey = task.getExternalKey();
			if (newlyCreated) {
				task.setProject(project);
				task.setJiraIntegration(integration);
				task.setExternalId(issue.id());
			}
			applyIssueFields(task, issue, assignees, sprintByExternalId);
			assertTaskProvenance(task, integration);
			toSave.add(task);
			if (newlyCreated || externalKeyChanged(previousKey, issue.key())) {
				reverseLinkExternalIds.add(issue.id());
			}
		}
		if (toSave.isEmpty()) {
			return 0;
		}
		List<Task> persisted = saveAllConflictSafe(integration.getId(), toSave);
		List<Task> forReverseLink = persisted.stream()
				.filter(task -> reverseLinkExternalIds.contains(task.getExternalId()))
				.toList();
		if (!forReverseLink.isEmpty()) {
			autoLink.linkTasks(project.getId(), jiraProjectKey, forReverseLink);
		}
		return persisted.size();
	}

	@Transactional
	public Task upsertOne(JiraIntegration integration, String jiraProjectKey, IssueSummary issue) {
		upsertBatch(integration, jiraProjectKey, List.of(issue));
		return tasks.findByJiraIntegration_IdAndExternalId(integration.getId(), issue.id()).orElseThrow();
	}

	@Transactional
	public void softDelete(JiraIntegration integration, String externalId, LocalDateTime when) {
		if (integration == null || integration.getId() == null || externalId == null || externalId.isBlank()) {
			return;
		}
		tasks.findByJiraIntegration_IdAndExternalId(integration.getId(), externalId).ifPresent(task -> {
			LocalDateTime deletedAt = when == null ? LocalDateTime.now() : when;
			if (task.getDeletedAt() != null && task.getDeletedAt().isAfter(deletedAt)) {
				return;
			}
			task.setDeletedAt(deletedAt);
			tasks.save(task);
		});
	}

	@Transactional
	public Sprint upsertSprint(JiraIntegration integration, String externalSprintId, String name, String state,
			LocalDateTime startDate, LocalDateTime endDate, String goal, LocalDateTime completeDate) {
		Sprint sprint = sprints
				.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), externalSprintId)
				.orElseGet(Sprint::new);
		if (sprint.getId() == null) {
			sprint.setJiraIntegration(integration);
			sprint.setExternalSprintId(externalSprintId);
		}
		sprint.setName(name);
		sprint.setState(state);
		sprint.setStartDate(startDate);
		sprint.setEndDate(endDate);
		sprint.setGoal(goal);
		sprint.setCompleteDate(completeDate);
		sprint.setDeletedAt(null);
		return sprints.save(sprint);
	}

	@Transactional
	public void softDeleteSprint(JiraIntegration integration, String externalSprintId, LocalDateTime when) {
		sprints.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), externalSprintId).ifPresent(sprint -> {
			sprint.setDeletedAt(when == null ? LocalDateTime.now() : when);
			sprints.save(sprint);
		});
	}

	static boolean shouldApply(Task task, LocalDateTime incoming) {
		if (task.getId() == null) {
			return true;
		}
		LocalDateTime stored = task.getExternalUpdatedAt();
		if (task.getDeletedAt() != null) {
			return incoming != null && incoming.isAfter(task.getDeletedAt());
		}
		if (incoming != null && stored != null && incoming.isBefore(stored)) {
			return false;
		}
		return true;
	}

	static boolean externalKeyChanged(String previousKey, String incomingKey) {
		String previous = previousKey == null ? "" : previousKey.trim();
		String incoming = incomingKey == null ? "" : incomingKey.trim();
		if (previous.isEmpty() && incoming.isEmpty()) {
			return false;
		}
		return !previous.equalsIgnoreCase(incoming);
	}

	/**
	 * Task.project and Task.jiraIntegration.project must be the same SAGA Project. When a Sprint is
	 * set, Task.jiraIntegration must equal Sprint.jiraIntegration. Backlog (null sprint) is allowed.
	 */
	public static void assertTaskProvenance(Task task, JiraIntegration expectedIntegration) {
		if (task == null || expectedIntegration == null || expectedIntegration.getId() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Task Jira source is required");
		}
		if (task.getJiraIntegration() == null
				|| task.getJiraIntegration().getId() == null
				|| !expectedIntegration.getId().equals(task.getJiraIntegration().getId())) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Task Jira source does not match the upsert integration");
		}
		assertIntegrationBelongsToItsProject(expectedIntegration);
		if (task.getProject() == null
				|| task.getProject().getId() == null
				|| !expectedIntegration.getProject().getId().equals(task.getProject().getId())) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Task project must match its Jira integration project");
		}
		Sprint sprint = task.getSprint();
		if (sprint != null) {
			if (sprint.getJiraIntegration() == null
					|| sprint.getJiraIntegration().getId() == null
					|| !expectedIntegration.getId().equals(sprint.getJiraIntegration().getId())) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_SPRINT_INVALID,
						HttpStatus.BAD_REQUEST,
						"Task Jira source must match Sprint Jira source");
			}
		}
	}

	static void assertIntegrationBelongsToItsProject(JiraIntegration integration) {
		if (integration.getProject() == null || integration.getProject().getId() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Jira integration project is required");
		}
	}

	private void applyIssueFields(
			Task task,
			IssueSummary issue,
			Map<String, StudentProfile> assignees,
			Map<String, Sprint> sprintByExternalId) {
		LocalDateTime incoming = ProjectionMappings.parseInstant(issue.updated());
		task.setExternalKey(issue.key());
		task.setTitle(issue.summary());
		task.setDescription(issue.description());
		task.setJiraStatusId(issue.statusId());
		task.setJiraStatusName(issue.statusName());
		task.setJiraStatusCategory(issue.statusCategory());
		task.setIssueTypeName(issue.issueTypeName());
		TaskStatus status = ProjectionMappings.taskStatus(issue.statusCategory(), issue.statusName());
		task.setStatus(status);
		task.setTaskType(ProjectionMappings.taskType(issue.issueTypeName()));
		task.setSagaCompletionState(ProjectionMappings.completion(status));
		task.setAssigneeExternalId(issue.assigneeAccountId());
		if (issue.assigneeAccountId() != null) {
			task.setAssigneeStudent(assignees.get(issue.assigneeAccountId()));
		} else {
			task.setAssigneeStudent(null);
		}
		task.setPriority(ProjectionMappings.priority(issue.priorityName()));
		// storyPointsProvided/sprintProvided distinguish "Jira told us the true current value
		// (possibly null -- explicitly cleared)" from "this payload said nothing about this field"
		// (a partial webhook missing the dynamically-resolved custom field, or field discovery
		// never resolved a field id at all) -- the latter must never overwrite existing data with
		// null. Bulk/full sync and single-issue fetch always mark both as provided (authoritative).
		if (issue.storyPointsProvided()) {
			task.setStoryPoint(issue.storyPoints());
		}
		if (issue.sprintProvided()) {
			if (issue.sprintExternalId() == null || issue.sprintExternalId().isBlank()) {
				task.setSprint(null);
			} else {
				task.setSprint(sprintByExternalId.get(issue.sprintExternalId()));
			}
		}
		// Same provided-flag guard as Story Points/Sprint above. Deliberately NO lookup against a
		// local parent Task row here -- parentExternalId/parentExternalKey are stored verbatim as
		// Jira's own identity, so a parent that hasn't synced yet (or never will) is not a blocker,
		// and a parent that syncs later needs no backfill on this row.
		// Native SAGA parent_task_id is never populated, cleared, or overwritten here.
		if (issue.parentProvided()) {
			task.setParentExternalId(issue.parentExternalId());
			task.setParentExternalKey(issue.parentExternalKey());
		}
		// Same provided-flag guard. Serialized the same way JiraIssueEvidenceSyncService's
		// separate evidence-sync path already writes labelsJson (mapper.writeValueAsString of a
		// plain string list), so both writers produce the exact same JSON shape TaskLabelParser
		// already reads.
		if (issue.labelsProvided()) {
			task.setLabelsJson(writeLabelsJson(issue.labels()));
		}
		// Same provided-flag guard. Jira's "duedate" is a plain calendar date (no time-of-day);
		// stored at local midnight in the existing DATETIME(6) column -- this is a business due
		// date, never to be confused with created_at/updated_at/external_updated_at, which track
		// row/provider modification time, not a planned deadline.
		if (issue.dueDateProvided()) {
			task.setDueDate(issue.dueDate() == null ? null : issue.dueDate().atStartOfDay());
		}
		// Same provided-flag guard as dueDate. startDateProvided already correctly encodes all four
		// required semantics from the caller (JiraIssueWriteClient.toSummary): authoritative
		// sync's absence -> provided=true, value=null -> clears; partial webhook that never carried
		// the dynamically-resolved Start Date field -> provided=false -> this branch is skipped,
		// preserving whatever is already stored; an explicit null in a webhook payload (the field
		// key present but blank) -> provided=true, value=null -> clears; a real value -> updates.
		if (issue.startDateProvided()) {
			task.setStartDate(issue.startDate() == null ? null : issue.startDate().atStartOfDay());
		}
		task.setExternalUpdatedAt(incoming);
		if (status == TaskStatus.DONE && task.getResolvedAt() == null) {
			task.setResolvedAt(incoming == null ? LocalDateTime.now() : incoming);
			task.setCompletedAt(task.getResolvedAt());
		}
		if (task.getDeletedAt() != null && incoming != null && incoming.isAfter(task.getDeletedAt())) {
			task.setDeletedAt(null);
		}
	}

	private String writeLabelsJson(List<String> labels) {
		try {
			return mapper.writeValueAsString(labels == null ? List.of() : labels);
		} catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
			return "[]";
		}
	}

	private Map<String, Sprint> resolveSprints(JiraIntegration integration, List<IssueSummary> issues) {
		Set<String> sprintIds = issues.stream()
				.map(IssueSummary::sprintExternalId)
				.filter(Objects::nonNull)
				.filter(id -> !id.isBlank())
				.collect(Collectors.toCollection(HashSet::new));
		if (sprintIds.isEmpty()) {
			return Map.of();
		}
		Map<String, Sprint> existing = sprints
				.findByJiraIntegration_IdAndExternalSprintIdIn(integration.getId(), sprintIds)
				.stream()
				.collect(Collectors.toMap(Sprint::getExternalSprintId, Function.identity(), (a, b) -> a));
		Map<String, Sprint> resolved = new HashMap<>(existing);
		for (IssueSummary issue : issues) {
			String sprintId = issue.sprintExternalId();
			if (sprintId == null || sprintId.isBlank() || resolved.containsKey(sprintId)) {
				continue;
			}
			Sprint created = upsertSprint(
					integration, sprintId, issue.sprintName(), issue.sprintState(), null, null, null, null);
			resolved.put(sprintId, created);
		}
		for (IssueSummary issue : issues) {
			String sprintId = issue.sprintExternalId();
			if (sprintId == null || sprintId.isBlank()) {
				continue;
			}
			Sprint sprint = resolved.get(sprintId);
			if (sprint == null) {
				continue;
			}
			boolean dirty = false;
			if (issue.sprintName() != null && !issue.sprintName().equals(sprint.getName())) {
				sprint.setName(issue.sprintName());
				dirty = true;
			}
			if (issue.sprintState() != null && !issue.sprintState().equals(sprint.getState())) {
				sprint.setState(issue.sprintState());
				dirty = true;
			}
			if (sprint.getDeletedAt() != null) {
				sprint.setDeletedAt(null);
				dirty = true;
			}
			if (dirty) {
				resolved.put(sprintId, sprints.save(sprint));
			}
		}
		return resolved;
	}

	private List<Task> saveAllConflictSafe(UUID jiraIntegrationId, List<Task> toSave) {
		try {
			return tasks.saveAll(toSave);
		} catch (DataIntegrityViolationException ex) {
			Set<String> ids = toSave.stream().map(Task::getExternalId).collect(Collectors.toSet());
			Map<String, Task> reloaded = tasks
					.findByJiraIntegration_IdAndExternalIdIn(jiraIntegrationId, ids)
					.stream()
					.collect(Collectors.toMap(Task::getExternalId, Function.identity(), (a, b) -> a));
			List<Task> merged = new ArrayList<>();
			for (Task candidate : toSave) {
				Task row = reloaded.getOrDefault(candidate.getExternalId(), candidate);
				if (row.getId() != null && row != candidate) {
					if (!shouldApply(row, candidate.getExternalUpdatedAt())) {
						continue;
					}
					row.setExternalKey(candidate.getExternalKey());
					row.setTitle(candidate.getTitle());
					row.setDescription(candidate.getDescription());
					row.setJiraStatusId(candidate.getJiraStatusId());
					row.setJiraStatusName(candidate.getJiraStatusName());
					row.setJiraStatusCategory(candidate.getJiraStatusCategory());
					row.setIssueTypeName(candidate.getIssueTypeName());
					row.setStatus(candidate.getStatus());
					row.setTaskType(candidate.getTaskType());
					row.setSagaCompletionState(candidate.getSagaCompletionState());
					row.setAssigneeExternalId(candidate.getAssigneeExternalId());
					row.setAssigneeStudent(candidate.getAssigneeStudent());
					row.setPriority(candidate.getPriority());
					row.setStoryPoint(candidate.getStoryPoint());
					row.setSprint(candidate.getSprint());
					row.setParentExternalId(candidate.getParentExternalId());
					row.setParentExternalKey(candidate.getParentExternalKey());
					row.setLabelsJson(candidate.getLabelsJson());
					row.setDueDate(candidate.getDueDate());
					row.setStartDate(candidate.getStartDate());
					row.setExternalUpdatedAt(candidate.getExternalUpdatedAt());
					row.setResolvedAt(candidate.getResolvedAt());
					row.setCompletedAt(candidate.getCompletedAt());
					row.setDeletedAt(candidate.getDeletedAt());
					merged.add(row);
				} else {
					merged.add(candidate);
				}
			}
			return merged.isEmpty() ? List.of() : tasks.saveAll(merged);
		}
	}

	private Map<String, StudentProfile> resolveJiraAssignees(List<IssueSummary> issues) {
		Set<String> accountIds = issues.stream()
				.map(IssueSummary::assigneeAccountId)
				.filter(Objects::nonNull)
				.filter(id -> !id.isBlank())
				.collect(Collectors.toCollection(HashSet::new));
		if (accountIds.isEmpty()) {
			return Map.of();
		}
		List<IdentityMap> maps = identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(
				IntegrationProvider.JIRA, accountIds, ACTIVE_STATUSES);
		Set<UUID> userIds = maps.stream().map(map -> map.getUserAccount().getId()).collect(Collectors.toSet());
		Map<UUID, StudentProfile> profiles = userIds.isEmpty()
				? Map.of()
				: students.findFetchedByUserAccount_IdIn(userIds).stream()
						.collect(Collectors.toMap(p -> p.getUserAccount().getId(), Function.identity(), (a, b) -> a));
		Map<String, StudentProfile> byExternal = new HashMap<>();
		for (IdentityMap map : maps) {
			StudentProfile profile = profiles.get(map.getUserAccount().getId());
			if (profile != null) {
				byExternal.put(map.getExternalAccountId(), profile);
			}
		}
		return byExternal;
	}
}

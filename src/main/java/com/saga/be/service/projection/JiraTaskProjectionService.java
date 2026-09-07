package com.saga.be.service.projection;

import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.IdentityMapRepository;
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

	public JiraTaskProjectionService(
			TaskRepository tasks,
			IdentityMapRepository identities,
			StudentProfileRepository students,
			CommitTaskAutoLinkService autoLink) {
		this.tasks = tasks;
		this.identities = identities;
		this.students = students;
		this.autoLink = autoLink;
	}

	@Transactional
	public int upsertBatch(Project project, String jiraProjectKey, List<IssueSummary> issues) {
		if (project == null || project.getId() == null || issues == null || issues.isEmpty()) {
			return 0;
		}
		List<IssueSummary> valid = issues.stream()
				.filter(item -> item != null && item.id() != null && !item.id().isBlank())
				.toList();
		if (valid.isEmpty()) {
			return 0;
		}
		Set<String> externalIds = valid.stream().map(IssueSummary::id).collect(Collectors.toCollection(HashSet::new));
		Map<String, Task> existing = tasks.findByProject_IdAndExternalIdIn(project.getId(), externalIds).stream()
				.collect(Collectors.toMap(Task::getExternalId, Function.identity(), (a, b) -> a));
		Map<String, StudentProfile> assignees = resolveJiraAssignees(valid);
		List<Task> toSave = new ArrayList<>();
		for (IssueSummary issue : valid) {
			Task task = existing.getOrDefault(issue.id(), new Task());
			LocalDateTime incoming = ProjectionMappings.parseInstant(issue.updated());
			if (!shouldApply(task, incoming)) {
				continue;
			}
			if (task.getId() == null) {
				task.setProject(project);
				task.setExternalId(issue.id());
			}
			task.setExternalKey(issue.key());
			task.setTitle(issue.summary());
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
			task.setExternalUpdatedAt(incoming);
			if (status == TaskStatus.DONE && task.getResolvedAt() == null) {
				task.setResolvedAt(incoming == null ? LocalDateTime.now() : incoming);
				task.setCompletedAt(task.getResolvedAt());
			}
			if (task.getDeletedAt() != null
					&& incoming != null
					&& incoming.isAfter(task.getDeletedAt())) {
				task.setDeletedAt(null);
			}
			toSave.add(task);
		}
		if (toSave.isEmpty()) {
			return 0;
		}
		List<Task> persisted = saveAllConflictSafe(project.getId(), toSave);
		autoLink.linkTasks(project.getId(), jiraProjectKey, persisted);
		return persisted.size();
	}

	/** Backward-compatible overload used by tests/callers that omit projectKey. */
	@Transactional
	public int upsertBatch(Project project, List<IssueSummary> issues) {
		return upsertBatch(project, null, issues);
	}

	@Transactional
	public void softDelete(Project project, String externalId, LocalDateTime when) {
		if (project == null || externalId == null || externalId.isBlank()) {
			return;
		}
		tasks.findByProject_IdAndExternalId(project.getId(), externalId).ifPresent(task -> {
			LocalDateTime deletedAt = when == null ? LocalDateTime.now() : when;
			if (task.getDeletedAt() != null
					&& task.getDeletedAt().isAfter(deletedAt)) {
				return;
			}
			task.setDeletedAt(deletedAt);
			tasks.save(task);
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

	private List<Task> saveAllConflictSafe(UUID projectId, List<Task> toSave) {
		try {
			return tasks.saveAll(toSave);
		} catch (DataIntegrityViolationException ex) {
			Set<String> ids = toSave.stream().map(Task::getExternalId).collect(Collectors.toSet());
			Map<String, Task> reloaded = tasks.findByProject_IdAndExternalIdIn(projectId, ids).stream()
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
					row.setJiraStatusId(candidate.getJiraStatusId());
					row.setJiraStatusName(candidate.getJiraStatusName());
					row.setJiraStatusCategory(candidate.getJiraStatusCategory());
					row.setIssueTypeName(candidate.getIssueTypeName());
					row.setStatus(candidate.getStatus());
					row.setTaskType(candidate.getTaskType());
					row.setSagaCompletionState(candidate.getSagaCompletionState());
					row.setAssigneeExternalId(candidate.getAssigneeExternalId());
					row.setAssigneeStudent(candidate.getAssigneeStudent());
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

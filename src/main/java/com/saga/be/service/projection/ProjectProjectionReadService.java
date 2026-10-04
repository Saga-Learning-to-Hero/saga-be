package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitPageResponse;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.ProjectTaskSprintResponse;
import com.saga.be.dto.project.TaskJiraSourceSummary;
import com.saga.be.dto.project.TaskParentOptionsResponse;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.dto.integration.failover.TaskMigrationSummary;
import com.saga.be.entity.account.UserAccount;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectProjectionReadService {

	static final int DEFAULT_PAGE = 0;
	static final int DEFAULT_SIZE = 50;
	static final int MAX_SIZE = 200;

	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final SprintRepository sprints;
	private final ProjectDataAuthorization authorization;
	private final TaskHierarchyService hierarchy;
	private final JiraTaskFailoverItemRepository failoverItems;
	private TaskAttachmentRepository attachments;

	public ProjectProjectionReadService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			SprintRepository sprints,
			ProjectDataAuthorization authorization,
			TaskHierarchyService hierarchy, JiraTaskFailoverItemRepository failoverItems) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.files = files;
		this.webLinks = webLinks;
		this.sprints = sprints;
		this.authorization = authorization;
		this.hierarchy = hierarchy;
		this.failoverItems = failoverItems;
	}

	/** Jira attachments also prove a document/research task; optional so hand-built test instances need not wire it. */
	@org.springframework.beans.factory.annotation.Autowired(required = false)
	void setAttachments(TaskAttachmentRepository attachments) {
		this.attachments = attachments;
	}

	@Transactional(readOnly = true)
	public List<ProjectTaskResponse> listTasks(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		List<Task> rows = tasks.findActiveFetchedByProject_Id(projectId);
		Map<UUID, Long> counts = linkCounts(projectId);
		Map<UUID, Long> evidence = evidenceCounts(projectId);
		Map<UUID, Long> commitProof = commitProofCounts(projectId);
		Map<UUID, Long> documentProof = documentProofCounts(projectId, evidence);
		Map<UUID, TaskMigrationSummary> migrations = migrations(rows);
		Map<String, UUID> bySourceAndJiraId = new java.util.HashMap<>();
		for (Task row : rows) {
			if (row.getJiraIntegration() != null && row.getExternalId() != null) {
				bySourceAndJiraId.put(JiraParentResolution.key(row.getJiraIntegration().getId(), row.getExternalId()), row.getId());
			}
		}
		ParentLookup lookup = (integrationId, externalId) -> bySourceAndJiraId.get(JiraParentResolution.key(integrationId, externalId));
		Set<String> parentsOfSubtasks = new java.util.HashSet<>();
		for (Task row : rows) {
			if (row.getJiraIntegration() == null) {
				continue;
			}
			if ("SUBTASK".equals(row.getIssueTypeLevel())
					&& row.getParentExternalId() != null
					&& !row.getParentExternalId().isBlank()) {
				parentsOfSubtasks.add(JiraParentResolution.key(row.getJiraIntegration().getId(), row.getParentExternalId()));
			}
		}
		return rows.stream()
				.map(task -> {
					String ownKey = task.getJiraIntegration() == null || task.getExternalId() == null
							? null
							: JiraParentResolution.key(task.getJiraIntegration().getId(), task.getExternalId());
					return toTask(
							task,
							counts.getOrDefault(task.getId(), 0L),
							evidence.getOrDefault(task.getId(), 0L),
							commitProof.getOrDefault(task.getId(), 0L),
							documentProof.getOrDefault(task.getId(), 0L),
							(List<ProjectTaskResponse.Subtask>) null,
							migrations.getOrDefault(task.getId(), TaskMigrationSummary.none()),
							lookup,
							null,
							ownKey != null && parentsOfSubtasks.contains(ownKey));
				})
				.toList();
	}

	@Transactional(readOnly = true)
	public ProjectTaskResponse getTask(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireReader(userId, projectId);
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
		Map<UUID, Long> counts = linkCounts(projectId);
		Map<UUID, Long> evidence = evidenceCounts(projectId);
		List<ProjectTaskResponse.Subtask> children = jiraChildren(task);
		boolean proofOnSubtasks = false;
		for (ProjectTaskResponse.Subtask child : children) {
			if ("SUBTASK".equals(child.issueTypeLevel())) {
				proofOnSubtasks = true;
				break;
			}
		}
		return toTask(
				task,
				counts.getOrDefault(task.getId(), 0L),
				evidence.getOrDefault(task.getId(), 0L),
				commitProofCounts(projectId).getOrDefault(task.getId(), 0L),
				documentProofCounts(projectId, evidence).getOrDefault(task.getId(), 0L),
				children,
				migrations(List.of(task)).getOrDefault(task.getId(), TaskMigrationSummary.none()),
				repositoryParentLookup(tasks, projectId),
				null,
				proofOnSubtasks);
	}

	/**
	 * Resolves (Jira source, parent Jira id) to a SAGA task id, or null. Single-task responses look
	 * it up; the list builds it from the rows it already loaded.
	 */
	@FunctionalInterface
	interface ParentLookup {
		UUID find(UUID jiraIntegrationId, String parentExternalId);
	}

	/** One query per call: an active task of this project with that (source, Jira id). */
	static ParentLookup repositoryParentLookup(TaskRepository tasks, UUID projectId) {
		return (integrationId, externalId) -> tasks.findByJiraIntegration_IdAndExternalId(integrationId, externalId)
				.filter(row -> row.getDeletedAt() == null)
				.filter(row -> row.getProject() != null && projectId.equals(row.getProject().getId()))
				.map(Task::getId)
				.orElse(null);
	}

	@Transactional(readOnly = true)
	public TaskParentOptionsResponse listParentOptions(
			UUID userId, UUID projectId, String q, int page, int size, UUID excludeTaskId) {
		authorization.requireReader(userId, projectId);
		return hierarchy.listParentOptions(projectId, q, page, size, excludeTaskId);
	}

	@Transactional(readOnly = true)
	public List<ProjectSprintResponse> listSprints(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		return sprints.findActiveFetchedByProject_Id(projectId).stream().map(this::toSprint).toList();
	}

	@Transactional(readOnly = true)
	public ProjectSprintResponse getSprint(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		Sprint sprint = sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Sprint was not found for this project."));
		return toSprint(sprint);
	}

	@Transactional(readOnly = true)
	public ProjectCommitPageResponse listCommits(UUID userId, UUID projectId, Integer page, Integer size) {
		return listCommits(userId, projectId, page, size, null);
	}

	@Transactional(readOnly = true)
	public ProjectCommitPageResponse listCommits(
			UUID userId, UUID projectId, Integer page, Integer size, UUID authorStudentId) {
		return listCommits(userId, projectId, page, size, authorStudentId, null, null);
	}

	/**
	 * Optional filters (null = all): {@code authorStudentId} keeps commits attributed to that student
	 * profile; {@code jiraIntegrationId} / {@code sprintId} keep commits linked to a task of that Jira
	 * source / sprint. Ids from another project simply match nothing.
	 */
	@Transactional(readOnly = true)
	public ProjectCommitPageResponse listCommits(
			UUID userId,
			UUID projectId,
			Integer page,
			Integer size,
			UUID authorStudentId,
			UUID jiraIntegrationId,
			UUID sprintId) {
		authorization.requireReader(userId, projectId);
		int pageNumber = page == null ? DEFAULT_PAGE : page;
		int pageSize = size == null ? DEFAULT_SIZE : size;
		if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
		}
		PageRequest pageable = PageRequest.of(pageNumber, pageSize);
		Page<UUID> idPage = authorStudentId == null && jiraIntegrationId == null && sprintId == null
				? commits.findPageIdsByProject(projectId, pageable)
				: commits.findPageIdsByProjectFiltered(projectId, authorStudentId, jiraIntegrationId, sprintId, pageable);
		List<UUID> orderedIds = idPage.getContent();
		List<ProjectCommitResponse> items = List.of();
		if (!orderedIds.isEmpty()) {
			Map<UUID, GitCommit> byId = new HashMap<>();
			for (GitCommit commit : commits.findFetchedByIdIn(orderedIds)) {
				byId.put(commit.getId(), commit);
			}
			List<ProjectCommitResponse> reconstructed = new ArrayList<>(orderedIds.size());
			for (UUID id : orderedIds) {
				GitCommit commit = byId.get(id);
				if (commit != null) {
					reconstructed.add(toCommit(commit));
				}
			}
			items = reconstructed;
		}
		return new ProjectCommitPageResponse(items, pageNumber, pageSize, idPage.getTotalElements());
	}

	@Transactional(readOnly = true)
	public ProjectCommitPageResponse listTaskCommits(
			UUID userId, UUID projectId, UUID taskId, Integer page, Integer size) {
		return listTaskCommits(userId, projectId, taskId, page, size, false);
	}

	/** {@code includeMerges} also returns known merge commits, so total matches the task's linkedCommitCount. */
	@Transactional(readOnly = true)
	public ProjectCommitPageResponse listTaskCommits(
			UUID userId, UUID projectId, UUID taskId, Integer page, Integer size, boolean includeMerges) {
		authorization.requireReader(userId, projectId);
		int pageNumber = page == null ? DEFAULT_PAGE : page;
		int pageSize = size == null ? DEFAULT_SIZE : size;
		if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
		}
		tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
		PageRequest pageable = PageRequest.of(pageNumber, pageSize);
		Page<UUID> idPage = includeMerges
				? links.findPageIdsByProjectAndTaskIncludingMerges(projectId, taskId, pageable)
				: links.findPageIdsByProjectAndTask(projectId, taskId, pageable);
		List<UUID> orderedIds = idPage.getContent();
		List<ProjectCommitResponse> items = List.of();
		if (!orderedIds.isEmpty()) {
			Map<UUID, GitCommit> byId = new HashMap<>();
			for (GitCommit commit : commits.findFetchedByIdIn(orderedIds)) {
				byId.put(commit.getId(), commit);
			}
			List<ProjectCommitResponse> reconstructed = new ArrayList<>(orderedIds.size());
			for (UUID id : orderedIds) {
				GitCommit commit = byId.get(id);
				if (commit != null) {
					reconstructed.add(toCommit(commit));
				}
			}
			items = reconstructed;
		}
		return new ProjectCommitPageResponse(items, pageNumber, pageSize, idPage.getTotalElements());
	}

	private Map<UUID, Long> linkCounts(UUID projectId) {
		return groupedCounts(links.countLinksByProjectGrouped(projectId));
	}

	private Map<UUID, Long> evidenceCounts(UUID projectId) {
		Map<UUID, Long> counts = groupedCounts(files.countByProjectGrouped(projectId));
		for (Map.Entry<UUID, Long> row : groupedCounts(webLinks.countByProjectGrouped(projectId)).entrySet()) {
			counts.merge(row.getKey(), row.getValue(), Long::sum);
		}
		return counts;
	}

	/** Linked non-merge commits per task: the commit proof a code/test task needs. */
	private Map<UUID, Long> commitProofCounts(UUID projectId) {
		return groupedCounts(links.countV23LinksByProjectGrouped(projectId));
	}

	/** Files + web links (already in {@code evidence}) + Jira attachments: a document/research task's proof. */
	private Map<UUID, Long> documentProofCounts(UUID projectId, Map<UUID, Long> evidence) {
		Map<UUID, Long> counts = new HashMap<>(evidence);
		if (attachments == null) {
			return counts;
		}
		for (Map.Entry<UUID, Long> row : groupedCounts(attachments.countByProjectGrouped(projectId)).entrySet()) {
			counts.merge(row.getKey(), row.getValue(), Long::sum);
		}
		return counts;
	}

	private static Map<UUID, Long> groupedCounts(List<Object[]> rows) {
		Map<UUID, Long> counts = new HashMap<>();
		if (rows == null) {
			return counts;
		}
		for (Object[] row : rows) {
			if (row == null || row.length < 2 || !(row[0] instanceof UUID taskId) || !(row[1] instanceof Number count)) {
				continue;
			}
			counts.put(taskId, count.longValue());
		}
		return counts;
	}

	static ProjectTaskResponse toTask(Task task, long linkedCommitCount) {
		return toTask(task, linkedCommitCount, 0L);
	}

	static ProjectTaskResponse toTask(Task task, long linkedCommitCount, long evidenceCount) {
		return toTask(task, linkedCommitCount, evidenceCount, null, TaskMigrationSummary.none());
	}

	static ProjectTaskResponse toTask(
			Task task, long linkedCommitCount, List<ProjectTaskResponse.Subtask> subtasks) {
		return toTask(task, linkedCommitCount, 0L, subtasks, TaskMigrationSummary.none());
	}

	static ProjectTaskResponse toTask(Task task, long linkedCommitCount, List<ProjectTaskResponse.Subtask> subtasks, TaskMigrationSummary migration) {
		return toTask(task, linkedCommitCount, 0L, subtasks, migration);
	}

	/**
	 * Write-path responses (create/patch) have no per-task proof breakdown at hand, so the evidence
	 * check falls back to the raw link count and files + web links; list/detail are authoritative.
	 */
	static ProjectTaskResponse toTask(
			Task task,
			long linkedCommitCount,
			long evidenceCount,
			List<ProjectTaskResponse.Subtask> subtasks,
			TaskMigrationSummary migration) {
		return toTask(task, linkedCommitCount, evidenceCount, linkedCommitCount, evidenceCount, subtasks, migration);
	}

	static ProjectTaskResponse toTask(
			Task task,
			long linkedCommitCount,
			long evidenceCount,
			long commitProofCount,
			long documentProofCount,
			List<ProjectTaskResponse.Subtask> subtasks,
			TaskMigrationSummary migration) {
		return toTask(task, linkedCommitCount, evidenceCount, commitProofCount, documentProofCount, subtasks, migration, null, null, false);
	}

	/** {@code parentLookup} null -> the Jira parent is echoed without resolution. */
	static ProjectTaskResponse toTask(
			Task task,
			long linkedCommitCount,
			long evidenceCount,
			long commitProofCount,
			long documentProofCount,
			List<ProjectTaskResponse.Subtask> subtasks,
			TaskMigrationSummary migration,
			ParentLookup parentLookup) {
		return toTask(
				task,
				linkedCommitCount,
				evidenceCount,
				commitProofCount,
				documentProofCount,
				subtasks,
				migration,
				parentLookup,
				null,
				false);
	}

	/**
	 * {@code proofLabels} null uses this task's own labels, including a Subtask.
	 * {@code proofOnSubtasks} means this task already has Subtasks, so it needs no proof of its own.
	 */
	static ProjectTaskResponse toTask(
			Task task,
			long linkedCommitCount,
			long evidenceCount,
			long commitProofCount,
			long documentProofCount,
			List<ProjectTaskResponse.Subtask> subtasks,
			TaskMigrationSummary migration,
			ParentLookup parentLookup,
			List<String> proofLabels,
			boolean proofOnSubtasks) {
		UserAccount assigneeUser =
				task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getUserAccount();
		String assigneeDisplay = null;
		if (assigneeUser != null && assigneeUser.getFullName() != null && !assigneeUser.getFullName().isBlank()) {
			assigneeDisplay = assigneeUser.getFullName();
		}
		UUID studentId = task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getId();
		ProjectTaskResponse.Assignee assignee = null;
		if (task.getAssigneeExternalId() != null || assigneeDisplay != null || studentId != null) {
			assignee = new ProjectTaskResponse.Assignee(
					task.getAssigneeExternalId(),
					assigneeDisplay,
					studentId,
					assigneeUser == null ? null : assigneeUser.getAvatarUrl());
		}
		String priorityName = task.getPriority() == null ? null : task.getPriority().name();
		ProjectTaskResponse.PriorityDetail priorityDetail =
				priorityName == null ? null : new ProjectTaskResponse.PriorityDetail(null, priorityName);
		ProjectTaskSprintResponse sprint = null;
		if (task.getSprint() != null && task.getSprint().getDeletedAt() == null) {
			sprint = new ProjectTaskSprintResponse(
					task.getSprint().getId(),
					task.getSprint().getExternalSprintId(),
					task.getSprint().getName(),
					task.getSprint().getState());
		}
		// Jira's own parent identity, always echoed (a parent SAGA hasn't synced still shows its true
		// provider identity), plus whether SAGA has that parent -- resolved by (source, Jira id).
		ProjectTaskResponse.Parent parent = null;
		if (JiraParentResolution.hasParent(task)) {
			JiraParentResolution.Result resolved = parentLookup == null || task.getJiraIntegration() == null
					? null
					: JiraParentResolution.of(
							task, parentLookup.find(task.getJiraIntegration().getId(), task.getParentExternalId()));
			parent = new ProjectTaskResponse.Parent(
					task.getParentExternalId(),
					task.getParentExternalKey(),
					resolved == null ? null : resolved.parentTaskId(),
					resolved == null ? null : resolved.resolution(),
					resolved == null ? null : resolved.reason());
		}
		// JOIN FETCH may load a soft-deleted parent via parent_task_id; treat that as absent so
		// list/detail never expose a deleted title/id as an active hierarchy relation.
		ProjectTaskResponse.ParentTask parentTask = null;
		if (task.getParentTask() != null && task.getParentTask().getDeletedAt() == null) {
			parentTask = new ProjectTaskResponse.ParentTask(task.getParentTask().getId(), task.getParentTask().getTitle());
		}
		// Reuses the same reader the contribution/peer-review label-marker feature already relies
		// on (com.saga.be.service.contribution.TaskLabelParser) -- one canonical parse of
		// labelsJson, not a second divergent implementation.
		List<String> labels = com.saga.be.service.contribution.TaskLabelParser.parse(task.getLabelsJson());
		// task.getDueDate() is stored at local midnight (Jira's duedate has no time component) --
		// truncate back to a plain calendar date for the API contract, distinct from the
		// LocalDateTime createdAt/updatedAt/externalUpdatedAt row-modification timestamps.
		java.time.LocalDate dueDate = task.getDueDate() == null ? null : task.getDueDate().toLocalDate();
		// Same truncation rule as dueDate -- task.getStartDate() is stored at local midnight (Jira's
		// Start Date custom field is also date-only, no time component).
		java.time.LocalDate startDate = task.getStartDate() == null ? null : task.getStartDate().toLocalDate();
		TaskJiraSourceSummary source = null;
		if (task.getJiraIntegration() != null) {
			var jira = task.getJiraIntegration();
			source = new TaskJiraSourceSummary(
					jira.getId(),
					jira.getSiteName(),
					jira.getProjectKey(),
					jira.getConnectionStatus() == null ? null : jira.getConnectionStatus().name());
		}
		return new ProjectTaskResponse(
				task.getId(),
				task.getExternalId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getDescription(),
				task.getStatus() == null ? null : task.getStatus().name(),
				task.getJiraStatusId(),
				task.getJiraStatusName(),
				task.getIssueTypeName(),
				task.getIssueTypeId(),
				TaskIssueTypePolicy.apiValue(task.getIssueTypeLevel()),
				task.getJiraHierarchyLevel(),
				task.getAssigneeExternalId(),
				assigneeDisplay,
				studentId,
				assignee,
				priorityName,
				priorityDetail,
				task.getStoryPoint(),
				sprint,
				parent,
				labels,
				dueDate,
				startDate,
				linkedCommitCount,
				evidenceCount,
				evidenceCount > 0,
				task.getExternalUpdatedAt(),
				task.getCreatedAt(),
				task.getUpdatedAt(),
			parentTask,
			source,
			migration,
			subtasks,
			evidenceCheck(
					task,
					proofLabels == null ? labels : proofLabels,
					commitProofCount,
					documentProofCount,
					proofOnSubtasks),
			scheduleCheck(task, startDate, dueDate));
	}

	private static ProjectTaskResponse.ScheduleCheck scheduleCheck(
			Task task, java.time.LocalDate startDate, java.time.LocalDate dueDate) {
		com.saga.be.service.task.TaskSchedulePolicy.SprintWindow window =
				com.saga.be.service.task.TaskSchedulePolicy.windowOf(task.getSprint());
		return new ProjectTaskResponse.ScheduleCheck(
				com.saga.be.service.task.TaskSchedulePolicy.driftIssues(startDate, dueDate, window).stream()
						.map(Enum::name)
						.toList(),
				window == null ? null : window.start(),
				window == null ? null : window.end(),
				com.saga.be.service.task.TaskSchedulePolicy.runsPastSprint(dueDate, window));
	}

	private static ProjectTaskResponse.EvidenceCheck evidenceCheck(
			Task task, List<String> labels, long commitProofCount, long documentProofCount, boolean proofOnSubtasks) {
		com.saga.be.service.contribution.TaskEvidencePolicy.Result result =
				com.saga.be.service.contribution.TaskEvidencePolicy.evaluate(
						task.getStatus(), labels, commitProofCount, documentProofCount, proofOnSubtasks);
		return new ProjectTaskResponse.EvidenceCheck(
				result.categories(),
				result.requiresCommit(),
				result.requiresDocument(),
				commitProofCount,
				documentProofCount,
				result.status().name());
	}

	private Map<UUID, TaskMigrationSummary> migrations(List<Task> rows) {
		if (rows.isEmpty()) return Map.of();
		Map<UUID, TaskMigrationSummary.MigrationLink> from = new HashMap<>(), to = new HashMap<>();
		for (JiraTaskFailoverItem item : failoverItems.findSuccessfulLineageByTaskIds(rows.stream().map(Task::getId).toList())) {
			Task source = item.getSourceTask(), target = item.getTargetTask();
			to.put(source.getId(), new TaskMigrationSummary.MigrationLink(target.getId(), target.getExternalKey(), target.getJiraIntegration().getId(), item.getRun().getId()));
			from.put(target.getId(), new TaskMigrationSummary.MigrationLink(source.getId(), source.getExternalKey(), source.getJiraIntegration().getId(), item.getRun().getId()));
		}
		Map<UUID, TaskMigrationSummary> result = new HashMap<>();
		for (Task task : rows) result.put(task.getId(), new TaskMigrationSummary(from.get(task.getId()), to.get(task.getId()), to.containsKey(task.getId())));
		return result;
	}

	/** Direct Jira children (same source, parent = this issue's Jira id); the canonical hierarchy. */
	private List<ProjectTaskResponse.Subtask> jiraChildren(Task task) {
		List<ProjectTaskResponse.Subtask> children = new java.util.ArrayList<>();
		if (task.getJiraIntegration() == null || task.getExternalId() == null || task.getExternalId().isBlank()) {
			return children;
		}
		for (Object[] row : tasks.findActiveJiraChildSummaries(task.getJiraIntegration().getId(), task.getExternalId())) {
			com.saga.be.entity.enums.TaskStatus status = (com.saga.be.entity.enums.TaskStatus) row[2];
			children.add(new ProjectTaskResponse.Subtask(
					(UUID) row[0],
					(String) row[1],
					status == null ? null : status.name(),
					(String) row[3],
					(String) row[4],
					TaskIssueTypePolicy.apiValue((String) row[5])));
		}
		return children;
	}

	private ProjectSprintResponse toSprint(Sprint sprint) {
		return new ProjectSprintResponse(
				sprint.getId(),
				sprint.getExternalSprintId(),
				sprint.getName(),
				sprint.getState(),
				sprint.getGoal(),
				sprint.getStartDate(),
				sprint.getEndDate(),
				sprint.getCompleteDate(),
				toSource(sprint.getJiraIntegration()));
	}

	private ProjectSprintResponse.Source toSource(com.saga.be.entity.jira.JiraIntegration integration) {
		if (integration == null) {
			return null;
		}
		return new ProjectSprintResponse.Source(
				integration.getId(),
				integration.getSiteName(),
				integration.getProjectKey(),
				integration.getJiraBoardId(),
				integration.getConnectionStatus() == null ? null : integration.getConnectionStatus().name());
	}

	private static String authorAvatarUrl(GitCommit commit) {
		if (commit.getAuthorStudent() == null || commit.getAuthorStudent().getUserAccount() == null) {
			return null;
		}
		return commit.getAuthorStudent().getUserAccount().getAvatarUrl();
	}

	private ProjectCommitResponse toCommit(GitCommit commit) {
		return new ProjectCommitResponse(
				commit.getId(),
				commit.getRepo() == null ? null : commit.getRepo().getId(),
				commit.getRepo() == null ? null : commit.getRepo().getFullName(),
				commit.getShaHash(),
				commit.getMessage(),
				commit.getAuthorExternalId(),
				commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getId(),
				authorAvatarUrl(commit),
				commit.getHeadRef(),
				commit.getCommittedAt(),
				commit.getCreatedAt(),
				commit.getParentCount(),
				commit.isMerge());
	}
}

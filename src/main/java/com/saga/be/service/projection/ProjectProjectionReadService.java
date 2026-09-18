package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitPageResponse;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.ProjectTaskSprintResponse;
import com.saga.be.dto.project.TaskParentOptionsResponse;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
	private final SprintRepository sprints;
	private final ProjectDataAuthorization authorization;
	private final TaskHierarchyService hierarchy;

	public ProjectProjectionReadService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			SprintRepository sprints,
			ProjectDataAuthorization authorization,
			TaskHierarchyService hierarchy) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.sprints = sprints;
		this.authorization = authorization;
		this.hierarchy = hierarchy;
	}

	@Transactional(readOnly = true)
	public List<ProjectTaskResponse> listTasks(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		List<Task> rows = tasks.findActiveFetchedByProject_Id(projectId);
		Map<UUID, Long> counts = linkCounts(projectId);
		return rows.stream().map(task -> toTask(task, counts.getOrDefault(task.getId(), 0L))).toList();
	}

	@Transactional(readOnly = true)
	public ProjectTaskResponse getTask(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireReader(userId, projectId);
		Task task = tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
		Map<UUID, Long> counts = linkCounts(projectId);
		return toTask(task, counts.getOrDefault(task.getId(), 0L), directSubtasks(task.getId()));
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
		return sprints.findActiveByProject_Id(projectId).stream().map(this::toSprint).toList();
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
		authorization.requireReader(userId, projectId);
		int pageNumber = page == null ? DEFAULT_PAGE : page;
		int pageSize = size == null ? DEFAULT_SIZE : size;
		if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
		}
		Page<UUID> idPage = commits.findPageIdsByProject(projectId, PageRequest.of(pageNumber, pageSize));
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
		Page<UUID> idPage = links.findPageIdsByProjectAndTask(projectId, taskId, PageRequest.of(pageNumber, pageSize));
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
		Map<UUID, Long> counts = new HashMap<>();
		for (Object[] row : links.countLinksByProjectGrouped(projectId)) {
			counts.put((UUID) row[0], (Long) row[1]);
		}
		return counts;
	}

	static ProjectTaskResponse toTask(Task task, long linkedCommitCount) {
		return toTask(task, linkedCommitCount, null);
	}

	static ProjectTaskResponse toTask(
			Task task, long linkedCommitCount, List<ProjectTaskResponse.Subtask> subtasks) {
		String assigneeDisplay = null;
		if (task.getAssigneeStudent() != null
				&& task.getAssigneeStudent().getUserAccount() != null
				&& task.getAssigneeStudent().getUserAccount().getFullName() != null
				&& !task.getAssigneeStudent().getUserAccount().getFullName().isBlank()) {
			assigneeDisplay = task.getAssigneeStudent().getUserAccount().getFullName();
		}
		UUID studentId = task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getId();
		ProjectTaskResponse.Assignee assignee = null;
		if (task.getAssigneeExternalId() != null || assigneeDisplay != null || studentId != null) {
			assignee = new ProjectTaskResponse.Assignee(task.getAssigneeExternalId(), assigneeDisplay, studentId);
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
		// Jira's own parent identity, echoed verbatim -- never a local Task lookup/join. A parent
		// SAGA hasn't synced yet (or never will) still surfaces its true provider identity here.
		ProjectTaskResponse.Parent parent = task.getParentExternalId() == null
				? null
				: new ProjectTaskResponse.Parent(task.getParentExternalId(), task.getParentExternalKey());
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
				task.getExternalUpdatedAt(),
				task.getCreatedAt(),
				task.getUpdatedAt(),
				parentTask,
				subtasks);
	}

	private List<ProjectTaskResponse.Subtask> directSubtasks(UUID parentId) {
		List<ProjectTaskResponse.Subtask> children = new java.util.ArrayList<>();
		for (Object[] row : tasks.findActiveDirectChildSummaries(parentId)) {
			com.saga.be.entity.enums.TaskStatus status = (com.saga.be.entity.enums.TaskStatus) row[2];
			children.add(new ProjectTaskResponse.Subtask(
					(UUID) row[0], (String) row[1], status == null ? null : status.name()));
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
				sprint.getCompleteDate());
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
				commit.getHeadRef(),
				commit.getCommittedAt(),
				commit.getCreatedAt(),
				commit.getParentCount(),
				commit.isMerge());
	}
}

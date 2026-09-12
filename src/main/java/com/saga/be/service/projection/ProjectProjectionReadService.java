package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectSprintResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.ProjectTaskSprintResponse;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectProjectionReadService {

	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final SprintRepository sprints;
	private final ProjectDataAuthorization authorization;

	public ProjectProjectionReadService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			SprintRepository sprints,
			ProjectDataAuthorization authorization) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.sprints = sprints;
		this.authorization = authorization;
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
		return toTask(task, counts.getOrDefault(task.getId(), 0L));
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
	public List<ProjectCommitResponse> listCommits(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		return commits.findFetchedByProject_Id(projectId).stream().map(this::toCommit).toList();
	}

	@Transactional(readOnly = true)
	public List<ProjectCommitResponse> listTaskCommits(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireReader(userId, projectId);
		tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
		return links.findFetchedCommitsByProjectAndTask(projectId, taskId).stream().map(this::toCommit).toList();
	}

	private Map<UUID, Long> linkCounts(UUID projectId) {
		Map<UUID, Long> counts = new HashMap<>();
		for (Object[] row : links.countLinksByProjectGrouped(projectId)) {
			counts.put((UUID) row[0], (Long) row[1]);
		}
		return counts;
	}

	static ProjectTaskResponse toTask(Task task, long linkedCommitCount) {
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
				linkedCommitCount,
				task.getExternalUpdatedAt(),
				task.getCreatedAt(),
				task.getUpdatedAt());
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
				commit.getCreatedAt());
	}
}

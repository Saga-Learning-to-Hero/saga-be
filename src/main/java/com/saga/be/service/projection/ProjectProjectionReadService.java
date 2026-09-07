package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
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
	private final ProjectDataAuthorization authorization;

	public ProjectProjectionReadService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			ProjectDataAuthorization authorization) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
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

	private ProjectTaskResponse toTask(Task task, long linkedCommitCount) {
		return new ProjectTaskResponse(
				task.getId(),
				task.getExternalId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus() == null ? null : task.getStatus().name(),
				task.getIssueTypeName(),
				task.getAssigneeExternalId(),
				task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getId(),
				linkedCommitCount,
				task.getExternalUpdatedAt(),
				task.getCreatedAt(),
				task.getUpdatedAt());
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
				commit.getCommittedAt(),
				commit.getCreatedAt());
	}
}

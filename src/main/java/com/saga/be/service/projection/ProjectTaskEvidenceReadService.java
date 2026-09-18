package com.saga.be.service.projection;

import com.saga.be.dto.project.TaskEvidenceGroup;
import com.saga.be.dto.project.TaskEvidenceGroupedResponse;
import com.saga.be.dto.project.TaskEvidenceItem;
import com.saga.be.dto.project.TaskEvidencePageResponse;
import com.saga.be.dto.project.TaskEvidenceResponse;
import com.saga.be.dto.project.TaskEvidenceType;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectTaskEvidenceReadService {

	static final int DEFAULT_SIZE = 20;
	static final int MAX_SIZE = 50;

	private final ProjectDataAuthorization authorization;
	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository commitLinks;
	private final GitCommitRepository commits;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;

	public ProjectTaskEvidenceReadService(
			ProjectDataAuthorization authorization,
			TaskRepository tasks,
			TaskGitCommitLinkRepository commitLinks,
			GitCommitRepository commits,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks) {
		this.authorization = authorization;
		this.tasks = tasks;
		this.commitLinks = commitLinks;
		this.commits = commits;
		this.files = files;
		this.webLinks = webLinks;
	}

	@Transactional(readOnly = true)
	public TaskEvidenceResponse list(
			UUID userId, UUID projectId, UUID taskId, String type, Integer page, Integer size) {
		authorization.requireReader(userId, projectId);
		int pageSize = resolveSize(size);
		int pageIndex = page == null ? 0 : page;
		if (pageIndex < 0) {
			throw invalid("page must be 0 or greater.");
		}
		TaskEvidenceType evidenceType = parseOptionalType(type);
		if (evidenceType == null && pageIndex != 0) {
			throw invalid("Grouped evidence preview only supports page=0.");
		}
		requireActiveTask(projectId, taskId);
		if (evidenceType == null) {
			return grouped(taskId, pageSize);
		}
		return paged(taskId, evidenceType, pageIndex, pageSize);
	}

	private TaskEvidenceGroupedResponse grouped(UUID taskId, int size) {
		Page<TaskEvidenceItem> commitPage = commitPage(taskId, 0, size);
		Page<TaskEvidenceItem> filePage = filePage(taskId, 0, size);
		Page<TaskEvidenceItem> linkPage = webLinkPage(taskId, 0, size);
		return new TaskEvidenceGroupedResponse(
				taskId,
				new TaskEvidenceGroupedResponse.Groups(
						new TaskEvidenceGroup(commitPage.getTotalElements(), commitPage.getContent()),
						new TaskEvidenceGroup(filePage.getTotalElements(), filePage.getContent()),
						new TaskEvidenceGroup(linkPage.getTotalElements(), linkPage.getContent())));
	}

	private TaskEvidencePageResponse paged(UUID taskId, TaskEvidenceType type, int page, int size) {
		Page<TaskEvidenceItem> result =
				switch (type) {
					case COMMIT -> commitPage(taskId, page, size);
					case FILE -> filePage(taskId, page, size);
					case WEB_LINK -> webLinkPage(taskId, page, size);
				};
		return new TaskEvidencePageResponse(taskId, type, page, size, result.getTotalElements(), result.getContent());
	}

	private Page<TaskEvidenceItem> commitPage(UUID taskId, int page, int size) {
		Page<UUID> ids = commitLinks.findLinkedCommitIdsByTaskId(taskId, PageRequest.of(page, size));
		List<UUID> ordered = ids.getContent();
		if (ordered.isEmpty()) {
			return new org.springframework.data.domain.PageImpl<>(List.of(), ids.getPageable(), ids.getTotalElements());
		}
		Map<UUID, GitCommit> byId = new HashMap<>();
		for (GitCommit commit : commits.findFetchedByIdIn(ordered)) {
			byId.put(commit.getId(), commit);
		}
		List<TaskEvidenceItem> items = new ArrayList<>(ordered.size());
		for (UUID id : ordered) {
			GitCommit commit = byId.get(id);
			if (commit != null) {
				items.add(toCommitItem(commit));
			}
		}
		return new org.springframework.data.domain.PageImpl<>(items, ids.getPageable(), ids.getTotalElements());
	}

	private Page<TaskEvidenceItem> filePage(UUID taskId, int page, int size) {
		Page<TaskFile> rows = files.findByTask_Id(
				taskId,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
		return rows.map(row -> toFileItem(taskId, row));
	}

	private Page<TaskEvidenceItem> webLinkPage(UUID taskId, int page, int size) {
		Page<TaskWebLink> rows = webLinks.findByTask_Id(
				taskId,
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"))));
		return rows.map(ProjectTaskEvidenceReadService::toWebLinkItem);
	}

	private void requireActiveTask(UUID projectId, UUID taskId) {
		tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Task was not found for this project."));
	}

	private static int resolveSize(Integer size) {
		int resolved = size == null ? DEFAULT_SIZE : size;
		if (resolved < 1 || resolved > MAX_SIZE) {
			throw invalid("size must be between 1 and 50.");
		}
		return resolved;
	}

	private static TaskEvidenceType parseOptionalType(String type) {
		if (type == null || type.isBlank()) {
			return null;
		}
		try {
			return TaskEvidenceType.valueOf(type.trim());
		} catch (IllegalArgumentException ex) {
			throw invalid("type must be COMMIT, FILE, or WEB_LINK.");
		}
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static TaskEvidenceItem toCommitItem(GitCommit commit) {
		String sha = commit.getShaHash();
		String shortSha = shortSha(sha);
		String title = commit.getMessage() == null || commit.getMessage().isBlank() ? shortSha : commit.getMessage();
		LocalDateTime createdAt = commit.getCommittedAt() != null ? commit.getCommittedAt() : commit.getCreatedAt();
		String repoName = commit.getRepo() == null ? null : commit.getRepo().getFullName();
		return TaskEvidenceItem.commit(
				commit.getId(),
				title,
				createdAt,
				new TaskEvidenceItem.CommitEvidence(
						commit.getId(), sha, shortSha, commit.getMessage(), commit.getCommittedAt(), repoName));
	}

	private static TaskEvidenceItem toFileItem(UUID taskId, TaskFile row) {
		String source = row.getSource() == null ? null : row.getSource().name();
		return TaskEvidenceItem.file(
				row.getId(),
				row.getOriginalFilename(),
				row.getCreatedAt(),
				source,
				new TaskEvidenceItem.FileEvidence(
						row.getOriginalFilename(),
						row.getMimeType(),
						row.getSizeBytes(),
						"/api/tasks/" + taskId + "/files/" + row.getId()));
	}

	private static TaskEvidenceItem toWebLinkItem(TaskWebLink row) {
		String source = row.getSource() == null ? null : row.getSource().name();
		return TaskEvidenceItem.webLink(
				row.getId(),
				row.getTitle(),
				row.getCreatedAt(),
				source,
				new TaskEvidenceItem.WebLinkEvidence(row.getUrl(), row.getTitle()));
	}

	private static String shortSha(String sha) {
		if (sha == null || sha.isBlank()) {
			return sha;
		}
		return sha.length() <= 7 ? sha : sha.substring(0, 7);
	}
}

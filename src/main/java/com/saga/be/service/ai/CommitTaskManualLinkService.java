package com.saga.be.service.ai;

import com.saga.be.dto.ai.CommitAiReviewDtos.Detail;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Attach a commit to a task by hand when its message forgot the Jira key. Only the commit's author
 * or the team leader may do it, never on a merge commit, only to a task of the same project.
 *
 * <p>Display and review context only: the row lives in {@code task_commit_manual_link}, which no
 * contribution, evidence or dashboard query reads, so it never changes anyone's score.
 */
@Service
@Profile("!test")
public class CommitTaskManualLinkService {

	private final ProjectDataAuthorization authorization;
	private final GitCommitRepository commits;
	private final TaskRepository tasks;
	private final TaskCommitManualLinkRepository manualLinks;
	private final TaskGitCommitLinkRepository links;
	private final UserAccountRepository users;
	private final CommitAiReviewService reviews;

	public CommitTaskManualLinkService(
			ProjectDataAuthorization authorization,
			GitCommitRepository commits,
			TaskRepository tasks,
			TaskCommitManualLinkRepository manualLinks,
			TaskGitCommitLinkRepository links,
			UserAccountRepository users,
			CommitAiReviewService reviews) {
		this.authorization = authorization;
		this.commits = commits;
		this.tasks = tasks;
		this.manualLinks = manualLinks;
		this.links = links;
		this.users = users;
		this.reviews = reviews;
	}

	public Detail link(UUID userId, UUID projectId, UUID commitId, UUID taskId) {
		GitCommit commit = requireManageable(userId, projectId, commitId);
		if (taskId == null) throw invalid("Hãy chọn task để gắn.");
		Task task = tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> invalid("Task không thuộc dự án này hoặc đã bị xoá."));
		boolean alreadyAutomatic = links.findLiveWithTaskByGitCommitIds(List.of(commitId)).stream()
				.anyMatch(link -> link.getTask().getId().equals(task.getId()));
		if (!alreadyAutomatic && manualLinks.findByTask_IdAndGitCommit_Id(task.getId(), commitId).isEmpty()) {
			TaskCommitManualLink row = new TaskCommitManualLink();
			row.setProject(commit.getRepo().getProject());
			row.setTask(task);
			row.setGitCommit(commit);
			row.setCreatedBy(users.getReferenceById(userId));
			try {
				manualLinks.saveAndFlush(row);
			} catch (DataIntegrityViolationException ex) {
				// A double click inserted it first: same outcome.
			}
		}
		return reviews.detail(userId, projectId, commitId);
	}

	/** Only manual attachments can be removed; an automatic link comes from the commit itself. */
	public Detail unlink(UUID userId, UUID projectId, UUID commitId, UUID taskId) {
		requireManageable(userId, projectId, commitId);
		manualLinks.findByTask_IdAndGitCommit_Id(taskId, commitId)
				.filter(row -> row.getProject().getId().equals(projectId))
				.ifPresent(manualLinks::delete);
		return reviews.detail(userId, projectId, commitId);
	}

	private GitCommit requireManageable(UUID userId, UUID projectId, UUID commitId) {
		authorization.requireStudentTeamMember(userId, projectId);
		GitCommit commit = commits.findFetchedByIdIn(List.of(commitId)).stream().findFirst().orElse(null);
		if (commit == null || commit.getRepo() == null || commit.getRepo().getProject() == null || !projectId.equals(commit.getRepo().getProject().getId())) {
			throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, "Không tìm thấy commit trong dự án này.");
		}
		if (commit.looksLikeMerge()) throw invalid("Merge commit chỉ gộp code đã có nên không cần gắn task.");
		if (!reviews.canManageLinks(userId, projectId, commit)) {
			throw new IntegrationException(IntegrationErrorCode.COMMIT_TASK_LINK_FORBIDDEN, HttpStatus.FORBIDDEN,
					"Chỉ tác giả của commit hoặc trưởng nhóm mới được gắn task cho commit này.");
		}
		return commit;
	}

	private static IntegrationException invalid(String message) {
		return new IntegrationException(IntegrationErrorCode.COMMIT_TASK_LINK_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}

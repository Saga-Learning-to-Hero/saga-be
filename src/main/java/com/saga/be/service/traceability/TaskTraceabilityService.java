package com.saga.be.service.traceability;

import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.PullRequest;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.entity.traceability.TaskPullRequestLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class TaskTraceabilityService {

	public interface Store {
		Optional<Task> findByProjectAndKey(UUID projectId, String jiraKey);

		boolean commitLinked(UUID taskId, UUID commitId);

		boolean pullRequestLinked(UUID taskId, UUID pullRequestId);

		TaskGitCommitLink saveCommitLink(TaskGitCommitLink link);

		TaskPullRequestLink savePullRequestLink(TaskPullRequestLink link);
	}

	private final Store store;

	public TaskTraceabilityService(Store store) {
		this.store = store;
	}

	public List<TaskGitCommitLink> linkCommit(UUID projectId, GitCommit commit, String branchName) {
		Set<String> keys = JiraKeyExtractor.extract(commit.getMessage(), branchName, commit.getHeadRef());
		List<TaskGitCommitLink> created = new ArrayList<>();
		for (String key : keys) {
			store.findByProjectAndKey(projectId, key).ifPresent(task -> {
				if (store.commitLinked(task.getId(), commit.getId())) {
					return;
				}
				TraceLinkSource source = sourceForCommit(commit.getMessage(), branchName, key);
				TaskGitCommitLink link = new TaskGitCommitLink();
				link.setTask(task);
				link.setGitCommit(commit);
				link.setLinkSource(source);
				link.setJiraKeySnapshot(key);
				link.setConfidence("HIGH");
				created.add(store.saveCommitLink(link));
			});
		}
		return created;
	}

	public List<TaskPullRequestLink> linkPullRequest(UUID projectId, PullRequest pullRequest) {
		Set<String> keys =
				JiraKeyExtractor.extract(pullRequest.getTitle(), pullRequest.getBody(), pullRequest.getHeadRef());
		List<TaskPullRequestLink> created = new ArrayList<>();
		for (String key : keys) {
			store.findByProjectAndKey(projectId, key).ifPresent(task -> {
				if (store.pullRequestLinked(task.getId(), pullRequest.getId())) {
					return;
				}
				TraceLinkSource source = sourceForPullRequest(pullRequest, key);
				TaskPullRequestLink link = new TaskPullRequestLink();
				link.setTask(task);
				link.setPullRequest(pullRequest);
				link.setLinkSource(source);
				link.setJiraKeySnapshot(key);
				link.setConfidence("HIGH");
				created.add(store.savePullRequestLink(link));
			});
		}
		return created;
	}

	private static TraceLinkSource sourceForCommit(String message, String branch, String key) {
		if (contains(message, key)) {
			return TraceLinkSource.COMMIT_MESSAGE;
		}
		if (contains(branch, key)) {
			return TraceLinkSource.BRANCH_NAME;
		}
		return TraceLinkSource.RECONCILIATION;
	}

	private static TraceLinkSource sourceForPullRequest(PullRequest pullRequest, String key) {
		if (contains(pullRequest.getTitle(), key)) {
			return TraceLinkSource.PR_TITLE;
		}
		if (contains(pullRequest.getBody(), key)) {
			return TraceLinkSource.PR_BODY;
		}
		if (contains(pullRequest.getHeadRef(), key)) {
			return TraceLinkSource.BRANCH_NAME;
		}
		return TraceLinkSource.RECONCILIATION;
	}

	private static boolean contains(String text, String key) {
		return text != null && text.toUpperCase(Locale.ROOT).contains(key);
	}
}

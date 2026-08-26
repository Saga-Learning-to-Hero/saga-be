package com.saga.be.service.traceability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.PullRequest;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.entity.traceability.TaskPullRequestLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TaskTraceabilityServiceTest {

	@Test
	void commitMessageBranchAndPrTitleLinkWithoutGitHubIssue() {
		UUID projectId = UUID.randomUUID();
		Task task = task(projectId, "SAGA-123");
		Memory store = new Memory(task);
		TaskTraceabilityService service = new TaskTraceabilityService(store);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("fix SAGA-123 login");
		commit.setHeadRef("feature/SAGA-123-login");
		List<TaskGitCommitLink> commitLinks = service.linkCommit(projectId, commit, "feature/SAGA-123-login");
		assertEquals(1, commitLinks.size());
		assertEquals(TraceLinkSource.COMMIT_MESSAGE, commitLinks.getFirst().getLinkSource());
		PullRequest pr = new PullRequest();
		pr.setId(UUID.randomUUID());
		pr.setTitle("SAGA-123 login form");
		pr.setBody("closes nothing on github issues");
		pr.setHeadRef("feature/other");
		List<TaskPullRequestLink> prLinks = service.linkPullRequest(projectId, pr);
		assertEquals(1, prLinks.size());
		assertEquals(TraceLinkSource.PR_TITLE, prLinks.getFirst().getLinkSource());
		assertTrue(service.linkCommit(projectId, commit, "feature/SAGA-123-login").isEmpty());
	}

	@Test
	void unrelatedKeyDoesNotLink() {
		UUID projectId = UUID.randomUUID();
		Memory store = new Memory(task(projectId, "SAGA-123"));
		TaskTraceabilityService service = new TaskTraceabilityService(store);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("fix AUTH-9 something else");
		assertTrue(service.linkCommit(projectId, commit, "feature/unrelated").isEmpty());
	}

	@Test
	void branchNameLinksWhenMessageHasNoKey() {
		UUID projectId = UUID.randomUUID();
		Memory store = new Memory(task(projectId, "SAGA-88"));
		TaskTraceabilityService service = new TaskTraceabilityService(store);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("wip");
		assertEquals(TraceLinkSource.BRANCH_NAME, service.linkCommit(projectId, commit, "bugfix/SAGA-88").getFirst().getLinkSource());
	}

	private static Task task(UUID projectId, String key) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		return task;
	}

	private static final class Memory implements TaskTraceabilityService.Store {
		private final Task task;
		private final List<String> commitPairs = new ArrayList<>();
		private final List<String> prPairs = new ArrayList<>();

		private Memory(Task task) {
			this.task = task;
		}

		@Override
		public Optional<Task> findByProjectAndKey(UUID projectId, String jiraKey) {
			return task.getExternalKey().equals(jiraKey) ? Optional.of(task) : Optional.empty();
		}

		@Override
		public boolean commitLinked(UUID taskId, UUID commitId) {
			return commitPairs.contains(taskId + ":" + commitId);
		}

		@Override
		public boolean pullRequestLinked(UUID taskId, UUID pullRequestId) {
			return prPairs.contains(taskId + ":" + pullRequestId);
		}

		@Override
		public TaskGitCommitLink saveCommitLink(TaskGitCommitLink link) {
			commitPairs.add(link.getTask().getId() + ":" + link.getGitCommit().getId());
			return link;
		}

		@Override
		public TaskPullRequestLink savePullRequestLink(TaskPullRequestLink link) {
			prPairs.add(link.getTask().getId() + ":" + link.getPullRequest().getId());
			return link;
		}
	}
}

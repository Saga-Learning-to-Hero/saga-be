package com.saga.be.service.projection;

import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.traceability.JiraKeyExtractor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class CommitTaskAutoLinkService {

	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository links;
	private final CommitMessageCandidateQuery candidates;

	public CommitTaskAutoLinkService(
			TaskRepository tasks, TaskGitCommitLinkRepository links, CommitMessageCandidateQuery candidates) {
		this.tasks = tasks;
		this.links = links;
		this.candidates = candidates;
	}

	@Transactional
	public int linkCommits(UUID projectId, String jiraProjectKey, List<GitCommit> commits) {
		if (projectId == null || commits == null || commits.isEmpty()) {
			return 0;
		}
		Map<UUID, Set<String>> keysByCommit = new HashMap<>();
		Set<String> allKeys = new HashSet<>();
		for (GitCommit commit : commits) {
			if (commit == null || commit.getId() == null) {
				continue;
			}
			Set<String> keys = JiraKeyExtractor.extractForProject(
					jiraProjectKey, commit.getMessage(), commit.getHeadRef());
			if (!keys.isEmpty()) {
				keysByCommit.put(commit.getId(), keys);
				allKeys.addAll(keys);
			}
		}
		if (allKeys.isEmpty()) {
			return 0;
		}
		return createMissingLinks(projectId, keysByCommit, allKeys, commits);
	}

	/**
	 * Commit-first / task-later reconciliation: for newly projected tasks, find project commits
	 * whose message/headRef contain those keys (bounded query, not full-table scan).
	 */
	@Transactional
	public int linkTasks(UUID projectId, String jiraProjectKey, List<Task> projectedTasks) {
		if (projectId == null || projectedTasks == null || projectedTasks.isEmpty()) {
			return 0;
		}
		Set<String> keys = new HashSet<>();
		for (Task task : projectedTasks) {
			if (task == null || task.getId() == null || task.getDeletedAt() != null) {
				continue;
			}
			String key = task.getExternalKey();
			if (key == null || key.isBlank()) {
				continue;
			}
			String normalized = key.toUpperCase(Locale.ROOT);
			if (jiraProjectKey != null
					&& !jiraProjectKey.isBlank()
					&& !normalized.startsWith(jiraProjectKey.trim().toUpperCase(Locale.ROOT) + "-")) {
				continue;
			}
			keys.add(normalized);
		}
		if (keys.isEmpty()) {
			return 0;
		}
		List<GitCommit> matching = candidates.findByProjectAndKeys(projectId, keys);
		return linkCommits(projectId, jiraProjectKey, matching);
	}

	private int createMissingLinks(
			UUID projectId, Map<UUID, Set<String>> keysByCommit, Set<String> allKeys, List<GitCommit> commits) {
		Map<String, Task> tasksByKey = tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(projectId, allKeys).stream()
				.filter(task -> task.getDeletedAt() == null)
				.collect(Collectors.toMap(
						task -> task.getExternalKey().toUpperCase(Locale.ROOT), Function.identity(), (a, b) -> a));
		Set<UUID> commitIds = keysByCommit.keySet();
		Set<String> existingPairs = links.findByGitCommit_IdIn(commitIds).stream()
				.map(link -> link.getTask().getId() + ":" + link.getGitCommit().getId())
				.collect(Collectors.toCollection(HashSet::new));
		Map<UUID, GitCommit> commitsById = commits.stream()
				.filter(c -> c.getId() != null)
				.collect(Collectors.toMap(GitCommit::getId, Function.identity(), (a, b) -> a));
		List<TaskGitCommitLink> created = new ArrayList<>();
		for (Map.Entry<UUID, Set<String>> entry : keysByCommit.entrySet()) {
			GitCommit commit = commitsById.get(entry.getKey());
			if (commit == null) {
				continue;
			}
			for (String key : entry.getValue()) {
				Task task = tasksByKey.get(key);
				if (task == null) {
					continue;
				}
				String pair = task.getId() + ":" + commit.getId();
				if (!existingPairs.add(pair)) {
					continue;
				}
				TaskGitCommitLink link = new TaskGitCommitLink();
				link.setTask(task);
				link.setGitCommit(commit);
				link.setLinkSource(sourceFor(commit.getMessage(), key));
				link.setJiraKeySnapshot(key);
				link.setConfidence("HIGH");
				created.add(link);
			}
		}
		if (!created.isEmpty()) {
			links.saveAll(created);
		}
		return created.size();
	}

	private static TraceLinkSource sourceFor(String message, String key) {
		if (message != null && message.toUpperCase(Locale.ROOT).contains(key)) {
			return TraceLinkSource.COMMIT_MESSAGE;
		}
		return TraceLinkSource.RECONCILIATION;
	}
}

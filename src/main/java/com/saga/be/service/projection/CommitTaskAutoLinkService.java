package com.saga.be.service.projection;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
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
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional commit -> Jira task attribution. Never a precondition for persisting a commit: a key
 * that cannot be attributed to exactly one ACTIVE Jira source and exactly one live task of that
 * source is skipped, never guessed.
 *
 * <p>Source selection: a key {@code SAGA-123} is only a candidate for the project's ACTIVE Jira
 * sources whose {@code projectKey} is {@code SAGA}. REVOKED sources are never candidates for new
 * links (existing links are left untouched). Two ACTIVE sources may share a projectKey when they
 * live on different Jira sites; such a key is ambiguous and is not linked. The task is then looked
 * up within that source only ({@code task.jira_integration_id}), so the same key under two sources
 * can never cross-link.
 *
 * <p>One commit, one task: see {@link #linkCommits}.
 */
@Service
@Profile("!test")
public class CommitTaskAutoLinkService {

	private static final Logger log = LoggerFactory.getLogger(CommitTaskAutoLinkService.class);

	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository links;
	private final CommitMessageCandidateQuery candidates;
	private final JiraIntegrationRepository jiraIntegrations;
	private TaskCommitManualLinkRepository manualLinks;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setManualLinks(TaskCommitManualLinkRepository manualLinks) {
		this.manualLinks = manualLinks;
	}

	public CommitTaskAutoLinkService(
			TaskRepository tasks,
			TaskGitCommitLinkRepository links,
			CommitMessageCandidateQuery candidates,
			JiraIntegrationRepository jiraIntegrations) {
		this.tasks = tasks;
		this.links = links;
		this.candidates = candidates;
		this.jiraIntegrations = jiraIntegrations;
	}

	/**
	 * One commit belongs to one task (a task may have many commits): the first Jira key written in the
	 * commit message that resolves to exactly one live task. The branch name never links a commit: a
	 * branch like {@code feat/SAGA-119-x} also carries commits merged in from main. Links the message
	 * does not support (another task, a key only in the branch name, any link of a merge commit) are
	 * removed, and so is a hand-made attachment once the message names the task.
	 *
	 * <p>Bounded per batch: one ACTIVE-source query, one task query, one existing-link query, at most
	 * one delete of each kind.
	 */
	@Transactional
	public int linkCommits(UUID projectId, List<GitCommit> commits) {
		if (projectId == null || commits == null || commits.isEmpty()) {
			return 0;
		}
		Map<UUID, Set<String>> rawKeysByCommit = new HashMap<>();
		Set<UUID> merges = new HashSet<>();
		Set<UUID> batch = new HashSet<>();
		for (GitCommit commit : commits) {
			if (commit == null || commit.getId() == null) {
				continue;
			}
			batch.add(commit.getId());
			if (commit.looksLikeMerge()) {
				merges.add(commit.getId());
				continue;
			}
			// keys in the order written in the message; never the branch name
			Set<String> keys = JiraKeyExtractor.extract(commit.getMessage());
			if (!keys.isEmpty()) {
				rawKeysByCommit.put(commit.getId(), keys);
			}
		}
		if (batch.isEmpty()) {
			return 0;
		}
		Map<UUID, List<SourceKey>> keysByCommit = new HashMap<>();
		if (!rawKeysByCommit.isEmpty()) {
			Map<String, List<UUID>> sourcesByProjectKey = activeSourcesByProjectKey(projectId);
			Set<String> ambiguousPrefixes = new TreeSet<>();
			for (Map.Entry<UUID, Set<String>> entry : rawKeysByCommit.entrySet()) {
				List<SourceKey> resolved = new ArrayList<>();
				for (String key : entry.getValue()) {
					List<UUID> sources = sourcesByProjectKey.get(prefixOf(key));
					if (sources == null) {
						continue; // not a key of any ACTIVE source of this project
					}
					if (sources.size() > 1) {
						ambiguousPrefixes.add(prefixOf(key));
						continue;
					}
					resolved.add(new SourceKey(sources.get(0), key));
				}
				if (!resolved.isEmpty()) {
					keysByCommit.put(entry.getKey(), resolved);
				}
			}
			if (!ambiguousPrefixes.isEmpty()) {
				log.warn(
						"commit task auto-link skipped ambiguous Jira keys projectId={} projectKeys={} reason=MULTIPLE_ACTIVE_SOURCES_SHARE_PROJECT_KEY",
						projectId,
						ambiguousPrefixes);
			}
		}
		return applyLinks(projectId, chooseTasks(projectId, keysByCommit), merges, rawKeysByCommit, batch, commits);
	}

	/**
	 * Commit-first / task-later reconciliation: LIKE-scan project commits whose message/headRef
	 * contain the given task keys (bounded to {@code MAX_CANDIDATE_COMMITS}). Callers should pass
	 * only newly created tasks or tasks whose externalKey changed — not ordinary metadata updates.
	 * Candidate commits then go through the same source-aware resolution as {@link #linkCommits}.
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
		return linkCommits(projectId, matching);
	}

	private Map<String, List<UUID>> activeSourcesByProjectKey(UUID projectId) {
		Map<String, List<UUID>> byKey = new HashMap<>();
		for (JiraIntegration source : jiraIntegrations.findAllByProject_IdAndConnectionStatus(projectId, IntegrationStatus.ACTIVE)) {
			String projectKey = source.getProjectKey();
			if (source.getId() == null || projectKey == null || projectKey.isBlank()) {
				continue;
			}
			byKey.computeIfAbsent(projectKey.trim().toUpperCase(Locale.ROOT), ignored -> new ArrayList<>()).add(source.getId());
		}
		return byKey;
	}

	/** Per commit, the first key (in written order) that names exactly one live task of its source. */
	private Map<UUID, Choice> chooseTasks(UUID projectId, Map<UUID, List<SourceKey>> keysByCommit) {
		if (keysByCommit.isEmpty()) {
			return Map.of();
		}
		Set<UUID> sourceIds = new HashSet<>();
		Set<String> allKeys = new HashSet<>();
		keysByCommit.values().forEach(keys -> keys.forEach(k -> {
			sourceIds.add(k.sourceId());
			allKeys.add(k.key());
		}));
		Map<SourceKey, List<Task>> tasksBySourceKey = tasks
				.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(projectId, sourceIds, allKeys)
				.stream()
				.filter(task -> task.getDeletedAt() == null && task.getJiraIntegration() != null && task.getExternalKey() != null)
				.collect(Collectors.groupingBy(task -> new SourceKey(task.getJiraIntegration().getId(), task.getExternalKey().toUpperCase(Locale.ROOT))));
		Map<UUID, Choice> chosen = new HashMap<>();
		for (Map.Entry<UUID, List<SourceKey>> entry : keysByCommit.entrySet()) {
			for (SourceKey sourceKey : entry.getValue()) {
				List<Task> matches = tasksBySourceKey.getOrDefault(sourceKey, List.of());
				// None: unresolved key. Several live tasks with one key in one source: ambiguous, never guessed.
				if (matches.size() == 1) {
					chosen.put(entry.getKey(), new Choice(sourceKey.key(), matches.get(0)));
					break;
				}
			}
		}
		return chosen;
	}

	/**
	 * Keep exactly the chosen link of each commit: create it when missing; drop links to other tasks,
	 * links whose key is not in the message (e.g. added from a branch name) and every link of a merge
	 * commit. A link whose key is in the message but no longer resolves (a source revoked since) stays.
	 */
	private int applyLinks(
			UUID projectId,
			Map<UUID, Choice> chosen,
			Set<UUID> merges,
			Map<UUID, Set<String>> messageKeys,
			Set<UUID> batch,
			List<GitCommit> commits) {
		Set<UUID> alreadyLinked = new HashSet<>();
		List<TaskGitCommitLink> stale = new ArrayList<>();
		for (TaskGitCommitLink link : links.findByGitCommit_IdIn(batch)) {
			UUID commitId = link.getGitCommit().getId();
			Choice choice = chosen.get(commitId);
			if (choice != null) {
				if (choice.task().getId().equals(link.getTask().getId()) && alreadyLinked.add(commitId)) {
					continue;
				}
			} else if (!merges.contains(commitId) && keyInMessage(link, messageKeys.get(commitId))) {
				continue;
			}
			stale.add(link);
		}
		Map<UUID, GitCommit> commitsById = commits.stream()
				.filter(c -> c != null && c.getId() != null)
				.collect(Collectors.toMap(GitCommit::getId, Function.identity(), (a, b) -> a));
		List<TaskGitCommitLink> created = new ArrayList<>();
		for (Map.Entry<UUID, Choice> entry : chosen.entrySet()) {
			GitCommit commit = commitsById.get(entry.getKey());
			if (commit == null || alreadyLinked.contains(entry.getKey())) {
				continue;
			}
			TaskGitCommitLink link = new TaskGitCommitLink();
			link.setTask(entry.getValue().task());
			link.setGitCommit(commit);
			link.setLinkSource(sourceFor(commit.getMessage(), entry.getValue().key()));
			link.setJiraKeySnapshot(entry.getValue().key());
			link.setConfidence("HIGH");
			created.add(link);
		}
		if (!stale.isEmpty()) {
			links.deleteAllInBatch(stale);
			log.info("commit task auto-link removed extra links projectId={} count={}", projectId, stale.size());
		}
		if (!chosen.isEmpty() && manualLinks != null) {
			// the key in the commit now names its task: a hand-made attachment is no longer needed
			manualLinks.deleteByGitCommitIds(chosen.keySet());
		}
		if (!created.isEmpty()) {
			links.saveAll(created);
		}
		return created.size();
	}

	private static boolean keyInMessage(TaskGitCommitLink link, Set<String> messageKeys) {
		if (messageKeys == null || messageKeys.isEmpty()) {
			return false;
		}
		String key = link.getTask() != null && link.getTask().getExternalKey() != null
				? link.getTask().getExternalKey()
				: link.getJiraKeySnapshot();
		return key != null && messageKeys.contains(key.toUpperCase(Locale.ROOT));
	}

	private static String prefixOf(String key) {
		int dash = key.lastIndexOf('-');
		return dash <= 0 ? key : key.substring(0, dash);
	}

	private static TraceLinkSource sourceFor(String message, String key) {
		if (message != null && message.toUpperCase(Locale.ROOT).contains(key)) {
			return TraceLinkSource.COMMIT_MESSAGE;
		}
		return TraceLinkSource.RECONCILIATION;
	}

	/** An issue key qualified by the Jira source it was attributed to. */
	private record SourceKey(UUID sourceId, String key) {}

	/** The task a commit belongs to and the key that named it. */
	private record Choice(String key, Task task) {}
}

package com.saga.be.service.projection;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.JiraIntegrationRepository;
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
 */
@Service
@Profile("!test")
public class CommitTaskAutoLinkService {

	private static final Logger log = LoggerFactory.getLogger(CommitTaskAutoLinkService.class);

	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository links;
	private final CommitMessageCandidateQuery candidates;
	private final JiraIntegrationRepository jiraIntegrations;

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

	/** Bounded per batch: one ACTIVE-source query, one task query, one existing-link query. */
	@Transactional
	public int linkCommits(UUID projectId, List<GitCommit> commits) {
		if (projectId == null || commits == null || commits.isEmpty()) {
			return 0;
		}
		Map<UUID, Set<String>> rawKeysByCommit = new HashMap<>();
		for (GitCommit commit : commits) {
			if (commit == null || commit.getId() == null) {
				continue;
			}
			Set<String> keys = JiraKeyExtractor.extract(commit.getMessage(), commit.getHeadRef());
			if (!keys.isEmpty()) {
				rawKeysByCommit.put(commit.getId(), keys);
			}
		}
		if (rawKeysByCommit.isEmpty()) {
			return 0;
		}
		Map<String, List<UUID>> sourcesByProjectKey = activeSourcesByProjectKey(projectId);
		if (sourcesByProjectKey.isEmpty()) {
			return 0;
		}
		Map<UUID, Set<SourceKey>> keysByCommit = new HashMap<>();
		Set<String> ambiguousPrefixes = new TreeSet<>();
		for (Map.Entry<UUID, Set<String>> entry : rawKeysByCommit.entrySet()) {
			Set<SourceKey> resolved = new HashSet<>();
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
		if (keysByCommit.isEmpty()) {
			return 0;
		}
		return createMissingLinks(projectId, keysByCommit, commits);
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

	private int createMissingLinks(UUID projectId, Map<UUID, Set<SourceKey>> keysByCommit, List<GitCommit> commits) {
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
		Set<UUID> commitIds = keysByCommit.keySet();
		Set<String> existingPairs = links.findByGitCommit_IdIn(commitIds).stream()
				.map(link -> link.getTask().getId() + ":" + link.getGitCommit().getId())
				.collect(Collectors.toCollection(HashSet::new));
		Map<UUID, GitCommit> commitsById = commits.stream()
				.filter(c -> c != null && c.getId() != null)
				.collect(Collectors.toMap(GitCommit::getId, Function.identity(), (a, b) -> a));
		List<TaskGitCommitLink> created = new ArrayList<>();
		for (Map.Entry<UUID, Set<SourceKey>> entry : keysByCommit.entrySet()) {
			GitCommit commit = commitsById.get(entry.getKey());
			if (commit == null) {
				continue;
			}
			for (SourceKey sourceKey : entry.getValue()) {
				List<Task> matches = tasksBySourceKey.getOrDefault(sourceKey, List.of());
				if (matches.size() != 1) {
					// None: unresolved key (no link). Several live tasks with one key in one source:
					// ambiguous, never guessed.
					continue;
				}
				Task task = matches.get(0);
				String pair = task.getId() + ":" + commit.getId();
				if (!existingPairs.add(pair)) {
					continue;
				}
				TaskGitCommitLink link = new TaskGitCommitLink();
				link.setTask(task);
				link.setGitCommit(commit);
				link.setLinkSource(sourceFor(commit.getMessage(), sourceKey.key()));
				link.setJiraKeySnapshot(sourceKey.key());
				link.setConfidence("HIGH");
				created.add(link);
			}
		}
		if (!created.isEmpty()) {
			links.saveAll(created);
		}
		return created.size();
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
}

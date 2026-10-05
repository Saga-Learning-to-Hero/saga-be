package com.saga.be.service.projection;

import com.saga.be.entity.github.GitCommit;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Brings links made before "one commit, one task" in line once at startup instead of waiting for the
 * nightly full GitHub sync: commits linked to several tasks, and merge commits with any link, go
 * through {@link CommitTaskAutoLinkService#linkCommits} again. Idempotent, so a restart finds
 * nothing left to do; off the startup thread and never fatal.
 */
@Component
@Profile("!test")
public class CommitTaskLinkCleanup {

	private static final Logger log = LoggerFactory.getLogger(CommitTaskLinkCleanup.class);
	static final int MAX_COMMITS = 5000;
	private static final int CHUNK = 200;

	private final TaskGitCommitLinkRepository links;
	private final GitCommitRepository commits;
	private final CommitTaskAutoLinkService autoLink;
	private final boolean enabled;

	public CommitTaskLinkCleanup(
			TaskGitCommitLinkRepository links,
			GitCommitRepository commits,
			CommitTaskAutoLinkService autoLink,
			@Value("${saga.links.one-task-cleanup.enabled:true}") boolean enabled) {
		this.links = links;
		this.commits = commits;
		this.autoLink = autoLink;
		this.enabled = enabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void onReady() {
		if (!enabled) return;
		Thread worker = new Thread(this::run, "commit-task-link-cleanup");
		worker.setDaemon(true);
		worker.start();
	}

	/** Number of extra links removed. */
	int run() {
		try {
			Set<UUID> ids = new LinkedHashSet<>(links.findCommitIdsWithSeveralTasks(PageRequest.of(0, MAX_COMMITS)));
			ids.addAll(links.findMergeCommitIdsWithLinks(PageRequest.of(0, MAX_COMMITS)));
			if (ids.isEmpty()) return 0;
			long before = links.count();
			List<UUID> all = new ArrayList<>(ids);
			for (int from = 0; from < all.size(); from += CHUNK) {
				Map<UUID, List<GitCommit>> byProject = new HashMap<>();
				for (GitCommit commit : commits.findFetchedByIdIn(all.subList(from, Math.min(all.size(), from + CHUNK)))) {
					if (commit.getRepo() == null || commit.getRepo().getProject() == null) continue;
					byProject.computeIfAbsent(commit.getRepo().getProject().getId(), ignored -> new ArrayList<>()).add(commit);
				}
				byProject.forEach(autoLink::linkCommits);
			}
			int removed = (int) Math.max(0, before - links.count());
			log.info("one commit one task cleanup commits={} linksRemoved={}", ids.size(), removed);
			return removed;
		} catch (RuntimeException ex) {
			log.warn("one commit one task cleanup skipped type={} message={}", ex.getClass().getSimpleName(), ex.getMessage());
			return 0;
		}
	}
}

package com.saga.be.service.projection;

import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitCommitBranch;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.repository.GitCommitBranchRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Replaces the complete branch-reachability snapshot for one {@link GitRepo} after a successful
 * FULL provider traversal <em>and</em> successful persistence of every canonical {@link GitCommit}
 * the snapshot requires. Must not be called from webhook/partial paths. Previous snapshot rows
 * remain canonical if this method throws or is never reached.
 */
@Service
@Profile("!test")
public class GitCommitBranchSnapshotService {

	private final GitRepoRepository repos;
	private final GitCommitRepository commits;
	private final GitCommitBranchRepository branches;

	public GitCommitBranchSnapshotService(
			GitRepoRepository repos, GitCommitRepository commits, GitCommitBranchRepository branches) {
		this.repos = repos;
		this.commits = commits;
		this.branches = branches;
	}

	@Transactional
	public void replaceSnapshot(GitRepo repo, Map<String, Set<String>> shaToBranches, LocalDateTime resolvedAt) {
		if (repo == null || repo.getId() == null) {
			return;
		}
		Map<String, Set<String>> memberships = shaToBranches == null ? Map.of() : shaToBranches;
		Set<String> requiredShas = requiredShas(memberships);
		Map<String, GitCommit> bySha = requiredShas.isEmpty()
				? Map.of()
				: commits.findByRepo_IdAndShaHashIn(repo.getId(), requiredShas).stream()
						.collect(Collectors.toMap(
								GitCommit::getShaHash, Function.identity(), (a, b) -> a, LinkedHashMap::new));
		if (bySha.size() != requiredShas.size()) {
			Set<String> missing = new LinkedHashSet<>(requiredShas);
			missing.removeAll(bySha.keySet());
			throw new IllegalStateException(
					"Refusing to replace git_commit_branch snapshot: canonical GitCommit rows missing for SHAs "
							+ missing);
		}
		branches.deleteByCommitRepoId(repo.getId());
		List<GitCommitBranch> rows = new ArrayList<>();
		for (Map.Entry<String, Set<String>> entry : memberships.entrySet()) {
			GitCommit commit = bySha.get(entry.getKey());
			if (commit == null || entry.getValue() == null) {
				continue;
			}
			for (String branchName : entry.getValue()) {
				if (branchName == null || branchName.isBlank()) {
					continue;
				}
				GitCommitBranch row = new GitCommitBranch();
				row.setCommit(commit);
				row.setBranchName(branchName);
				rows.add(row);
			}
		}
		if (!rows.isEmpty()) {
			branches.saveAll(rows);
		}
		repo.setBranchMembershipSyncedAt(resolvedAt);
		repos.save(repo);
	}

	/**
	 * Incremental sync only: adds the branch memberships seen on the pages it walked, never deletes
	 * (a partial walk cannot prove a commit left a branch) and leaves {@code branchMembershipSyncedAt}
	 * on the last FULL traversal. Commits not stored (pre-claim) are skipped.
	 */
	@Transactional
	public void mergeMemberships(GitRepo repo, Map<String, Set<String>> shaToBranches) {
		if (repo == null || repo.getId() == null || shaToBranches == null || shaToBranches.isEmpty()) {
			return;
		}
		Set<String> shas = requiredShas(shaToBranches);
		if (shas.isEmpty()) {
			return;
		}
		List<GitCommit> stored = commits.findByRepo_IdAndShaHashIn(repo.getId(), shas);
		if (stored.isEmpty()) {
			return;
		}
		Map<java.util.UUID, Set<String>> existing = new java.util.HashMap<>();
		for (GitCommitBranch row : branches.findByCommit_IdInOrderByBranchNameAsc(
				stored.stream().map(GitCommit::getId).toList())) {
			existing.computeIfAbsent(row.getCommit().getId(), ignored -> new LinkedHashSet<>()).add(row.getBranchName());
		}
		List<GitCommitBranch> rows = new ArrayList<>();
		for (GitCommit commit : stored) {
			Set<String> names = shaToBranches.get(commit.getShaHash());
			if (names == null) {
				continue;
			}
			Set<String> have = existing.getOrDefault(commit.getId(), Set.of());
			for (String branchName : names) {
				if (branchName == null || branchName.isBlank() || have.contains(branchName)) {
					continue;
				}
				GitCommitBranch row = new GitCommitBranch();
				row.setCommit(commit);
				row.setBranchName(branchName);
				rows.add(row);
			}
		}
		if (!rows.isEmpty()) {
			branches.saveAll(rows);
		}
	}

	private static Set<String> requiredShas(Map<String, Set<String>> memberships) {
		Set<String> required = new LinkedHashSet<>();
		for (Map.Entry<String, Set<String>> entry : memberships.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank() || entry.getValue() == null) {
				continue;
			}
			for (String branchName : entry.getValue()) {
				if (branchName != null && !branchName.isBlank()) {
					required.add(entry.getKey());
					break;
				}
			}
		}
		return required;
	}
}

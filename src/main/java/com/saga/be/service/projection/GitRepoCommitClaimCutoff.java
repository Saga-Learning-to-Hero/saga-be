package com.saga.be.service.projection;

import com.saga.be.entity.github.GitRepo;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Option B claim cutoff: a Git commit belongs to a SAGA {@link GitRepo} projection only when its
 * canonical {@code committedAt} is {@code >=} that row's immutable {@link GitRepo#getCreatedAt()}.
 *
 * <p>{@code createdAt} models the project's <strong>first</strong> claim of that physical
 * repository and is intentionally preserved on same-project reconnect. Exact multi-tenure windows
 * such as A → B → A are not represented and are deferred technical debt.
 */
public final class GitRepoCommitClaimCutoff {

	private GitRepoCommitClaimCutoff() {}

	/**
	 * @param committedAt canonical GitHub committer timestamp already mapped into SAGA
	 * @param claimStartedAt {@link GitRepo#getCreatedAt()} — never substituted with "now"
	 * @return {@code true} when both timestamps are present and {@code committedAt >= claimStartedAt}
	 */
	public static boolean isEligible(LocalDateTime committedAt, LocalDateTime claimStartedAt) {
		if (committedAt == null || claimStartedAt == null) {
			// Missing provider commit time or missing claim start: do not invent a substitute.
			return false;
		}
		return !committedAt.isBefore(claimStartedAt);
	}

	public static boolean isEligible(CommitDraft draft, GitRepo repo) {
		if (draft == null || repo == null) {
			return false;
		}
		return isEligible(draft.committedAt(), repo.getCreatedAt());
	}

	/** Filters drafts before persist / auto-link. Preserves encounter order. */
	public static List<CommitDraft> filterEligible(GitRepo repo, List<CommitDraft> drafts) {
		if (drafts == null || drafts.isEmpty()) {
			return List.of();
		}
		List<CommitDraft> out = new ArrayList<>(drafts.size());
		for (CommitDraft draft : drafts) {
			if (isEligible(draft, repo)) {
				out.add(draft);
			}
		}
		return out;
	}
}

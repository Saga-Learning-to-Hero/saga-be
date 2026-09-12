package com.saga.be.service.projection;

import com.saga.be.entity.github.GitRepo;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Commit claim cutoff, scoped to only the case it exists to protect: cross-project reuse of a
 * physical GitHub repository (V15). A Git commit belongs to a SAGA {@link GitRepo} projection
 * when EITHER:
 *
 * <ul>
 *   <li>no OTHER SAGA project's row for this exact physical repository was created STRICTLY
 *       BEFORE this row ({@code cutoffApplies = false}) -- this project is (or is exactly as safe
 *       as) the repository's first-ever SAGA owner as of this row's own claim time, so its
 *       complete existing Git history may be imported; or
 *   <li>some other project's row was created before this one ({@code cutoffApplies = true}) --
 *       only commits with {@code committedAt >= } this row's immutable {@link
 *       GitRepo#getCreatedAt()} are eligible, so that earlier project's pre-existing history stays
 *       isolated.
 * </ul>
 *
 * <p><strong>Why "created before", not "has ever existed":</strong> "some other project has EVER
 * owned this repository" is a different, wrong question from "was THIS row created as a later
 * cross-project claim." Without the temporal bound, a first-ever owner (A, claimed at T1) whose
 * repository is LATER claimed by another project (B, at T2 &gt; T1) would incorrectly start
 * requiring a cutoff itself the moment B's row exists anywhere in history -- including on A's
 * ORIGINAL row on reconnect, whose own {@code createdAt} (T1) never changes. Comparing {@code
 * createdAt} values instead correctly resolves an A -&gt; B -&gt; A cycle: A (T1) never finds a
 * qualifying row (B's T2 is not {@code < T1}) and stays cutoff-free forever; B (T2) always finds
 * A's T1 and stays cutoff-protected forever, independent of either project's current
 * ACTIVE/REVOKED status or of how many times ownership changes hands afterward.
 *
 * <p>{@code cutoffApplies} must be computed fresh by the caller for every filtering call (see
 * {@code GitRepoRepository#existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan})
 * -- never cached as a one-time flag -- so manual recovery syncs and webhook projections made long
 * after the initial claim still apply the correct rule. {@code createdAt} is preserved on
 * same-project reconnect (the row is reused, never recreated), which is exactly why same-project
 * reconnect needs no separate case: it is indistinguishable from a first-ever claim under this
 * rule regardless of what happened to the physical repository in between.
 */
public final class GitRepoCommitClaimCutoff {

	private GitRepoCommitClaimCutoff() {}

	/**
	 * @param committedAt canonical GitHub committer timestamp already mapped into SAGA
	 * @param claimStartedAt {@link GitRepo#getCreatedAt()} — never substituted with "now"
	 * @param cutoffApplies whether another SAGA project's row for this physical repository was
	 *     created strictly before this row's own claim time
	 * @return {@code true} when the commit is eligible for this project's projection
	 */
	public static boolean isEligible(LocalDateTime committedAt, LocalDateTime claimStartedAt, boolean cutoffApplies) {
		if (!cutoffApplies) {
			// No other project's history to protect -- full existing Git history is allowed.
			// A commit lacking a parseable timestamp is still a real commit; there is nothing to
			// compare it against, so it is not rejected merely for that reason.
			return true;
		}
		if (committedAt == null || claimStartedAt == null) {
			// Missing provider commit time or missing claim start: do not invent a substitute.
			return false;
		}
		return !committedAt.isBefore(claimStartedAt);
	}

	public static boolean isEligible(CommitDraft draft, GitRepo repo, boolean cutoffApplies) {
		if (draft == null || repo == null) {
			return false;
		}
		return isEligible(draft.committedAt(), repo.getCreatedAt(), cutoffApplies);
	}

	/** Filters drafts before persist / auto-link. Preserves encounter order. */
	public static List<CommitDraft> filterEligible(GitRepo repo, List<CommitDraft> drafts, boolean cutoffApplies) {
		if (drafts == null || drafts.isEmpty()) {
			return List.of();
		}
		List<CommitDraft> out = new ArrayList<>(drafts.size());
		for (CommitDraft draft : drafts) {
			if (isEligible(draft, repo, cutoffApplies)) {
				out.add(draft);
			}
		}
		return out;
	}
}

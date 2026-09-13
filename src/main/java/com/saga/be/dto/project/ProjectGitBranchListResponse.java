package com.saga.be.dto.project;

import java.util.List;
import java.util.UUID;

/**
 * Live GitHub branch inventory for one ACTIVE repository, fetched directly from the provider
 * (fully paginated) at request time -- this is the authoritative source for the repository branch
 * dropdown and the current branch count.
 *
 * <p>{@code GitCommit.headRef} is NOT authoritative branch inventory and must not be used to derive
 * it: {@code headRef} records only the single branch a commit happened to be observed under during
 * ingestion (first-processed branch wins when a commit exists on more than one), not that commit's
 * complete branch membership, and a branch that exists on GitHub but currently has no unique commit
 * of its own never appears as any commit's {@code headRef} at all. FE must not compute
 * {@code unique(commits.map(commit.headRef))} as a branch list/count — use this endpoint instead.
 *
 * <p>Consequently, filtering commits by {@code commit.headRef == selectedBranch} remains
 * best-effort/observed-branch filtering only, not exact: a commit may exist on multiple branches
 * while {@code GitCommit} stores only one {@code headRef}. Exact many-to-many commit-to-branch
 * membership is not represented by the current data model and would require a separate persisted
 * relation (future work, out of scope for this endpoint).
 */
public record ProjectGitBranchListResponse(UUID repoId, String repositoryFullName, int branchCount, List<Branch> branches) {

	public record Branch(String name, boolean isDefault) {}
}

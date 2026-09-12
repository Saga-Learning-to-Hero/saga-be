package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.github.GitRepo;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class GitRepoCommitClaimCutoffTest {

	@Test
	void preClaimCommitIsExcluded() {
		LocalDateTime claim = LocalDateTime.of(2026, 9, 1, 0, 0);
		assertThat(GitRepoCommitClaimCutoff.isEligible(LocalDateTime.of(2026, 8, 15, 12, 0), claim)).isFalse();
	}

	@Test
	void exactClaimInstantIsIncluded() {
		LocalDateTime claim = LocalDateTime.of(2026, 9, 1, 0, 0);
		assertThat(GitRepoCommitClaimCutoff.isEligible(claim, claim)).isTrue();
	}

	@Test
	void postClaimCommitIsIncluded() {
		LocalDateTime claim = LocalDateTime.of(2026, 9, 1, 0, 0);
		assertThat(GitRepoCommitClaimCutoff.isEligible(LocalDateTime.of(2026, 9, 20, 0, 0), claim)).isTrue();
	}

	@Test
	void missingCommittedAtIsExcluded() {
		assertThat(GitRepoCommitClaimCutoff.isEligible(null, LocalDateTime.of(2026, 9, 1, 0, 0))).isFalse();
	}

	@Test
	void missingClaimStartIsExcluded() {
		assertThat(GitRepoCommitClaimCutoff.isEligible(LocalDateTime.of(2026, 9, 20, 0, 0), null)).isFalse();
	}

	@Test
	void filterEligibleDropsPreClaimOnly() {
		GitRepo repo = new GitRepo();
		repo.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		List<CommitDraft> drafts = List.of(
				draft("old", LocalDateTime.of(2026, 8, 1, 0, 0)),
				draft("edge", LocalDateTime.of(2026, 9, 1, 0, 0)),
				draft("new", LocalDateTime.of(2026, 9, 2, 0, 0)),
				draft("missing", null));
		assertThat(GitRepoCommitClaimCutoff.filterEligible(repo, drafts))
				.extracting(CommitDraft::sha)
				.containsExactly("edge", "new");
	}

	private static CommitDraft draft(String sha, LocalDateTime committedAt) {
		return new CommitDraft(sha, "msg", committedAt, null, "alice", "main");
	}
}

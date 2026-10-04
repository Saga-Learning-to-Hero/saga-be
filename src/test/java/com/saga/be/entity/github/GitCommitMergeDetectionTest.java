package com.saga.be.entity.github;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Which commits the AI review treats as merges (never reviewed, never warned about). */
class GitCommitMergeDetectionTest {

	private static GitCommit commit(Integer parents, String message) {
		GitCommit commit = new GitCommit();
		commit.setParentCount(parents);
		commit.setMessage(message);
		return commit;
	}

	@Test
	void aKnownParentCountDecides() {
		assertThat(commit(2, "anything").looksLikeMerge()).isTrue();
		assertThat(commit(3, "octopus").looksLikeMerge()).isTrue();
		assertThat(commit(1, "Merge pull request #5 from x/y").looksLikeMerge()).isFalse();
		assertThat(commit(0, "init").looksLikeMerge()).isFalse();
	}

	@Test
	void withAnUnknownParentCount_gitAndGithubMergeMessagesAreMerges() {
		// a push webhook carries no parents: these arrive with parentCount = null
		assertThat(commit(null, "Merge pull request #70 from Saga-Learning-to-Hero/dev").looksLikeMerge()).isTrue();
		assertThat(commit(null, "Merge branch 'main' of https://github.com/Saga-Learning-to-Hero/saga-fe").looksLikeMerge()).isTrue();
		assertThat(commit(null, "Merge remote-tracking branch 'origin/dev'").looksLikeMerge()).isTrue();
		assertThat(commit(null, "  Merge tag 'v1.0'").looksLikeMerge()).isTrue();
	}

	@Test
	void ordinaryMessagesThatMentionMergeAreNotMerges() {
		assertThat(commit(null, "fix: merge sort bug").looksLikeMerge()).isFalse();
		assertThat(commit(null, "merge conflicts resolved in Login").looksLikeMerge()).isFalse();
		assertThat(commit(null, "feat: SAGA-1 Merge pull request handling").looksLikeMerge()).isFalse();
		assertThat(commit(null, null).looksLikeMerge()).isFalse();
	}

	@Test
	void isMergeItselfStaysParentCountBased() {
		assertThat(commit(null, "Merge pull request #70 from x/y").isMerge()).isNull();
	}
}

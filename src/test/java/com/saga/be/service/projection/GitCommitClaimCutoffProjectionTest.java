package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.StudentProfileRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The claim cutoff (V15 follow-up) applies ONLY for cross-project reuse of a physical GitHub
 * repository -- and specifically only when some OTHER SAGA project's row for that repository was
 * created STRICTLY BEFORE this row's own {@code createdAt} (see {@link
 * GitRepoRepository#existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan}).
 *
 * <p>"Some other project has EVER owned this repository" (no temporal bound) was the FIRST fix's
 * predicate and is deliberately wrong: it would re-impose a cutoff on a first-ever owner (A) the
 * moment ANY later project (B) ever claims the same physical repository, even on A's ORIGINAL row
 * reused on reconnect (A -&gt; B -&gt; A). Comparing {@code createdAt} instead keeps A cutoff-free
 * forever (B's later claim never predates A's own {@code createdAt}) while B stays
 * cutoff-protected forever (A's earlier claim always predates B's {@code createdAt}).
 */
@ExtendWith(MockitoExtension.class)
class GitCommitClaimCutoffProjectionTest {

	private static final LocalDateTime T1 = LocalDateTime.of(2026, 1, 1, 0, 0);
	private static final LocalDateTime T2 = LocalDateTime.of(2026, 6, 1, 0, 0);

	@Mock
	private GitCommitRepository commits;
	@Mock
	private GitRepoRepository gitRepos;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private CommitTaskAutoLinkService autoLink;

	private GitCommitProjectionService service;
	private GitRepo repo;

	@BeforeEach
	void setUp() {
		service = new GitCommitProjectionService(commits, gitRepos, identities, students, jiraIntegrations, autoLink);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(555_000_111L);
		repo.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
	}

	@Test
	void crossProjectReuse_excludesPreClaimAndMissingTimestamp() {
		stubCutoffApplies(true);
		when(commits.findByRepo_IdAndShaHashIn(any(), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		when(jiraIntegrations.findByProject_Id(repo.getProject().getId())).thenReturn(Optional.empty());
		when(autoLink.linkCommits(any(), any(), any())).thenReturn(0);

		List<GitCommitProjectionService.CommitDraft> drafts = List.of(
				draft("pre", "SAGA-123", LocalDateTime.of(2026, 8, 15, 0, 0)),
				draft("edge", "ok", LocalDateTime.of(2026, 9, 1, 0, 0)),
				draft("post", "ok", LocalDateTime.of(2026, 9, 20, 0, 0)),
				draft("null-ts", "SAGA-123", null));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repo, drafts);

		assertThat(outcome.commitsTouched()).isEqualTo(2);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> saved = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(saved.capture());
		assertThat(saved.getValue()).extracting(GitCommit::getShaHash).containsExactly("edge", "post");
		verify(autoLink).linkCommits(eq(repo.getProject().getId()), any(), saved.capture());
		assertThat(saved.getAllValues().getLast())
				.extracting(GitCommit::getShaHash)
				.containsExactly("edge", "post")
				.doesNotContain("pre", "null-ts");
	}

	@Test
	void crossProjectReuse_preClaimOnly_skipsPersistAndAutoLink() {
		stubCutoffApplies(true);
		List<GitCommitProjectionService.CommitDraft> drafts =
				List.of(draft("pre", "SAGA-123", LocalDateTime.of(2026, 8, 1, 0, 0)));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repo, drafts);

		assertThat(outcome).isEqualTo(GitCommitProjectionService.UpsertOutcome.EMPTY);
		verify(commits, never()).saveAll(any());
		verify(autoLink, never()).linkCommits(any(), any(), any());
	}

	@Test
	void firstEverOwner_importsPreClaimHistory() {
		// No other SAGA project's row for this repository predates this row -- the original bug:
		// a first-ever owner must NOT have its repository's existing history dropped merely
		// because the git_repo row was created after those commits already existed on GitHub.
		stubCutoffApplies(false);
		when(commits.findByRepo_IdAndShaHashIn(any(), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		when(jiraIntegrations.findByProject_Id(repo.getProject().getId())).thenReturn(Optional.empty());
		when(autoLink.linkCommits(any(), any(), any())).thenReturn(0);

		List<GitCommitProjectionService.CommitDraft> drafts = List.of(
				draft("ancient", "old", LocalDateTime.of(2020, 1, 1, 0, 0)),
				draft("post", "ok", LocalDateTime.of(2026, 9, 20, 0, 0)),
				draft("no-timestamp", "ok", null));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repo, drafts);

		assertThat(outcome.commitsTouched()).isEqualTo(3);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> saved = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(saved.capture());
		assertThat(saved.getValue())
				.extracting(GitCommit::getShaHash)
				.containsExactlyInAnyOrder("ancient", "post", "no-timestamp");
	}

	@Test
	void sameProjectReconnect_behavesLikeFirstEverOwner_noOtherProjectRowPredatesIt() {
		// The existence check excludes this project's own id and requires an earlier createdAt;
		// only this project has ever had a row for this repository, so a same-project reconnect
		// is indistinguishable from a first-ever claim: no isolation concern, full history retained.
		stubCutoffApplies(false);
		when(commits.findByRepo_IdAndShaHashIn(any(), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		when(jiraIntegrations.findByProject_Id(repo.getProject().getId())).thenReturn(Optional.empty());
		when(autoLink.linkCommits(any(), any(), any())).thenReturn(0);

		List<GitCommitProjectionService.CommitDraft> drafts =
				List.of(draft("before-disconnect", "ok", LocalDateTime.of(2026, 8, 1, 0, 0)));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repo, drafts);

		assertThat(outcome.commitsTouched()).isEqualTo(1);
	}

	@Test
	void firstOwnerReclaimedAfterLaterProject_keepsFirstOwnerSemantics_aThenBThenA() {
		// A claims at T1 (repo.createdAt = T1, fixed forever). B later claims the same physical
		// repository at T2 > T1 and disconnects. A reconnects, reusing its ORIGINAL row -- createdAt
		// stays T1. The exists-check for A's row must ask "does another project's row predate T1?"
		// -- B's row (T2) does not, so A must NOT gain a cutoff merely because B existed afterward.
		GitRepo repoA = new GitRepo();
		repoA.setId(UUID.randomUUID());
		Project projectA = new Project();
		projectA.setId(UUID.randomUUID());
		repoA.setProject(projectA);
		repoA.setProvider(GitProvider.GITHUB);
		repoA.setRepositoryId(999_222_333L);
		repoA.setCreatedAt(T1);
		when(gitRepos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						GitProvider.GITHUB, 999_222_333L, projectA.getId(), T1))
				.thenReturn(false); // B's T2 is not before A's T1
		when(commits.findByRepo_IdAndShaHashIn(any(), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		when(jiraIntegrations.findByProject_Id(projectA.getId())).thenReturn(Optional.empty());
		when(autoLink.linkCommits(any(), any(), any())).thenReturn(0);

		List<GitCommitProjectionService.CommitDraft> drafts =
				List.of(draft("predates-A-claim", "old", LocalDateTime.of(2025, 1, 1, 0, 0)));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repoA, drafts);

		assertThat(outcome.commitsTouched()).isEqualTo(1);
	}

	@Test
	void laterOwnerRemainsCutoffProtected_bAfterA() {
		// B claims at T2, AFTER A's row (T1) already existed for the same physical repository. B
		// must be cutoff-protected against A's pre-existing history forever, regardless of A's or
		// B's later status changes.
		GitRepo repoB = new GitRepo();
		repoB.setId(UUID.randomUUID());
		Project projectB = new Project();
		projectB.setId(UUID.randomUUID());
		repoB.setProject(projectB);
		repoB.setProvider(GitProvider.GITHUB);
		repoB.setRepositoryId(999_222_333L);
		repoB.setCreatedAt(T2);
		when(gitRepos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						GitProvider.GITHUB, 999_222_333L, projectB.getId(), T2))
				.thenReturn(true); // A's T1 is before B's T2
		when(commits.findByRepo_IdAndShaHashIn(any(), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		when(jiraIntegrations.findByProject_Id(projectB.getId())).thenReturn(Optional.empty());
		when(autoLink.linkCommits(any(), any(), any())).thenReturn(0);

		List<GitCommitProjectionService.CommitDraft> drafts = List.of(
				draft("b-pre-claim", "A's old commit", T2.minusDays(1)),
				draft("b-post-claim", "B's own commit", T2.plusDays(1)));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repoB, drafts);

		assertThat(outcome.commitsTouched()).isEqualTo(1);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> saved = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(saved.capture());
		assertThat(saved.getValue()).extracting(GitCommit::getShaHash).containsExactly("b-post-claim");
	}

	private void stubCutoffApplies(boolean applies) {
		when(gitRepos.existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
						GitProvider.GITHUB, 555_000_111L, repo.getProject().getId(), repo.getCreatedAt()))
				.thenReturn(applies);
	}

	private static GitCommitProjectionService.CommitDraft draft(String sha, String message, LocalDateTime at) {
		return new GitCommitProjectionService.CommitDraft(sha, message, at, null, "alice", "main");
	}
}

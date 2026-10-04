package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.service.ai.AiCommitAutomationTrigger;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Only commits first seen now and pushed in the last 72h are handed to the AI review and the
 * "no task" notification: re-upserting known commits on every sync, or a first backfill of old
 * history, must never spend AI quota or send notifications.
 */
class GitCommitAutoReviewWindowTest {

	private GitCommitRepository commits;
	private AiCommitAutomationTrigger automation;
	private UnlinkedCommitNotifier notifier;
	private CommitTaskAutoLinkService autoLink;
	private GitCommitProjectionService service;
	private GitRepo repo;
	private final List<GitCommit> stored = new ArrayList<>();

	@BeforeEach
	void setUp() {
		commits = mock(GitCommitRepository.class);
		IdentityMapRepository identities = mock(IdentityMapRepository.class);
		automation = mock(AiCommitAutomationTrigger.class);
		notifier = mock(UnlinkedCommitNotifier.class);
		autoLink = mock(CommitTaskAutoLinkService.class);
		service = new GitCommitProjectionService(commits, mock(GitRepoRepository.class), identities, mock(StudentProfileRepository.class), autoLink, automation);
		service.setUnlinkedNotifier(notifier);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		when(identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(any(), any(), any())).thenReturn(List.of());
		when(identities.findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(any(), any(), any())).thenReturn(List.of());
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenAnswer(inv -> {
			java.util.Collection<String> shas = inv.getArgument(1);
			return stored.stream().filter(c -> shas.contains(c.getShaHash())).toList();
		});
		when(commits.saveAll(any())).thenAnswer(inv -> {
			List<GitCommit> list = inv.getArgument(0);
			for (GitCommit commit : list) {
				if (commit.getId() == null) {
					commit.setId(UUID.randomUUID());
					stored.add(commit);
				}
			}
			return list;
		});
	}

	@SuppressWarnings("unchecked")
	private List<UUID> automated() {
		ArgumentCaptor<List<UUID>> captor = ArgumentCaptor.forClass(List.class);
		verify(automation, org.mockito.Mockito.atLeastOnce()).afterCommitsPersisted(eq(repo.getProject().getId()), captor.capture());
		return captor.getValue();
	}

	@Test
	void aNewRecentCommitIsReviewedAndChecked_aReUpsertOfItIsNot() {
		CommitDraft draft = new CommitDraft("recent1", "fix", LocalDateTime.now().minusHours(2), "1", "alice", "main");

		service.upsertBatch(repo, List.of(draft));
		assertThat(automated()).containsExactly(stored.getFirst().getId());

		service.upsertBatch(repo, List.of(draft)); // the next sync sees it again
		assertThat(automated()).isEmpty();
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> fresh = ArgumentCaptor.forClass(List.class);
		verify(notifier, org.mockito.Mockito.times(2)).afterNewCommits(eq(repo.getProject().getId()), fresh.capture());
		assertThat(fresh.getAllValues().get(0)).hasSize(1);
		assertThat(fresh.getAllValues().get(1)).isEmpty();
	}

	@Test
	void theGithubLoginIsKept_aWebhookGivesTheLogin_aSyncAddsTheNumericId_andNoneErasesIt() {
		// webhook: login only
		service.upsertBatch(repo, List.of(new CommitDraft("login01", "fix", LocalDateTime.now().minusHours(2), null, "trungne08", "main")));
		GitCommit commit = stored.getFirst();
		assertThat(commit.getAuthorLogin()).isEqualTo("trungne08");
		assertThat(commit.getAuthorExternalId()).isEqualTo("trungne08");

		// API sync of the same commit: numeric id and login
		service.upsertBatch(repo, List.of(new CommitDraft("login01", "fix", LocalDateTime.now().minusHours(2), "139128461", "trungne08", "main")));
		assertThat(commit.getAuthorExternalId()).isEqualTo("139128461");
		assertThat(commit.getAuthorLogin()).isEqualTo("trungne08");

		// a source that does not know the login never clears it
		service.upsertBatch(repo, List.of(new CommitDraft("login01", "fix", LocalDateTime.now().minusHours(2), "139128461", null, "main")));
		assertThat(commit.getAuthorLogin()).isEqualTo("trungne08");
	}

	@Test
	void oldHistoryInAFirstBackfillIsNeverReviewedAutomatically() {
		service.upsertBatch(repo, List.of(
				new CommitDraft("old0001", "init", LocalDateTime.now().minusDays(30), "1", "alice", "main"),
				new CommitDraft("old0002", "wip", LocalDateTime.now().minusHours(73), "1", "alice", "main"),
				new CommitDraft("new0001", "SAGA-1 feat", LocalDateTime.now().minusHours(1), "1", "alice", "main"),
				new CommitDraft("nodate1", "unknown time", null, "1", "alice", "main")));

		List<UUID> reviewed = automated();
		assertThat(reviewed).hasSize(1);
		assertThat(stored.stream().filter(c -> c.getId().equals(reviewed.getFirst())).findFirst().orElseThrow().getShaHash()).isEqualTo("new0001");
	}

	@Test
	void ingestionWorksWithoutTheNotifierWired() {
		service.setUnlinkedNotifier(null);
		assertThat(service.upsertBatch(repo, List.of(new CommitDraft("recent2", "x", LocalDateTime.now(), "1", "alice", "main")))).isEqualTo(1);
	}
}

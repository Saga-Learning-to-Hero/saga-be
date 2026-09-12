package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.GitCommitRepository;
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

@ExtendWith(MockitoExtension.class)
class GitCommitClaimCutoffProjectionTest {

	@Mock
	private GitCommitRepository commits;
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
		service = new GitCommitProjectionService(commits, identities, students, jiraIntegrations, autoLink);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setCreatedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
	}

	@Test
	void upsertBatch_excludesPreClaimAndMissingTimestamp() {
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
	void upsertBatch_preClaimOnly_skipsPersistAndAutoLink() {
		List<GitCommitProjectionService.CommitDraft> drafts =
				List.of(draft("pre", "SAGA-123", LocalDateTime.of(2026, 8, 1, 0, 0)));

		GitCommitProjectionService.UpsertOutcome outcome = service.upsertBatchDetailed(repo, drafts);

		assertThat(outcome).isEqualTo(GitCommitProjectionService.UpsertOutcome.EMPTY);
		verify(commits, never()).saveAll(any());
		verify(autoLink, never()).linkCommits(any(), any(), any());
	}

	private static GitCommitProjectionService.CommitDraft draft(String sha, String message, LocalDateTime at) {
		return new GitCommitProjectionService.CommitDraft(sha, message, at, null, "alice", "main");
	}
}

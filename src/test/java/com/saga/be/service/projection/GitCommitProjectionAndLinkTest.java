package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GitCommitProjectionAndLinkTest {

	@Mock
	private GitCommitRepository commits;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskGitCommitLinkRepository links;
	@Mock
	private CommitMessageCandidateQuery candidates;

	private GitCommitProjectionService commitsService;
	private CommitTaskAutoLinkService autoLink;
	private Project project;
	private GitRepo repo;

	@BeforeEach
	void setUp() {
		autoLink = new CommitTaskAutoLinkService(tasks, links, candidates);
		commitsService = new GitCommitProjectionService(commits, identities, students, jiraIntegrations, autoLink);
		project = new Project();
		project.setId(UUID.randomUUID());
		repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setFullName("org/saga");
	}

	@Test
	void upsert_thenWebhookSameSha_doesNotDuplicate() {
		CommitDraft draft = new CommitDraft("abc123", "SAGA-1 fix", null, "99", "alice", "main");
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenReturn(List.of());
		when(identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(identities.findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> {
			List<GitCommit> list = inv.getArgument(0);
			list.forEach(c -> {
				if (c.getId() == null) {
					c.setId(UUID.randomUUID());
				}
			});
			return list;
		});
		JiraIntegration jira = new JiraIntegration();
		jira.setProjectKey("SAGA");
		when(jiraIntegrations.findByProject_Id(project.getId())).thenReturn(Optional.of(jira));
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(any(), any())).thenReturn(List.of());
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());

		assertThat(commitsService.upsertBatch(repo, List.of(draft))).isEqualTo(1);

		GitCommit existing = new GitCommit();
		existing.setId(UUID.randomUUID());
		existing.setShaHash("abc123");
		existing.setRepo(repo);
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenReturn(List.of(existing));
		assertThat(commitsService.upsertBatch(repo, List.of(draft))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits, times(2)).saveAll(captor.capture());
		assertThat(captor.getAllValues().get(1).getFirst().getId()).isEqualTo(existing.getId());
	}

	@Test
	void autoLink_taskFirst_thenCommit() {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 fix login");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-12");
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(eq(project.getId()), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), "SAGA", List.of(commit))).isEqualTo(1);
	}

	@Test
	void autoLink_commitFirst_thenTask_reconciles() {
		GitCommit waiting = new GitCommit();
		waiting.setId(UUID.randomUUID());
		waiting.setMessage("SAGA-123 implement login");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-123");
		when(candidates.findByProjectAndKeys(eq(project.getId()), any())).thenReturn(List.of(waiting));
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(eq(project.getId()), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkTasks(project.getId(), "SAGA", List.of(task))).isEqualTo(1);

		TaskGitCommitLink existing = new TaskGitCommitLink();
		existing.setTask(task);
		existing.setGitCommit(waiting);
		existing.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of(existing));
		assertThat(autoLink.linkTasks(project.getId(), "SAGA", List.of(task))).isEqualTo(0);
	}

	@Test
	void autoLink_oneCommitMultipleTasks() {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 SAGA-15 complete flow");
		Task t12 = new Task();
		t12.setId(UUID.randomUUID());
		t12.setExternalKey("SAGA-12");
		Task t15 = new Task();
		t15.setId(UUID.randomUUID());
		t15.setExternalKey("SAGA-15");
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(eq(project.getId()), any()))
				.thenReturn(List.of(t12, t15));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), "SAGA", List.of(commit))).isEqualTo(2);
	}

	@Test
	void autoLink_multipleCommitsWaitingForSameTask() {
		GitCommit c1 = new GitCommit();
		c1.setId(UUID.randomUUID());
		c1.setMessage("SAGA-9 part 1");
		GitCommit c2 = new GitCommit();
		c2.setId(UUID.randomUUID());
		c2.setMessage("SAGA-9 part 2");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-9");
		when(candidates.findByProjectAndKeys(eq(project.getId()), any())).thenReturn(List.of(c1, c2));
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(eq(project.getId()), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkTasks(project.getId(), "SAGA", List.of(task))).isEqualTo(2);
	}

	@Test
	void autoLink_filtersToSelectedProjectKey_andIsIdempotent() {
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 fix login and ABC-99 noise");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-12");
		when(tasks.findByProject_IdAndExternalKeyIgnoreCaseIn(eq(project.getId()), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), "SAGA", List.of(commit))).isEqualTo(1);

		TaskGitCommitLink existing = new TaskGitCommitLink();
		existing.setTask(task);
		existing.setGitCommit(commit);
		existing.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of(existing));
		assertThat(autoLink.linkCommits(project.getId(), "SAGA", List.of(commit))).isEqualTo(0);
	}

	@Test
	void upsertBatch_usesBoundedShaLookups_notPerCommit() {
		List<CommitDraft> ten = new ArrayList<>();
		List<CommitDraft> hundred = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			CommitDraft draft = new CommitDraft("sha" + i, "msg", null, null, null, "main");
			hundred.add(draft);
			if (i < 10) {
				ten.add(draft);
			}
		}
		AtomicInteger lookups = new AtomicInteger();
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenAnswer(inv -> {
			lookups.incrementAndGet();
			return List.of();
		});
		when(commits.saveAll(any())).thenAnswer(inv -> {
			List<GitCommit> list = inv.getArgument(0);
			list.forEach(c -> c.setId(UUID.randomUUID()));
			return list;
		});
		when(jiraIntegrations.findByProject_Id(project.getId())).thenReturn(Optional.empty());

		commitsService.upsertBatch(repo, ten);
		int after10 = lookups.get();
		commitsService.upsertBatch(repo, hundred);
		int after100 = lookups.get();

		assertThat(after10).isEqualTo(1);
		assertThat(after100 - after10).isEqualTo(1);
	}
}

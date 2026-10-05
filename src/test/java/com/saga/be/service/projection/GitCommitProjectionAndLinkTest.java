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
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.projection.GitCommitProjectionService.CommitDraft;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
	private GitRepoRepository gitRepos;
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
		autoLink = new CommitTaskAutoLinkService(tasks, links, candidates, jiraIntegrations);
		commitsService =
				new GitCommitProjectionService(commits, gitRepos, identities, students, autoLink, org.mockito.Mockito.mock(com.saga.be.service.ai.AiCommitAutomationTrigger.class));
		project = new Project();
		project.setId(UUID.randomUUID());
		repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setFullName("org/saga");
		repo.setCreatedAt(java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
	}

	@Test
	void upsert_thenWebhookSameSha_doesNotDuplicate() {
		CommitDraft draft = new CommitDraft(
				"abc123", "SAGA-1 fix", java.time.LocalDateTime.of(2026, 6, 1, 12, 0), "99", "alice", "main");
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
		activeSaga();
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(any(), any(), any())).thenReturn(List.of());

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
	void webhookUnknownParentCountDoesNotOverwriteKnownMerge() {
		GitCommit existing = new GitCommit();
		existing.setId(UUID.randomUUID());
		existing.setShaHash("abc123");
		existing.setRepo(repo);
		existing.setParentCount(2);
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenReturn(List.of(existing));
		when(identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(identities.findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		CommitDraft webhookUnknown = new CommitDraft(
				"abc123",
				"Merge branch 'x'",
				java.time.LocalDateTime.of(2026, 6, 1, 12, 0),
				"99",
				"alice",
				"main",
				null);

		assertThat(commitsService.upsertBatch(repo, List.of(webhookUnknown))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentCount()).isEqualTo(2);
	}

	@Test
	void fullSyncKnownParentCountEnrichesUnknownRow() {
		GitCommit existing = new GitCommit();
		existing.setId(UUID.randomUUID());
		existing.setShaHash("abc123");
		existing.setRepo(repo);
		existing.setParentCount(null);
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenReturn(List.of(existing));
		when(identities.findFetchedByProviderAndExternalAccountIdInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(identities.findFetchedByProviderAndExternalUsernameLowerInAndMappingStatusIn(any(), any(), any()))
				.thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		CommitDraft sync = new CommitDraft(
				"abc123",
				"custom message",
				java.time.LocalDateTime.of(2026, 6, 1, 12, 0),
				"99",
				"alice",
				"main",
				2);

		commitsService.upsertBatch(repo, List.of(sync));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentCount()).isEqualTo(2);
	}

	@Test
	void insertWritesNullableParentCountsIncludingRootAndMerge() {
		when(commits.findByRepo_IdAndShaHashIn(eq(repo.getId()), any())).thenReturn(List.of());
		when(commits.saveAll(any())).thenAnswer(inv -> {
			List<GitCommit> list = inv.getArgument(0);
			list.forEach(c -> c.setId(UUID.randomUUID()));
			return list;
		});

		List<CommitDraft> drafts = List.of(
				new CommitDraft("root", "init", java.time.LocalDateTime.now(), null, null, "main", 0),
				new CommitDraft("one", "Merge branch x", java.time.LocalDateTime.now(), null, null, "main", 1),
				new CommitDraft("two", "custom", java.time.LocalDateTime.now(), null, null, "main", 2),
				new CommitDraft("unk", "later webhook", java.time.LocalDateTime.now(), null, null, "main", null));

		commitsService.upsertBatch(repo, drafts);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<GitCommit>> captor = ArgumentCaptor.forClass(List.class);
		verify(commits).saveAll(captor.capture());
		assertThat(captor.getValue()).extracting(GitCommit::getParentCount).containsExactly(0, 1, 2, null);
		assertThat(captor.getValue()).extracting(GitCommit::isMerge).containsExactly(false, false, true, null);
	}

	@Test
	void autoLink_taskFirst_thenCommit() {
		JiraIntegration saga = activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 fix login");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-12");
		task.setJiraIntegration(saga);
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isEqualTo(1);
	}

	@Test
	void autoLink_commitFirst_thenTask_reconciles() {
		JiraIntegration saga = activeSaga();
		GitCommit waiting = new GitCommit();
		waiting.setId(UUID.randomUUID());
		waiting.setMessage("SAGA-123 implement login");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-123");
		task.setJiraIntegration(saga);
		when(candidates.findByProjectAndKeys(eq(project.getId()), any())).thenReturn(List.of(waiting));
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any())).thenReturn(List.of(task));
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
	void autoLink_oneCommitBelongsToOneTask_theFirstKeyInTheMessage() {
		JiraIntegration saga = activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 SAGA-15 complete flow");
		Task t12 = task(saga, "SAGA-12");
		Task t15 = task(saga, "SAGA-15");
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any()))
				.thenReturn(List.of(t15, t12));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isEqualTo(1);

		assertThat(savedLinks()).singleElement().satisfies(link -> {
			assertThat(link.getTask()).isSameAs(t12);
			assertThat(link.getJiraKeySnapshot()).isEqualTo("SAGA-12");
		});
	}

	@Test
	void autoLink_theMessageKeyWinsOverTheBranchName_andTheBranchLinkIsRemoved() {
		// af2e84a: "fix: [FE][SAGA-116] ..." pushed on feat/SAGA-102-... used to be linked to both
		JiraIntegration saga = activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("fix: [FE][SAGA-116] Cập nhật bộ lọc courseId cho DelayCasesActionWidget");
		commit.setHeadRef("refs/heads/feat/SAGA-102-delay-cases");
		Task t116 = task(saga, "SAGA-116");
		Task t102 = task(saga, "SAGA-102");
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any()))
				.thenReturn(List.of(t102, t116));
		TaskGitCommitLink keep = link(t116, commit);
		TaskGitCommitLink branch = link(t102, commit);
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of(branch, keep));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isZero();

		verify(links).deleteAllInBatch(List.of(branch));
		verify(links, org.mockito.Mockito.never()).saveAll(any());
	}

	@Test
	void autoLink_theBranchNameNeverLinks_andItsOldLinkIsRemoved() {
		// 3f0eb3c "fix: commit đã có task thì ẩn form..." reached feat/SAGA-119-... when main was merged in
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("fix:  commit đã có task thì ẩn form gắn task thủ công");
		commit.setHeadRef("feat/SAGA-119-Fix-peer-review-evaluation");
		Task t119 = new Task();
		t119.setId(UUID.randomUUID());
		t119.setExternalKey("SAGA-119");
		TaskGitCommitLink fromBranch = link(t119, commit);
		when(links.findByGitCommit_IdIn(Set.of(commit.getId()))).thenReturn(List.of(fromBranch));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isZero();

		verify(links).deleteAllInBatch(List.of(fromBranch));
		verify(links, org.mockito.Mockito.never()).saveAll(any());
		org.mockito.Mockito.verifyNoInteractions(tasks);
	}

	@Test
	void autoLink_aKeyWithoutATaskFallsThroughToTheNextKey() {
		JiraIntegration saga = activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-404 SAGA-15 typo in key");
		Task t15 = task(saga, "SAGA-15");
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any()))
				.thenReturn(List.of(t15));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isEqualTo(1);
		assertThat(savedLinks()).singleElement().extracting(TaskGitCommitLink::getTask).isSameAs(t15);
	}

	@Test
	void autoLink_aMergeCommitIsNeverLinked_andLosesOldLinks() {
		GitCommit merge = new GitCommit();
		merge.setId(UUID.randomUUID());
		merge.setMessage("Merge pull request #12 from org/feat/SAGA-102-login");
		Task t102 = new Task();
		t102.setId(UUID.randomUUID());
		TaskGitCommitLink old = link(t102, merge);
		when(links.findByGitCommit_IdIn(Set.of(merge.getId()))).thenReturn(List.of(old));

		assertThat(autoLink.linkCommits(project.getId(), List.of(merge))).isZero();

		verify(links).deleteAllInBatch(List.of(old));
		verify(links, org.mockito.Mockito.never()).saveAll(any());
		org.mockito.Mockito.verifyNoInteractions(tasks, jiraIntegrations);
	}

	@Test
	void autoLink_aKeyThatNamesTheTaskReplacesAHandMadeAttachment() {
		JiraIntegration saga = activeSaga();
		var manualLinks = org.mockito.Mockito.mock(com.saga.be.repository.TaskCommitManualLinkRepository.class);
		autoLink.setManualLinks(manualLinks);
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("feat: SAGA-12 login");
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any()))
				.thenReturn(List.of(task(saga, "SAGA-12")));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		autoLink.linkCommits(project.getId(), List.of(commit));

		verify(manualLinks).deleteByGitCommitIds(Set.of(commit.getId()));
	}

	@Test
	void autoLink_aLinkWhoseKeyIsInTheMessageStaysEvenIfItNoLongerResolves() {
		activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("OLD-2 legacy work");
		Task old = new Task();
		old.setId(UUID.randomUUID());
		old.setExternalKey("OLD-2");
		when(links.findByGitCommit_IdIn(Set.of(commit.getId()))).thenReturn(List.of(link(old, commit)));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isZero();

		verify(links, org.mockito.Mockito.never()).deleteAllInBatch(any());
		verify(links, org.mockito.Mockito.never()).saveAll(any());
	}

	@Test
	void autoLink_multipleCommitsWaitingForSameTask() {
		JiraIntegration saga = activeSaga();
		GitCommit c1 = new GitCommit();
		c1.setId(UUID.randomUUID());
		c1.setMessage("SAGA-9 part 1");
		GitCommit c2 = new GitCommit();
		c2.setId(UUID.randomUUID());
		c2.setMessage("SAGA-9 part 2");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-9");
		task.setJiraIntegration(saga);
		when(candidates.findByProjectAndKeys(eq(project.getId()), any())).thenReturn(List.of(c1, c2));
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkTasks(project.getId(), "SAGA", List.of(task))).isEqualTo(2);
	}

	@Test
	void autoLink_filtersToSelectedProjectKey_andIsIdempotent() {
		JiraIntegration saga = activeSaga();
		GitCommit commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setMessage("SAGA-12 fix login and ABC-99 noise");
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey("SAGA-12");
		task.setJiraIntegration(saga);
		when(tasks.findByProject_IdAndJiraIntegration_IdInAndExternalKeyIgnoreCaseIn(eq(project.getId()), eq(Set.of(saga.getId())), any())).thenReturn(List.of(task));
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of());
		when(links.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isEqualTo(1);

		TaskGitCommitLink existing = new TaskGitCommitLink();
		existing.setTask(task);
		existing.setGitCommit(commit);
		existing.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		when(links.findByGitCommit_IdIn(any())).thenReturn(List.of(existing));
		assertThat(autoLink.linkCommits(project.getId(), List.of(commit))).isEqualTo(0);
	}

	@Test
	void upsertBatch_usesBoundedShaLookups_notPerCommit() {
		List<CommitDraft> ten = new ArrayList<>();
		List<CommitDraft> hundred = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			CommitDraft draft = new CommitDraft(
					"sha" + i, "msg", java.time.LocalDateTime.of(2026, 6, 1, 12, 0), null, null, "main");
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

		commitsService.upsertBatch(repo, ten);
		int after10 = lookups.get();
		commitsService.upsertBatch(repo, hundred);
		int after100 = lookups.get();

		assertThat(after10).isEqualTo(1);
		assertThat(after100 - after10).isEqualTo(1);
	}
	/** The project's single ACTIVE Jira source with projectKey SAGA. */
	private Task task(JiraIntegration source, String key) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		task.setJiraIntegration(source);
		return task;
	}

	private static TaskGitCommitLink link(Task task, GitCommit commit) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		return link;
	}

	@SuppressWarnings("unchecked")
	private List<TaskGitCommitLink> savedLinks() {
		ArgumentCaptor<List<TaskGitCommitLink>> captor = ArgumentCaptor.forClass(List.class);
		verify(links).saveAll(captor.capture());
		return captor.getValue();
	}

	private JiraIntegration activeSaga() {
		JiraIntegration saga = new JiraIntegration();
		saga.setId(UUID.randomUUID());
		saga.setProjectKey("SAGA");
		saga.setConnectionStatus(com.saga.be.entity.enums.IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findAllByProject_IdAndConnectionStatus(project.getId(), com.saga.be.entity.enums.IntegrationStatus.ACTIVE))
				.thenReturn(List.of(saga));
		return saga;
	}
}

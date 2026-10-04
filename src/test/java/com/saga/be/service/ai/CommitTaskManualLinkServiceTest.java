package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

class CommitTaskManualLinkServiceTest {

	private final UUID projectId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private ProjectDataAuthorization authorization;
	private GitCommitRepository commits;
	private TaskRepository tasks;
	private TaskCommitManualLinkRepository manualLinks;
	private TaskGitCommitLinkRepository links;
	private UserAccountRepository users;
	private CommitAiReviewService reviews;
	private CommitTaskManualLinkService service;
	private GitCommit commit;
	private Task task;

	@BeforeEach
	void setUp() {
		authorization = mock(ProjectDataAuthorization.class);
		commits = mock(GitCommitRepository.class);
		tasks = mock(TaskRepository.class);
		manualLinks = mock(TaskCommitManualLinkRepository.class);
		links = mock(TaskGitCommitLinkRepository.class);
		users = mock(UserAccountRepository.class);
		reviews = mock(CommitAiReviewService.class);
		service = new CommitTaskManualLinkService(authorization, commits, tasks, manualLinks, links, users, reviews);
		Project project = new Project();
		project.setId(projectId);
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		commit = new GitCommit();
		commit.setId(UUID.randomUUID());
		commit.setRepo(repo);
		commit.setParentCount(1);
		task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		when(commits.findFetchedByIdIn(List.of(commit.getId()))).thenReturn(List.of(commit));
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(task.getId(), projectId)).thenReturn(Optional.of(task));
		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of());
		when(manualLinks.findByTask_IdAndGitCommit_Id(any(), any())).thenReturn(Optional.empty());
		when(reviews.canManageLinks(userId, projectId, commit)).thenReturn(true);
		when(users.getReferenceById(userId)).thenReturn(new UserAccount());
	}

	@Test
	void theAuthorOrLeaderAttachesATaskByHand_storedOnlyInTheManualTable() {
		service.link(userId, projectId, commit.getId(), task.getId());

		ArgumentCaptor<TaskCommitManualLink> saved = ArgumentCaptor.forClass(TaskCommitManualLink.class);
		verify(manualLinks).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getTask()).isSameAs(task);
		assertThat(saved.getValue().getGitCommit()).isSameAs(commit);
		assertThat(saved.getValue().getProject().getId()).isEqualTo(projectId);
		// never an automatic (scored) link
		verify(links, never()).save(any());
		verify(links, never()).saveAll(any());
		verify(reviews).detail(userId, projectId, commit.getId());
	}

	@Test
	void anyoneElseIsForbidden() {
		when(reviews.canManageLinks(userId, projectId, commit)).thenReturn(false);
		assertThatThrownBy(() -> service.link(userId, projectId, commit.getId(), task.getId()))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.COMMIT_TASK_LINK_FORBIDDEN);
					assertThat(((IntegrationException) ex).getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				})
				.hasMessageContaining("tác giả của commit hoặc trưởng nhóm");
		verify(manualLinks, never()).saveAndFlush(any());
	}

	@Test
	void nonMembersAreStoppedBeforeAnything() {
		doThrow(new IntegrationException(IntegrationErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, "no"))
				.when(authorization).requireStudentTeamMember(userId, projectId);
		assertThatThrownBy(() -> service.link(userId, projectId, commit.getId(), task.getId())).isInstanceOf(IntegrationException.class);
		verify(manualLinks, never()).saveAndFlush(any());
	}

	@Test
	void aMergeCommitCannotBeAttached() {
		commit.setParentCount(2);
		assertThatThrownBy(() -> service.link(userId, projectId, commit.getId(), task.getId()))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.COMMIT_TASK_LINK_INVALID))
				.hasMessageContaining("Merge commit");
	}

	@Test
	void aTaskOfAnotherProjectOrADeletedTaskIsRejected() {
		UUID foreignTask = UUID.randomUUID();
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(foreignTask, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.link(userId, projectId, commit.getId(), foreignTask))
				.isInstanceOf(IntegrationException.class)
				.hasMessageContaining("Task không thuộc dự án này");
		assertThatThrownBy(() -> service.link(userId, projectId, commit.getId(), null))
				.isInstanceOf(IntegrationException.class)
				.hasMessageContaining("Hãy chọn task");
		verify(manualLinks, never()).saveAndFlush(any());
	}

	@Test
	void aCommitOfAnotherProjectIsNotFound() {
		UUID unknown = UUID.randomUUID();
		when(commits.findFetchedByIdIn(List.of(unknown))).thenReturn(List.of());
		assertThatThrownBy(() -> service.link(userId, projectId, unknown, task.getId()))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
	}

	@Test
	void alreadyLinkedAutomaticallyOrByHand_addsNothing() {
		TaskGitCommitLink automatic = new TaskGitCommitLink();
		automatic.setTask(task);
		automatic.setGitCommit(commit);
		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of(automatic));
		service.link(userId, projectId, commit.getId(), task.getId());

		when(links.findLiveWithTaskByGitCommitIds(any())).thenReturn(List.of());
		when(manualLinks.findByTask_IdAndGitCommit_Id(task.getId(), commit.getId())).thenReturn(Optional.of(new TaskCommitManualLink()));
		service.link(userId, projectId, commit.getId(), task.getId());

		verify(manualLinks, never()).saveAndFlush(any());
	}

	@Test
	void aDoubleClickRaceIsHarmless() {
		when(manualLinks.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uk_task_commit_manual_link"));
		service.link(userId, projectId, commit.getId(), task.getId());
		verify(reviews).detail(userId, projectId, commit.getId());
	}

	@Test
	void unlinkRemovesOnlyAManualAttachmentOfThisProject() {
		TaskCommitManualLink row = new TaskCommitManualLink();
		row.setProject(commit.getRepo().getProject());
		when(manualLinks.findByTask_IdAndGitCommit_Id(task.getId(), commit.getId())).thenReturn(Optional.of(row));

		service.unlink(userId, projectId, commit.getId(), task.getId());

		verify(manualLinks).delete(row);
	}

	@Test
	void unlinkIsGuardedLikeLink() {
		when(reviews.canManageLinks(userId, projectId, commit)).thenReturn(false);
		assertThatThrownBy(() -> service.unlink(userId, projectId, commit.getId(), task.getId())).isInstanceOf(IntegrationException.class);
		verify(manualLinks, never()).delete(any());
	}
}

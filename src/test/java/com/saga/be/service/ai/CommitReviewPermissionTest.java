package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Leader: any commit. Member: only commits of their own tasks (or their own commits). Lecturer: reads only. */
class CommitReviewPermissionTest {

	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final GitCommitRepository commits = mock(GitCommitRepository.class);
	private final TaskGitCommitLinkRepository links = mock(TaskGitCommitLinkRepository.class);
	private final TaskCommitManualLinkRepository manualLinks = mock(TaskCommitManualLinkRepository.class);
	private final CommitReviewPermission permission = new CommitReviewPermission(members, commits, links, manualLinks);

	private final UUID projectId = UUID.randomUUID();
	private final UUID commitId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();

	@Test
	void theLeaderMayReviewAnyCommit() {
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.ALLOWED);
	}

	@Test
	void aMemberMayReviewACommitOfTheirTask_linkedAutomaticallyOrByHand_orTheirOwnCommit() {
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		when(links.existsLiveLinkToTaskAssignedTo(commitId, userId)).thenReturn(true);
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.ALLOWED);

		when(links.existsLiveLinkToTaskAssignedTo(commitId, userId)).thenReturn(false);
		when(manualLinks.existsManualLinkToTaskAssignedTo(commitId, userId)).thenReturn(true);
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.ALLOWED);

		when(manualLinks.existsManualLinkToTaskAssignedTo(commitId, userId)).thenReturn(false);
		when(commits.existsAuthoredBy(commitId, userId)).thenReturn(true);
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.ALLOWED);
	}

	@Test
	void aMemberMayNotReviewSomeoneElsesTaskCommit() {
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.NOT_ALLOWED);
	}

	@Test
	void someoneOutsideTheTeam_theLecturer_onlyReads() {
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.empty());
		assertThat(permission.access(userId, projectId, commitId)).isEqualTo(CommitReviewPermission.Access.READ_ONLY);
	}
}

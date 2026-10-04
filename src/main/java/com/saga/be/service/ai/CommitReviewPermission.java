package com.saga.be.service.ai;

import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Who may ask the AI to (re)review a commit: the team leader any commit; a member only a commit of a
 * task assigned to them (linked automatically or by hand) or one they authored. Everyone else who can
 * read the project (the lecturer) only reads the review. Plain queries, no lazy loading: callers run
 * with and without a transaction.
 */
@Component
@Profile("!test")
public class CommitReviewPermission {

	public enum Access { ALLOWED, READ_ONLY, NOT_ALLOWED }

	private final TeamMemberRepository members;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final TaskCommitManualLinkRepository manualLinks;

	public CommitReviewPermission(TeamMemberRepository members, GitCommitRepository commits,
			TaskGitCommitLinkRepository links, TaskCommitManualLinkRepository manualLinks) {
		this.members = members;
		this.commits = commits;
		this.links = links;
		this.manualLinks = manualLinks;
	}

	public Access access(UUID userId, UUID projectId, UUID commitId) {
		RoleInTeam role = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
		if (role == null) return Access.READ_ONLY;
		if (role == RoleInTeam.LEADER) return Access.ALLOWED;
		boolean own = commits.existsAuthoredBy(commitId, userId)
				|| links.existsLiveLinkToTaskAssignedTo(commitId, userId)
				|| manualLinks.existsManualLinkToTaskAssignedTo(commitId, userId);
		return own ? Access.ALLOWED : Access.NOT_ALLOWED;
	}
}

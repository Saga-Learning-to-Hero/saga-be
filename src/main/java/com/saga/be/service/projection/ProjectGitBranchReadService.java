package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectGitBranchListResponse;
import com.saga.be.dto.project.ProjectGitBranchListResponse.Branch;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Live GitHub branch inventory for one ACTIVE project repository. Reuses {@link
 * GitHubOAuthClient#listBranches} (already fully paginated) instead of a second branch-fetch
 * implementation, and never derives branch inventory from {@code GitCommit.headRef} -- see {@link
 * ProjectGitBranchListResponse} for why that would be incomplete. Provider HTTP (installation
 * token creation + branch listing) happens with no surrounding JDBC transaction, matching {@link
 * com.saga.be.service.sync.GitHubCommitSyncService}'s existing provider-HTTP-outside-JDBC-tx
 * convention.
 */
@Service
@Profile("!test")
public class ProjectGitBranchReadService {

	private final ProjectDataAuthorization authorization;
	private final GitRepoRepository repos;
	private final GithubProjectInstallationRepository projectInstallations;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;

	public ProjectGitBranchReadService(
			ProjectDataAuthorization authorization,
			GitRepoRepository repos,
			GithubProjectInstallationRepository projectInstallations,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt) {
		this.authorization = authorization;
		this.repos = repos;
		this.projectInstallations = projectInstallations;
		this.github = github;
		this.githubJwt = githubJwt;
	}

	/**
	 * Same reader policy as the project commit page ({@link
	 * ProjectDataAuthorization#requireReader}): ACTIVE team member or assigned Lecturer; ADMIN
	 * denied, matching every other project-data read on this surface.
	 */
	public ProjectGitBranchListResponse listBranches(UUID userId, UUID projectId, UUID repoId) {
		authorization.requireReader(userId, projectId);
		GitRepo repo = repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Repository was not found for this project."));
		if (repo.getOwnerLogin() == null || repo.getOwnerLogin().isBlank() || repo.getName() == null || repo.getName().isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Repository metadata is incomplete.");
		}
		GithubInstallation installation = currentInstallation(projectId);
		if (installation == null || installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
					HttpStatus.BAD_REQUEST,
					"GitHub installation is not active.");
		}
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		List<String> names = github.listBranches(token, repo.getOwnerLogin(), repo.getName());
		String defaultBranch = repo.getDefaultBranch();
		List<Branch> branches = names.stream().map(name -> new Branch(name, name.equals(defaultBranch))).toList();
		return new ProjectGitBranchListResponse(repo.getId(), repo.getFullName(), branches.size(), branches);
	}

	private GithubInstallation currentInstallation(UUID projectId) {
		List<GithubProjectInstallation> memberships = projectInstallations.findByProject_IdWithInstallation(projectId);
		if (memberships.isEmpty()) {
			return null;
		}
		return memberships.getFirst().getInstallation();
	}
}

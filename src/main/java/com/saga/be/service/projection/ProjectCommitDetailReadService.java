package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectCommitDetailResponse;
import com.saga.be.dto.project.ProjectCommitDetailResponse.ChangedFile;
import com.saga.be.dto.project.ProjectCommitDetailResponse.Parent;
import com.saga.be.dto.project.ProjectCommitDetailResponse.Stats;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.github.GitHubOAuthClient.CommitDetail;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Live GitHub commit detail for one projected {@link GitCommit}. Authorization and SAGA identity
 * are resolved in short repository transactions; provider HTTP runs afterwards with no surrounding
 * JDBC transaction. GET is side-effect free and does not persist stats or files.
 */
@Service
@Profile("!test")
public class ProjectCommitDetailReadService {

	private final ProjectDataAuthorization authorization;
	private final GitCommitRepository commits;
	private final GithubProjectInstallationRepository projectInstallations;
	private final GitHubOAuthClient github;
	private final GitHubAppJwtService githubJwt;

	public ProjectCommitDetailReadService(
			ProjectDataAuthorization authorization,
			GitCommitRepository commits,
			GithubProjectInstallationRepository projectInstallations,
			GitHubOAuthClient github,
			GitHubAppJwtService githubJwt) {
		this.authorization = authorization;
		this.commits = commits;
		this.projectInstallations = projectInstallations;
		this.github = github;
		this.githubJwt = githubJwt;
	}

	public ProjectCommitDetailResponse getDetail(UUID userId, UUID projectId, UUID gitCommitId) {
		authorization.requireReader(userId, projectId);
		ResolvedCommit resolved = resolve(projectId, gitCommitId);
		GithubInstallation installation = currentInstallation(projectId);
		if (installation == null || installation.getInstallationStatus() != GitHubInstallationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.GITHUB_INSTALLATION_INVALID,
					HttpStatus.BAD_REQUEST,
					"GitHub installation is not active.");
		}
		String token = github.createInstallationToken(githubJwt.createJwt(), installation.getInstallationId());
		CommitDetail provider = github.getCommit(token, resolved.ownerLogin(), resolved.repoName(), resolved.sha());
		if (provider == null || provider.sha() == null || !provider.sha().equalsIgnoreCase(resolved.sha())) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_GATEWAY,
					"GitHub commit could not be loaded.");
		}
		return toResponse(resolved, provider);
	}

	private ResolvedCommit resolve(UUID projectId, UUID gitCommitId) {
		GitCommit commit = commits
				.findFetchedById(gitCommitId)
				.orElseThrow(() -> notFound());
		GitRepo repo = commit.getRepo();
		if (repo == null
				|| repo.getProject() == null
				|| !projectId.equals(repo.getProject().getId())
				|| repo.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw notFound();
		}
		if (repo.getOwnerLogin() == null
				|| repo.getOwnerLogin().isBlank()
				|| repo.getName() == null
				|| repo.getName().isBlank()
				|| commit.getShaHash() == null
				|| commit.getShaHash().isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Repository metadata is incomplete.");
		}
		return new ResolvedCommit(
				commit.getId(),
				repo.getId(),
				repo.getFullName(),
				commit.getShaHash(),
				repo.getOwnerLogin(),
				repo.getName());
	}

	private GithubInstallation currentInstallation(UUID projectId) {
		List<GithubProjectInstallation> memberships = projectInstallations.findByProject_IdWithInstallation(projectId);
		if (memberships.isEmpty()) {
			return null;
		}
		return memberships.getFirst().getInstallation();
	}

	private static ProjectCommitDetailResponse toResponse(ResolvedCommit resolved, CommitDetail provider) {
		Stats stats = provider.stats() == null
				? null
				: new Stats(provider.stats().total(), provider.stats().additions(), provider.stats().deletions());
		List<Parent> parents = provider.parents() == null
				? List.of()
				: provider.parents().stream().map(parent -> new Parent(parent.sha())).toList();
		int parentCount = parents.size();
		List<ChangedFile> files = provider.files() == null
				? List.of()
				: provider.files().stream()
						.map(file -> new ChangedFile(
								file.filename(),
								file.previousFilename(),
								file.status(),
								file.additions(),
								file.deletions(),
								file.changes(),
								file.patch()))
						.toList();
		return new ProjectCommitDetailResponse(
				resolved.gitCommitId(),
				resolved.repoId(),
				resolved.repositoryFullName(),
				resolved.sha(),
				provider.message(),
				provider.authorName(),
				provider.authorLogin(),
				ProjectionMappings.parseInstant(provider.committedAt()),
				provider.htmlUrl(),
				stats,
				parents,
				parentCount,
				parentCount > 1,
				provider.filesTruncated(),
				files);
	}

	private static AcademicException notFound() {
		return new AcademicException(
				AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Commit was not found for this project.");
	}

	private record ResolvedCommit(
			UUID gitCommitId,
			UUID repoId,
			String repositoryFullName,
			String sha,
			String ownerLogin,
			String repoName) {}
}

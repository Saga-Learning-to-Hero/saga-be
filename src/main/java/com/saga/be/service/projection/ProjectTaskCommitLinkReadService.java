package com.saga.be.service.projection;

import com.saga.be.dto.project.ProjectTaskCommitLinksResponse;
import com.saga.be.dto.project.ProjectTaskCommitLinksResponse.Filter;
import com.saga.be.dto.project.ProjectTaskCommitLinksResponse.Link;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitBranchRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Canonical batch read of {@link TaskGitCommitLink} rows. Branch filtering uses {@code
 * git_commit_branch} (REACHABLE_AT_SYNC), never {@code GitCommit.headRef} and never commit-message
 * parsing.
 */
@Service
@Profile("!test")
public class ProjectTaskCommitLinkReadService {

	static final int DEFAULT_PAGE = 0;
	static final int DEFAULT_SIZE = 100;
	static final int MAX_SIZE = 200;

	private final ProjectDataAuthorization authorization;
	private final GitRepoRepository repos;
	private final TaskGitCommitLinkRepository links;
	private final GitCommitBranchRepository memberships;

	public ProjectTaskCommitLinkReadService(
			ProjectDataAuthorization authorization,
			GitRepoRepository repos,
			TaskGitCommitLinkRepository links,
			GitCommitBranchRepository memberships) {
		this.authorization = authorization;
		this.repos = repos;
		this.links = links;
		this.memberships = memberships;
	}

	@Transactional(readOnly = true)
	public ProjectTaskCommitLinksResponse list(
			UUID userId, UUID projectId, UUID repoId, String branchName, Integer page, Integer size) {
		authorization.requireReader(userId, projectId);
		int pageNumber = page == null ? DEFAULT_PAGE : page;
		int pageSize = size == null ? DEFAULT_SIZE : size;
		if (pageNumber < 0 || pageSize < 1 || pageSize > MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + MAX_SIZE + ".");
		}
		String branchFilter = blankToNull(branchName);
		if (branchFilter != null && repoId == null) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"branchName requires repoId.");
		}
		GitRepo filterRepo = null;
		if (repoId != null) {
			filterRepo = repos.findByIdAndProject_IdAndConnectionStatus(repoId, projectId, IntegrationStatus.ACTIVE)
					.orElseThrow(() -> new AcademicException(
							AcademicErrorCode.PROJECT_NOT_FOUND,
							HttpStatus.NOT_FOUND,
							"Repository was not found for this project."));
		}
		PageRequest pageable = PageRequest.of(pageNumber, pageSize);
		Page<UUID> idPage;
		if (filterRepo != null && branchFilter != null) {
			idPage = links.findPageIdsByProjectAndRepoAndBranch(projectId, filterRepo.getId(), branchFilter, pageable);
		} else if (filterRepo != null) {
			idPage = links.findPageIdsByProjectAndRepo(projectId, filterRepo.getId(), pageable);
		} else {
			idPage = links.findPageIdsByProject(projectId, pageable);
		}
		List<UUID> orderedIds = idPage.getContent();
		List<Link> rows = List.of();
		if (!orderedIds.isEmpty()) {
			Map<UUID, Integer> order = new HashMap<>();
			for (int i = 0; i < orderedIds.size(); i++) {
				order.put(orderedIds.get(i), i);
			}
			List<TaskGitCommitLink> fetched = new ArrayList<>(links.findFetchedByIdIn(orderedIds));
			fetched.sort(Comparator.comparingInt(a -> order.getOrDefault(a.getId(), Integer.MAX_VALUE)));
			List<UUID> commitIds = fetched.stream().map(link -> link.getGitCommit().getId()).distinct().toList();
			Map<UUID, List<String>> branchesByCommit = new HashMap<>();
			for (Object[] row : memberships.findCommitIdAndBranchNameByCommitIdIn(commitIds)) {
				UUID commitId = (UUID) row[0];
				String name = (String) row[1];
				branchesByCommit.computeIfAbsent(commitId, ignored -> new ArrayList<>()).add(name);
			}
			rows = fetched.stream().map(link -> toLink(link, branchesByCommit)).toList();
		}
		Filter filter = new Filter(
				filterRepo == null ? null : filterRepo.getId(),
				filterRepo == null ? null : filterRepo.getRepositoryId(),
				filterRepo == null ? null : filterRepo.getFullName(),
				branchFilter,
				ProjectTaskCommitLinksResponse.BRANCH_RESOLUTION_REACHABLE_AT_SYNC,
				filterRepo == null ? null : filterRepo.getBranchMembershipSyncedAt());
		return new ProjectTaskCommitLinksResponse(
				projectId, filter, rows, pageNumber, pageSize, idPage.getTotalElements());
	}

	private static Link toLink(TaskGitCommitLink link, Map<UUID, List<String>> branchesByCommit) {
		GitCommit commit = link.getGitCommit();
		GitRepo repo = commit.getRepo();
		Task task = link.getTask();
		List<String> branchNames = List.copyOf(branchesByCommit.getOrDefault(commit.getId(), List.of()));
		return new Link(
				task.getId(),
				task.getExternalKey(),
				commit.getId(),
				commit.getShaHash(),
				commit.getMessage(),
				repo == null ? null : repo.getId(),
				repo == null ? null : repo.getRepositoryId(),
				repo == null ? null : repo.getFullName(),
				commit.getHeadRef(),
				branchNames,
				link.getCreatedAt(),
				link.getLinkSource() == null ? null : link.getLinkSource().name());
	}

	private static String blankToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}

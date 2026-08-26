package com.saga.be.repository;

import com.saga.be.entity.github.PullRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PullRequestRepository extends JpaRepository<PullRequest, UUID> {

	Optional<PullRequest> findByRepo_IdAndGithubPullRequestId(UUID repoId, Long githubPullRequestId);
}

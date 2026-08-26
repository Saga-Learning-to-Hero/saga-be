package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskPullRequestLink;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskPullRequestLinkRepository extends JpaRepository<TaskPullRequestLink, UUID> {

	boolean existsByTask_IdAndPullRequest_Id(UUID taskId, UUID pullRequestId);
}

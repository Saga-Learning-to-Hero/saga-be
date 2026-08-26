package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskGitCommitLink;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskGitCommitLinkRepository extends JpaRepository<TaskGitCommitLink, UUID> {

	boolean existsByTask_IdAndGitCommit_Id(UUID taskId, UUID gitCommitId);
}

package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskGitCommitLink;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskGitCommitLinkRepository extends JpaRepository<TaskGitCommitLink, UUID> {

	boolean existsByTask_IdAndGitCommit_Id(UUID taskId, UUID gitCommitId);

	@Query(
			"""
			select l from TaskGitCommitLink l
			where l.gitCommit.id in :commitIds
			""")
	List<TaskGitCommitLink> findByGitCommit_IdIn(@Param("commitIds") Collection<UUID> commitIds);
}

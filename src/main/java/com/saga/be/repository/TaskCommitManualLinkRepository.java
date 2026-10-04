package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskCommitManualLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Manual commit-task attachments: read only by the commit review and its AI evidence, never by scoring. */
public interface TaskCommitManualLinkRepository extends JpaRepository<TaskCommitManualLink, UUID> {

	@Query("""
			select l from TaskCommitManualLink l
			join fetch l.task t
			left join fetch t.jiraIntegration
			where l.gitCommit.id in :commitIds and l.project.id = :projectId and t.deletedAt is null
			""")
	List<TaskCommitManualLink> findFetchedByProjectAndCommitIds(
			@Param("projectId") UUID projectId, @Param("commitIds") Collection<UUID> commitIds);

	Optional<TaskCommitManualLink> findByTask_IdAndGitCommit_Id(UUID taskId, UUID gitCommitId);
}

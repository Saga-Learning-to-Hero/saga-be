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

	/** Any hand-made attachment of this commit (a commit belongs to one task). */
	boolean existsByGitCommit_Id(UUID gitCommitId);

	/** The commit's own Jira key now names its task. */
	@org.springframework.data.jpa.repository.Modifying(flushAutomatically = true, clearAutomatically = false)
	@Query("delete from TaskCommitManualLink l where l.gitCommit.id in :commitIds")
	int deleteByGitCommitIds(@Param("commitIds") Collection<UUID> commitIds);

	/** The commit is attached by hand to a live task assigned to this user. */
	@Query(
			"""
			select count(l) > 0 from TaskCommitManualLink l
			where l.gitCommit.id = :commitId
			  and l.task.deletedAt is null
			  and l.task.assigneeStudent.userAccount.id = :userId
			""")
	boolean existsManualLinkToTaskAssignedTo(@Param("commitId") java.util.UUID commitId, @Param("userId") java.util.UUID userId);
}

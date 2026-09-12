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

	@Query(
			"""
			select l.task.id, count(l)
			from TaskGitCommitLink l
			join l.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			group by l.task.id
			""")
	List<Object[]> countLinksByProjectGrouped(@Param("projectId") UUID projectId);

	@Query(
			"""
			select c from TaskGitCommitLink l
			join l.gitCommit c
			join fetch c.repo
			left join fetch c.authorStudent
			join l.task t
			where t.id = :taskId
			  and t.project.id = :projectId
			order by coalesce(c.committedAt, c.createdAt) desc
			""")
	List<com.saga.be.entity.github.GitCommit> findFetchedCommitsByProjectAndTask(
			@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

	/**
	 * Progress dashboard: per-student linked-commit attribution for a project — {@code Object[]{UUID
	 * studentId, Long distinctLinkedCommitCount, Long distinctLinkedTaskCount}}. Distinct-counts both
	 * columns so a commit linked to multiple tasks is never double-counted as more than one linked
	 * commit, while still counting every distinct task it is linked to.
	 */
	@Query(
			"""
			select c.authorStudent.id, count(distinct c.id), count(distinct l.task.id)
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where t.project.id = :projectId
			  and c.authorStudent is not null
			  and t.deletedAt is null
			group by c.authorStudent.id
			""")
	List<Object[]> countLinkedCommitsAndTasksGroupedByAuthorStudent(@Param("projectId") UUID projectId);

	@Query(
			"""
			select count(distinct l.gitCommit.id)
			from TaskGitCommitLink l
			join l.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			""")
	long countDistinctLinkedCommitsByProject_Id(@Param("projectId") UUID projectId);
}

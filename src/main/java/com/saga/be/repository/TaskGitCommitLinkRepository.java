package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskGitCommitLink;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
			select l from TaskGitCommitLink l
			join fetch l.task t
			join fetch l.gitCommit
			where t.project.id = :projectId
			  and t.deletedAt is null
			""")
	List<TaskGitCommitLink> findFetchedByProject_Id(@Param("projectId") UUID projectId);

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
			  and (c.parentCount is null or c.parentCount <= 1)
			order by coalesce(c.committedAt, c.createdAt) desc
			""")
	List<com.saga.be.entity.github.GitCommit> findFetchedCommitsByProjectAndTask(
			@Param("projectId") UUID projectId, @Param("taskId") UUID taskId);

	/**
	 * Task-commit list IDs through canonical {@code task_git_commit_link} only. Excludes known
	 * merges ({@code parent_count > 1}); UNKNOWN/root/normal remain. Sort is in JPQL; callers must
	 * pass an unsorted {@link Pageable}. Count is {@code count(distinct c.id)} so duplicate link
	 * rows cannot inflate total.
	 */
	@Query(
			value =
					"""
					select c.id
					from TaskGitCommitLink l
					join l.gitCommit c
					join l.task t
					where t.id = :taskId
					  and t.project.id = :projectId
					  and t.deletedAt is null
					  and (c.parentCount is null or c.parentCount <= 1)
					order by coalesce(c.committedAt, c.createdAt) desc, c.id desc
					""",
			countQuery =
					"""
					select count(distinct c.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					join l.task t
					where t.id = :taskId
					  and t.project.id = :projectId
					  and t.deletedAt is null
					  and (c.parentCount is null or c.parentCount <= 1)
					""")
	Page<UUID> findPageIdsByProjectAndTask(
			@Param("projectId") UUID projectId, @Param("taskId") UUID taskId, Pageable pageable);

	long countByTask_Id(UUID taskId);

	@Query(
			value =
					"""
					select c.id
					from TaskGitCommitLink l
					join l.gitCommit c
					where l.task.id = :taskId
					  and (c.parentCount is null or c.parentCount <= 1)
					order by c.committedAt desc, c.id desc
					""",
			countQuery =
					"""
					select count(l.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					where l.task.id = :taskId
					  and (c.parentCount is null or c.parentCount <= 1)
					""")
	Page<UUID> findLinkedCommitIdsByTaskId(@Param("taskId") UUID taskId, Pageable pageable);

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
			  and (c.parentCount is null or c.parentCount <= 1)
			group by c.authorStudent.id
			""")
	List<Object[]> countLinkedCommitsAndTasksGroupedByAuthorStudent(@Param("projectId") UUID projectId);

	@Query(
			"""
			select count(distinct l.gitCommit.id)
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	long countDistinctLinkedCommitsByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			value =
					"""
					select l.id
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					order by coalesce(c.committedAt, c.createdAt) desc, c.shaHash asc, t.id asc
					""",
			countQuery =
					"""
					select count(l.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					""")
	Page<UUID> findPageIdsByProject(@Param("projectId") UUID projectId, Pageable pageable);

	@Query(
			value =
					"""
					select l.id
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					  and r.id = :repoId
					order by coalesce(c.committedAt, c.createdAt) desc, c.shaHash asc, t.id asc
					""",
			countQuery =
					"""
					select count(l.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					  and r.id = :repoId
					""")
	Page<UUID> findPageIdsByProjectAndRepo(
			@Param("projectId") UUID projectId, @Param("repoId") UUID repoId, Pageable pageable);

	@Query(
			value =
					"""
					select l.id
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					  and r.id = :repoId
					  and exists (
					    select 1 from GitCommitBranch b
					    where b.commit.id = c.id and b.branchName = :branchName
					  )
					order by coalesce(c.committedAt, c.createdAt) desc, c.shaHash asc, t.id asc
					""",
			countQuery =
					"""
					select count(l.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					join c.repo r
					join l.task t
					where t.project.id = :projectId
					  and t.deletedAt is null
					  and r.id = :repoId
					  and exists (
					    select 1 from GitCommitBranch b
					    where b.commit.id = c.id and b.branchName = :branchName
					  )
					""")
	Page<UUID> findPageIdsByProjectAndRepoAndBranch(
			@Param("projectId") UUID projectId,
			@Param("repoId") UUID repoId,
			@Param("branchName") String branchName,
			Pageable pageable);

	@Query(
			"""
			select distinct l
			from TaskGitCommitLink l
			join fetch l.gitCommit c
			join fetch c.repo r
			join fetch l.task t
			where l.id in :ids
			""")
	List<TaskGitCommitLink> findFetchedByIdIn(@Param("ids") Collection<UUID> ids);

	/**
	 * Sprint activity: commit ids linked to a non-deleted task in a sprint —
	 * {@code Object[]{UUID sprintId, UUID commitId}}. A commit linked to several tasks in the same
	 * sprint appears more than once; callers must distinct-count per sprint.
	 */
	@Query(
			"""
			select t.sprint.id, c.id
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.sprint is not null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findLinkedCommitIdsBySprint(@Param("projectId") UUID projectId);

	/** Personal sprint activity: same shape as {@link #findLinkedCommitIdsBySprint}, authored by {@code studentId}. */
	@Query(
			"""
			select t.sprint.id, c.id
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.sprint is not null
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findLinkedCommitIdsBySprintAndAuthor(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);
}

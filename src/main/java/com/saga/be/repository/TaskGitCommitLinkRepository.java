package com.saga.be.repository;

import com.saga.be.entity.traceability.TaskGitCommitLink;
import java.time.LocalDateTime;
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

	/** Bounded, most-recent-first linked commits for one task (Task Intelligence evidence). */
	@Query("select l from TaskGitCommitLink l join fetch l.gitCommit where l.task.id = :taskId order by l.gitCommit.committedAt desc, l.gitCommit.id desc")
	List<TaskGitCommitLink> findByTask_IdOrderByGitCommit_CommittedAtDesc(@Param("taskId") UUID taskId, Pageable pageable);

	@Query(
			"""
			select l from TaskGitCommitLink l
			where l.gitCommit.id in :commitIds
			""")
	List<TaskGitCommitLink> findByGitCommit_IdIn(@Param("commitIds") Collection<UUID> commitIds);

	/** Automatic links of these commits to tasks that still exist, with the task (commit review display). */
	@Query(
			"""
			select l from TaskGitCommitLink l
			join fetch l.task t
			where l.gitCommit.id in :commitIds and t.deletedAt is null
			""")
	List<TaskGitCommitLink> findLiveWithTaskByGitCommitIds(@Param("commitIds") Collection<UUID> commitIds);

	@Query(
			"""
			select l from TaskGitCommitLink l
			join fetch l.task t
			join fetch t.jiraIntegration
			join t.project p
			where l.gitCommit.id = :commitId and p.id = :projectId
			""")
	List<TaskGitCommitLink> findAnalysisEvidenceByGitCommitId(@Param("commitId") UUID commitId, @Param("projectId") UUID projectId);

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

	/**
	 * Distinct linked non-merge commits per active task ({@code parentCount} null/0/1): the commit
	 * proof a code/test task needs. {@code Object[]{UUID taskId, Long count}}.
	 */
	@Query(
			"""
			select l.task.id, count(distinct c.id)
			from TaskGitCommitLink l
			join l.task t
			join l.gitCommit c
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			group by l.task.id
			""")
	List<Object[]> countV23LinksByProjectGrouped(@Param("projectId") UUID projectId);

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

	/** {@link #findPageIdsByProjectAndTask} with known merges too: total equals linkedCommitCount. */
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
					""")
	Page<UUID> findPageIdsByProjectAndTaskIncludingMerges(
			@Param("projectId") UUID projectId, @Param("taskId") UUID taskId, Pageable pageable);

	/**
	 * V23 task-link page for the work-session timeline. Same coding predicate and coalesce sort as
	 * {@link #findPageIdsByProjectAndTask}, but returns {@code TaskGitCommitLink.id} so callers can
	 * surface {@code linkSource} / {@code createdAt} without a second identity lookup. Sort is in
	 * JPQL; callers must pass an unsorted {@link Pageable}.
	 */
	@Query(
			value =
					"""
					select l.id
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
					select count(l.id)
					from TaskGitCommitLink l
					join l.gitCommit c
					join l.task t
					where t.id = :taskId
					  and t.project.id = :projectId
					  and t.deletedAt is null
					  and (c.parentCount is null or c.parentCount <= 1)
					""")
	Page<UUID> findPageLinkIdsByProjectAndTaskV23(
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

	/** Timeline fetch: link + commit + repo + optional mapped author. */
	@Query(
			"""
			select distinct l
			from TaskGitCommitLink l
			join fetch l.gitCommit c
			join fetch c.repo r
			left join fetch c.authorStudent author
			left join fetch author.userAccount
			where l.id in :ids
			""")
	List<TaskGitCommitLink> findFetchedWithAuthorByIdIn(@Param("ids") Collection<UUID> ids);

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

	/**
	 * Distinct in-scope commits that have a canonical link to an in-scope (non-deleted) Task.
	 * Known merges ({@code parentCount > 1}) are excluded.
	 */
	@Query(
			"""
			select count(distinct c.id)
			from TaskGitCommitLink l
			join l.gitCommit c
			join c.repo r
			join r.project p
			join p.course course
			join l.task t
			where course.semester.id = :semesterId
			  and course.deletedAt is null
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	long countDistinctLinkedTraceableByCourseSemester(@Param("semesterId") UUID semesterId);

	/** Distinct V23-included in-scope commits that currently have a canonical link to an active Task. */
	@Query(
			"""
			select distinct c.id
			from TaskGitCommitLink l
			join l.gitCommit c
			join c.repo r
			join r.project p
			join p.course course
			join l.task t
			where course.semester.id = :semesterId
			  and course.deletedAt is null
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<UUID> findDistinctLinkedTraceableIdsByCourseSemester(@Param("semesterId") UUID semesterId);

	/**
	 * Distinct authored V23 commits that have at least one canonical link to a non-deleted task.
	 * A commit linked to several tasks counts once.
	 */
	@Query(
			"""
			select count(distinct c.id)
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	long countDistinctLinkedAuthoredV23(@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/**
	 * Same as {@link #countDistinctLinkedAuthoredV23} but only commits whose raw {@code committedAt}
	 * falls in {@code [rangeStart, rangeEndExclusive)}. No {@code createdAt} fallback.
	 */
	@Query(
			"""
			select count(distinct c.id)
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			  and c.committedAt is not null
			  and c.committedAt >= :rangeStart
			  and c.committedAt < :rangeEndExclusive
			  and (exists (
			    select 1 from TaskGitCommitLink sl join sl.task st
			    where sl.gitCommit = c and st.deletedAt is null and st.jiraIntegration.id = :jiraIntegrationId)
			  or not exists (
			    select 1 from TaskGitCommitLink ol join ol.task ot
			    where ol.gitCommit = c and ot.deletedAt is null and ot.jiraIntegration.id <> :jiraIntegrationId))
			""")
	long countDistinctLinkedAuthoredV23InRange(
			@Param("projectId") UUID projectId,
			@Param("studentId") UUID studentId,
			@Param("rangeStart") LocalDateTime rangeStart,
			@Param("rangeEndExclusive") LocalDateTime rangeEndExclusive,
			@Param("jiraIntegrationId") UUID jiraIntegrationId);

	/**
	 * Student dashboard preview: every link of these tasks with its commit, newest first per task --
	 * {@code Object[]{UUID taskId, UUID commitId, String sha, String message, LocalDateTime committedAt,
	 * String repoFullName, UUID authorStudentId, String authorExternalId, Integer parentCount}}.
	 * Merges included, so the row count per task equals the raw link count.
	 */
	@Query(
			"""
			select t.id, c.id, c.shaHash, c.message, c.committedAt, r.fullName, a.id, c.authorExternalId, c.parentCount
			from TaskGitCommitLink l
			join l.task t
			join l.gitCommit c
			join c.repo r
			left join c.authorStudent a
			where t.id in :taskIds
			order by t.id asc, coalesce(c.committedAt, c.createdAt) desc, c.id desc
			""")
	List<Object[]> findLinkedCommitRowsByTaskIds(@Param("taskIds") Collection<UUID> taskIds);

	/**
	 * Bulk linkedTaskKeys for recent commits —
	 * {@code Object[]{UUID commitId, String externalKey}}. Null keys and soft-deleted tasks omitted.
	 */
	@Query(
			"""
			select c.id, t.externalKey
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where c.id in :commitIds
			  and t.deletedAt is null
			  and t.externalKey is not null
			order by t.externalKey asc, t.id asc
			""")
	List<Object[]> findExternalKeysByCommitIds(@Param("commitIds") Collection<UUID> commitIds);

	/** Lecturer dashboard: {@code Object[]{UUID sprintId, UUID commitId, UUID taskId}}. V23 commits. */
	@Query(
			"""
			select t.sprint.id, c.id, t.id
			from TaskGitCommitLink l
			join l.gitCommit c
			join l.task t
			where t.sprint.id in :sprintIds
			  and t.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findLinkedCommitAndTaskIdsBySprintIds(@Param("sprintIds") Collection<UUID> sprintIds);

	/** [count, first, last] committedAt of a task's linked non-merge commits (delay case evidence). */
	@Query(
			"""
			select count(c), min(c.committedAt), max(c.committedAt)
			from TaskGitCommitLink l join l.gitCommit c
			where l.task.id = :taskId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> summarizeNonMergeCommitsByTask(@Param("taskId") UUID taskId);

	/** The commit is automatically linked to a live task assigned to this user. */
	@Query(
			"""
			select count(l) > 0 from TaskGitCommitLink l
			where l.gitCommit.id = :commitId
			  and l.task.deletedAt is null
			  and l.task.assigneeStudent.userAccount.id = :userId
			""")
	boolean existsLiveLinkToTaskAssignedTo(@Param("commitId") java.util.UUID commitId, @Param("userId") java.util.UUID userId);
}

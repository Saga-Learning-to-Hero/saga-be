package com.saga.be.repository;

import com.saga.be.entity.github.GitCommit;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitCommitRepository extends JpaRepository<GitCommit, UUID> {

	Optional<GitCommit> findByRepo_IdAndShaHash(UUID repoId, String shaHash);

	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo r
			join fetch r.project
			where c.id = :id
			""")
	Optional<GitCommit> findFetchedById(@Param("id") UUID id);

	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo r
			join fetch r.project
			left join fetch r.installation
			left join fetch c.authorStudent
			where c.id = :id
			""")
	Optional<GitCommit> findAnalysisTargetById(@Param("id") UUID id);

	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo
			left join fetch c.authorStudent author
			left join fetch author.userAccount
			where c.id in :ids
			""")
	List<GitCommit> findFetchedByIdIn(@Param("ids") Collection<UUID> ids);

	List<GitCommit> findByRepo_IdAndShaHashIn(UUID repoId, Collection<String> shaHashes);

	/** Fill a parent count still unknown (push webhooks do not carry it); never overwrites a known one. */
	@org.springframework.data.jpa.repository.Modifying
	@Query("update GitCommit c set c.parentCount = :parentCount where c.id = :id and c.parentCount is null")
	int setParentCountIfUnknown(@Param("id") UUID id, @Param("parentCount") Integer parentCount);

	/** SHAs of this repo already stored -- lets an incremental GitHub sync stop at known history. */
	@Query("select c.shaHash from GitCommit c where c.repo.id = :repoId and c.shaHash in :shaHashes")
	List<String> findShaHashesByRepoIdAndShaHashIn(
			@Param("repoId") UUID repoId, @Param("shaHashes") Collection<String> shaHashes);

	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo r
			left join fetch c.authorStudent
			where r.project.id = :projectId
			order by coalesce(c.committedAt, c.createdAt) desc
			""")
	List<GitCommit> findFetchedByProject_Id(@Param("projectId") UUID projectId);

	/**
	 * Raw project history IDs (includes known merges and UNKNOWN parentCount). Sort is in JPQL;
	 * callers must pass an unsorted {@link Pageable}.
	 */
	@Query(
			value =
					"""
					select c.id
					from GitCommit c
					where c.repo.project.id = :projectId
					order by coalesce(c.committedAt, c.createdAt) desc, c.id desc
					""",
			countQuery =
					"""
					select count(c.id)
					from GitCommit c
					where c.repo.project.id = :projectId
					""")
	Page<UUID> findPageIdsByProject(@Param("projectId") UUID projectId, Pageable pageable);

	/**
	 * Same order as {@link #findPageIdsByProject}, narrowed by optional filters (null = no filter):
	 * {@code authorStudentId} keeps commits mapped to that team member; {@code jiraIntegrationId}
	 * and {@code sprintId} keep commits linked (task_git_commit_link) to a live task of that Jira
	 * source / sprint -- both on the same task when both are given.
	 */
	@Query(
			value =
					"""
					select c.id
					from GitCommit c
					where c.repo.project.id = :projectId
					  and (:authorStudentId is null or c.authorStudent.id = :authorStudentId)
					  and ((:jiraIntegrationId is null and :sprintId is null) or exists (
					    select 1
					    from TaskGitCommitLink l
					    join l.task t
					    where l.gitCommit = c
					      and t.project.id = :projectId
					      and t.deletedAt is null
					      and (:jiraIntegrationId is null or t.jiraIntegration.id = :jiraIntegrationId)
					      and (:sprintId is null or t.sprint.id = :sprintId)))
					order by coalesce(c.committedAt, c.createdAt) desc, c.id desc
					""",
			countQuery =
					"""
					select count(c.id)
					from GitCommit c
					where c.repo.project.id = :projectId
					  and (:authorStudentId is null or c.authorStudent.id = :authorStudentId)
					  and ((:jiraIntegrationId is null and :sprintId is null) or exists (
					    select 1
					    from TaskGitCommitLink l
					    join l.task t
					    where l.gitCommit = c
					      and t.project.id = :projectId
					      and t.deletedAt is null
					      and (:jiraIntegrationId is null or t.jiraIntegration.id = :jiraIntegrationId)
					      and (:sprintId is null or t.sprint.id = :sprintId)))
					""")
	Page<UUID> findPageIdsByProjectFiltered(
			@Param("projectId") UUID projectId,
			@Param("authorStudentId") UUID authorStudentId,
			@Param("jiraIntegrationId") UUID jiraIntegrationId,
			@Param("sprintId") UUID sprintId,
			Pageable pageable);

	@Query(
			"""
			select count(c)
			from GitCommit c
			where c.repo.project.id = :projectId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	long countByRepo_Project_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			select max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id = :projectId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	LocalDateTime findMaxCommittedAtByProject_Id(@Param("projectId") UUID projectId);

	/** Progress dashboard: one row per student — {@code Object[]{UUID studentId, Long count, LocalDateTime lastCommittedAt}}. */
	@Query(
			"""
			select c.authorStudent.id, count(c), max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent is not null
			  and (c.parentCount is null or c.parentCount <= 1)
			group by c.authorStudent.id
			""")
	List<Object[]> countAndMaxCommittedAtGroupedByAuthorStudent(@Param("projectId") UUID projectId);

	/**
	 * Sprint activity: commit id + timestamp for date-window unlinked counts —
	 * {@code Object[]{UUID commitId, LocalDateTime committedAt}}.
	 */
	@Query(
			"""
			select c.id, coalesce(c.committedAt, c.createdAt)
			from GitCommit c
			where c.repo.project.id = :projectId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findIdAndCommittedAtByProject(@Param("projectId") UUID projectId);

	/** Personal sprint activity: same shape as {@link #findIdAndCommittedAtByProject}, authored by {@code studentId}. */
	@Query(
			"""
			select c.id, coalesce(c.committedAt, c.createdAt)
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findIdAndCommittedAtByProjectAndAuthor(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/** Heatmap: {@code Object[]{UUID studentId, LocalDateTime committedAt}}. */
	@Query(
			"""
			select c.authorStudent.id, coalesce(c.committedAt, c.createdAt)
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent is not null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findAuthorAndCommittedAtByProject(@Param("projectId") UUID projectId);

	/** Progress dashboard (Lecturer course overview): one row per project — {@code Object[]{UUID projectId, Long count, LocalDateTime lastCommittedAt}}. */
	@Query(
			"""
			select c.repo.project.id, count(c), max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id in :projectIds
			  and (c.parentCount is null or c.parentCount <= 1)
			group by c.repo.project.id
			""")
	List<Object[]> countAndMaxCommittedAtGroupedByProjects(@Param("projectIds") Collection<UUID> projectIds);

	/** Raw provider sync volume: every git_commit on in-scope projects, including known merges. */
	@Query(
			"""
			select count(c.id)
			from GitCommit c
			join c.repo r
			join r.project p
			join p.course course
			where course.semester.id = :semesterId
			  and course.deletedAt is null
			""")
	long countRawByCourseSemester(@Param("semesterId") UUID semesterId);

	/** V23 activity denominator: UNKNOWN / root / normal only ({@code parentCount} null or {@code <= 1}). */
	@Query(
			"""
			select count(c.id)
			from GitCommit c
			join c.repo r
			join r.project p
			join p.course course
			where course.semester.id = :semesterId
			  and course.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	long countTraceableByCourseSemester(@Param("semesterId") UUID semesterId);

	/**
	 * Phase B weekly activity: V23-included commits only ({@code parentCount} null or {@code <= 1}).
	 * Lightweight {@code Object[]{UUID commitId, LocalDateTime effectiveTimestamp}}.
	 */
	@Query(
			"""
			select c.id, coalesce(c.committedAt, c.createdAt)
			from GitCommit c
			join c.repo r
			join r.project p
			join p.course course
			where course.semester.id = :semesterId
			  and course.deletedAt is null
			  and (c.parentCount is null or c.parentCount <= 1)
			  and coalesce(c.committedAt, c.createdAt) >= :startInclusive
			  and coalesce(c.committedAt, c.createdAt) < :endExclusive
			""")
	List<Object[]> findActivityIdAndTimestampByCourseSemester(
			@Param("semesterId") UUID semesterId,
			@Param("startInclusive") LocalDateTime startInclusive,
			@Param("endExclusive") LocalDateTime endExclusive);

	/**
	 * Student dashboard personal commit metrics — {@code Object[]{Long total, LocalDateTime lastAt}}.
	 * Always one row. V23-included authored commits only.
	 */
	@Query(
			"""
			select count(c), max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> countAndMaxCommittedAtByProjectAndAuthor(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/**
	 * Recent personal V23 commits with repo fetched. Sort is in JPQL; pass an unsorted pageable.
	 */
	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			order by coalesce(c.committedAt, c.createdAt) desc, c.id desc
			""")
	List<GitCommit> findRecentAuthoredV23ByProject(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId, Pageable pageable);

	/**
	 * Recent personal V23 commits whose raw {@code committedAt} falls in
	 * {@code [rangeStart, rangeEndExclusive)} (the student dashboard's selected-sprint window),
	 * repo fetched. No {@code createdAt} fallback. Pass an unsorted pageable.
	 */
	@Query(
			"""
			select c from GitCommit c
			join fetch c.repo
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
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
			order by c.committedAt desc, c.id desc
			""")
	List<GitCommit> findRecentAuthoredV23ByProjectInRange(
			@Param("projectId") UUID projectId,
			@Param("studentId") UUID studentId,
			@Param("rangeStart") LocalDateTime rangeStart,
			@Param("rangeEndExclusive") LocalDateTime rangeEndExclusive,
			@Param("jiraIntegrationId") UUID jiraIntegrationId,
			Pageable pageable);

	/**
	 * A selected sprint's commits: {@code Object[]{UUID id, LocalDateTime committedAt}} in the window,
	 * leaving out commits linked only to tasks of another Jira source (a project may have several, and
	 * their sprints may share dates). Commits linked to nothing stay: they belong to no source.
	 */
	@Query(
			"""
			select c.id, c.committedAt
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
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
	List<Object[]> findSprintCommittedAtByProjectAndAuthor(
			@Param("projectId") UUID projectId,
			@Param("studentId") UUID studentId,
			@Param("rangeStart") LocalDateTime rangeStart,
			@Param("rangeEndExclusive") LocalDateTime rangeEndExclusive,
			@Param("jiraIntegrationId") UUID jiraIntegrationId);

	/**
	 * Student dashboard weekly commits — {@code Object[]{UUID id, LocalDateTime committedAt}}.
	 * {@code committedAt} only (nulls excluded). V23 authored rows on the team project.
	 */
	@Query(
			"""
			select c.id, c.committedAt
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			  and c.committedAt is not null
			  and c.committedAt >= :rangeStart
			  and c.committedAt < :rangeEndExclusive
			""")
	List<Object[]> findWeeklyCommittedAtByProjectAndAuthor(
			@Param("projectId") UUID projectId,
			@Param("studentId") UUID studentId,
			@Param("rangeStart") LocalDateTime rangeStart,
			@Param("rangeEndExclusive") LocalDateTime rangeEndExclusive);

	/**
	 * Ghosting activity: mapped V23 authored commits whose raw {@code committedAt} falls in the
	 * academic calendar window. No {@code createdAt} fallback.
	 */
	@Query(
			"""
			select case when count(c) > 0 then true else false end
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent.id = :studentId
			  and (c.parentCount is null or c.parentCount <= 1)
			  and c.committedAt is not null
			  and c.committedAt >= :windowStart
			  and c.committedAt < :windowEndExclusive
			""")
	boolean existsAuthoredV23CommittedAtInRange(
			@Param("projectId") UUID projectId,
			@Param("studentId") UUID studentId,
			@Param("windowStart") LocalDateTime windowStart,
			@Param("windowEndExclusive") LocalDateTime windowEndExclusive);

	/**
	 * Lecturer dashboard: {@code Object[]{UUID projectId, UUID commitId, LocalDateTime committedAt}}.
	 * V23 coding commits only.
	 */
	@Query(
			"""
			select c.repo.project.id, c.id, coalesce(c.committedAt, c.createdAt)
			from GitCommit c
			where c.repo.project.id in :projectIds
			  and (c.parentCount is null or c.parentCount <= 1)
			""")
	List<Object[]> findProjectIdAndIdAndCommittedAtByProjectIds(@Param("projectIds") Collection<UUID> projectIds);

	/** Non-merge commits per author for the progress report: {@code Object[]{UUID projectId,
	 * UUID authorStudentId (null = unmapped GitHub author), Long commits, LocalDateTime lastCommittedAt}}. */
	@Query(
			"""
			select r.project.id, a.id, count(c), max(c.committedAt)
			from GitCommit c
			join c.repo r
			left join c.authorStudent a
			where r.project.id in :projectIds
			  and (c.parentCount is null or c.parentCount <= 1)
			  and (c.message is null or c.message not like 'Merge %')
			group by r.project.id, a.id
			""")
	List<Object[]> countProgressReportCommits(@Param("projectIds") java.util.Collection<UUID> projectIds);

	/** Non-merge commits attached to no live task (automatic or manual), per author:
	 * {@code Object[]{UUID projectId, UUID authorStudentId, Long commits}}. */
	@Query(
			"""
			select r.project.id, a.id, count(c)
			from GitCommit c
			join c.repo r
			left join c.authorStudent a
			where r.project.id in :projectIds
			  and (c.parentCount is null or c.parentCount <= 1)
			  and (c.message is null or c.message not like 'Merge %')
			  and not exists (
			      select 1 from TaskGitCommitLink l
			      where l.gitCommit = c and l.task.deletedAt is null)
			  and not exists (
			      select 1 from TaskCommitManualLink m
			      where m.gitCommit = c and m.task.deletedAt is null)
			group by r.project.id, a.id
			""")
	List<Object[]> countProgressReportUnlinkedCommits(@Param("projectIds") java.util.Collection<UUID> projectIds);
}

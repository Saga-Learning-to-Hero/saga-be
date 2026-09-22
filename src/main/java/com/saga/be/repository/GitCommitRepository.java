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
			left join fetch c.authorStudent
			where c.id in :ids
			""")
	List<GitCommit> findFetchedByIdIn(@Param("ids") Collection<UUID> ids);

	List<GitCommit> findByRepo_IdAndShaHashIn(UUID repoId, Collection<String> shaHashes);

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
}

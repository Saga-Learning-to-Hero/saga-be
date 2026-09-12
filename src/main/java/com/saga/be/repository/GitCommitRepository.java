package com.saga.be.repository;

import com.saga.be.entity.github.GitCommit;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitCommitRepository extends JpaRepository<GitCommit, UUID> {

	Optional<GitCommit> findByRepo_IdAndShaHash(UUID repoId, String shaHash);

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

	long countByRepo_Project_Id(UUID projectId);

	@Query(
			"select max(coalesce(c.committedAt, c.createdAt)) from GitCommit c where c.repo.project.id = :projectId")
	LocalDateTime findMaxCommittedAtByProject_Id(@Param("projectId") UUID projectId);

	/** Progress dashboard: one row per student — {@code Object[]{UUID studentId, Long count, LocalDateTime lastCommittedAt}}. */
	@Query(
			"""
			select c.authorStudent.id, count(c), max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id = :projectId
			  and c.authorStudent is not null
			group by c.authorStudent.id
			""")
	List<Object[]> countAndMaxCommittedAtGroupedByAuthorStudent(@Param("projectId") UUID projectId);

	/** Progress dashboard (Lecturer course overview): one row per project — {@code Object[]{UUID projectId, Long count, LocalDateTime lastCommittedAt}}. */
	@Query(
			"""
			select c.repo.project.id, count(c), max(coalesce(c.committedAt, c.createdAt))
			from GitCommit c
			where c.repo.project.id in :projectIds
			group by c.repo.project.id
			""")
	List<Object[]> countAndMaxCommittedAtGroupedByProjects(@Param("projectIds") Collection<UUID> projectIds);
}

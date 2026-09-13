package com.saga.be.repository;

import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GitRepoRepository extends JpaRepository<GitRepo, UUID> {

	List<GitRepo> findByProject_Id(UUID projectId);

	@Query(
			"""
			select r from GitRepo r
			left join fetch r.installation
			where r.project.id = :projectId
			""")
	List<GitRepo> findByProject_IdWithInstallation(@Param("projectId") UUID projectId);

	/**
	 * This project's own row for a specific physical repository, regardless of status -- safe as a
	 * singular lookup because of {@code uk_git_repo_project_provider_repository} (V15): one project
	 * can never hold two rows for the same {@code (provider, repositoryId)}.
	 */
	Optional<GitRepo> findByProject_IdAndProviderAndRepositoryId(UUID projectId, GitProvider provider, Long repositoryId);

	/**
	 * Since V15, {@code (provider, repository_id)} is unique only among ACTIVE rows ({@code
	 * uk_git_repo_active_provider_repository}) -- multiple REVOKED rows may legitimately share a
	 * physical repository across different projects. A lookup that ignores {@code
	 * connectionStatus} is no longer safely singular and would risk {@code
	 * NonUniqueResultException}; always scope this by {@code connectionStatus}.
	 */
	Optional<GitRepo> findByConnectionStatusAndProviderAndRepositoryId(
			IntegrationStatus connectionStatus, GitProvider provider, Long repositoryId);

	@Query(
			"""
			select r from GitRepo r
			join fetch r.project
			left join fetch r.installation
			where r.provider = :provider
			  and r.repositoryId = :repositoryId
			  and r.connectionStatus = :status
			""")
	List<GitRepo> findFetchedActiveByProviderAndRepositoryId(
			@Param("provider") GitProvider provider,
			@Param("repositoryId") Long repositoryId,
			@Param("status") IntegrationStatus status);

	List<GitRepo> findByProject_IdAndConnectionStatus(UUID projectId, IntegrationStatus status);

	/**
	 * Scopes a single repository lookup to both its owning project AND {@code connectionStatus} so
	 * a REVOKED historical row (kept for audit/history under V15's relaxed cross-project cardinality)
	 * can never be resolved as a project's CURRENT branch/commit source, and a repository belonging
	 * to a different project is never returned either.
	 */
	Optional<GitRepo> findByIdAndProject_IdAndConnectionStatus(UUID id, UUID projectId, IntegrationStatus status);

	/**
	 * Whether some OTHER SAGA project's {@code git_repo} row for this exact physical repository was
	 * created STRICTLY BEFORE {@code createdAt} -- regardless of that other row's current status
	 * (ACTIVE or REVOKED). An EXISTS-style check, safe under V15's relaxed cardinality (unlike a
	 * singular lookup).
	 *
	 * <p>The {@code createdAt} bound is deliberate and load-bearing: "some other project has EVER
	 * owned this repository" is NOT the same question as "was THIS row created as a later
	 * cross-project claim." Without the temporal bound, a first-ever owner (A, claimed at T1) whose
	 * physical repository is LATER claimed by another project (B, at T2 > T1) would incorrectly
	 * start requiring a claim cutoff itself merely because B's row now exists somewhere in history
	 * -- even on A's ORIGINAL row, reused on reconnect, whose own {@code createdAt} (T1) never
	 * changes. Ordering by {@code createdAt} instead correctly answers "did another project already
	 * own this repository before THIS row came into existence": A (T1) never sees a qualifying
	 * other row (B's T2 is not {@code < T1}) and stays cutoff-free forever, including through any
	 * A -> B -> A cycle; B (T2) always sees A's T1 and stays cutoff-protected forever.
	 *
	 * <p>{@code created_at} is an immutable, Hibernate-managed {@code @CreationTimestamp} (never
	 * substituted or backdated) at {@code DATETIME(6)} / {@code LocalDateTime} microsecond
	 * precision. Two DIFFERENT projects' rows for the same physical repository are only ever
	 * created via separate, causally-sequential HTTP requests (one project must disconnect before
	 * another can claim an ACTIVE source) so an exact-tie collision is not a realistic concern; a
	 * tie under strict {@code <} simply leaves both sides not-cutoff-required, which fails toward
	 * preserving history rather than wrongly discarding it. No id-based tie-breaker is added, since
	 * schema/behavior changes for an unobserved, sub-microsecond race are not justified.
	 */
	boolean existsByProviderAndRepositoryIdAndProject_IdNotAndCreatedAtLessThan(
			GitProvider provider, Long repositoryId, UUID projectId, LocalDateTime createdAt);

	@Query(
			"""
			select r from GitRepo r
			join fetch r.project
			where r.project.id = :projectId
			  and r.connectionStatus = :status
			""")
	List<GitRepo> findFetchedByProject_IdAndConnectionStatus(
			@Param("projectId") UUID projectId, @Param("status") IntegrationStatus status);
}

package com.saga.be.repository;

import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitRepo;
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

	Optional<GitRepo> findByProviderAndRepositoryId(GitProvider provider, Long repositoryId);

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

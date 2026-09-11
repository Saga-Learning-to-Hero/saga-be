package com.saga.be.repository;

import com.saga.be.entity.github.GithubProjectInstallation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GithubProjectInstallationRepository extends JpaRepository<GithubProjectInstallation, UUID> {

	@Query(
			"""
			select m from GithubProjectInstallation m
			join fetch m.installation
			where m.project.id = :projectId
			order by m.updatedAt desc
			""")
	List<GithubProjectInstallation> findByProject_IdWithInstallation(@Param("projectId") UUID projectId);

	@Query(
			"""
			select m from GithubProjectInstallation m
			join fetch m.installation
			where m.project.id = :projectId
			order by m.updatedAt desc
			""")
	List<GithubProjectInstallation> findFetchedByProjectId(@Param("projectId") UUID projectId);

	Optional<GithubProjectInstallation> findByProject_IdAndInstallation_Id(UUID projectId, UUID installationId);

	boolean existsByProject_IdAndInstallation_Id(UUID projectId, UUID installationId);

	@Query(
			"""
			select m from GithubProjectInstallation m
			join fetch m.project
			where m.installation.id = :installationId
			""")
	List<GithubProjectInstallation> findByInstallation_IdWithProject(@Param("installationId") UUID installationId);

	@Query(
			"""
			select m from GithubProjectInstallation m
			join fetch m.project
			join fetch m.installation
			where m.installation.installationId = :providerInstallationId
			""")
	List<GithubProjectInstallation> findByProviderInstallationId(
			@Param("providerInstallationId") Long providerInstallationId);

	long countByInstallation_Id(UUID installationId);

	void deleteByProject_Id(UUID projectId);

	void deleteByProject_IdAndInstallation_Id(UUID projectId, UUID installationId);
}

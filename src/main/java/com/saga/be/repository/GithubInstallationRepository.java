package com.saga.be.repository;

import com.saga.be.entity.github.GithubInstallation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GithubInstallationRepository extends JpaRepository<GithubInstallation, UUID> {

	Optional<GithubInstallation> findByInstallationId(Long installationId);

	Optional<GithubInstallation> findByProject_Id(UUID projectId);
}

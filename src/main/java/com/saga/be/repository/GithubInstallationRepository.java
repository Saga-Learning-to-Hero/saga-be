package com.saga.be.repository;

import com.saga.be.entity.github.GithubInstallation;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface GithubInstallationRepository extends JpaRepository<GithubInstallation, UUID> {

	Optional<GithubInstallation> findByInstallationId(Long installationId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select g from GithubInstallation g where g.installationId = :installationId")
	Optional<GithubInstallation> findByInstallationIdForUpdate(@Param("installationId") Long installationId);
}

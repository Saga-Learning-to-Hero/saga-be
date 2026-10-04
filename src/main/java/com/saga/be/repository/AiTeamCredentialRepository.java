package com.saga.be.repository;

import com.saga.be.entity.ai.AiTeamCredential;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTeamCredentialRepository extends JpaRepository<AiTeamCredential, UUID> {
	Optional<AiTeamCredential> findByProject_Id(UUID projectId);
}

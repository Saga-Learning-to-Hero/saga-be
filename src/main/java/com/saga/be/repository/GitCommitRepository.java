package com.saga.be.repository;

import com.saga.be.entity.github.GitCommit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitCommitRepository extends JpaRepository<GitCommit, UUID> {

	Optional<GitCommit> findByRepo_IdAndShaHash(UUID repoId, String shaHash);
}

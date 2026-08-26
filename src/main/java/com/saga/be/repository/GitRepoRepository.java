package com.saga.be.repository;

import com.saga.be.entity.github.GitRepo;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GitRepoRepository extends JpaRepository<GitRepo, UUID> {

	List<GitRepo> findByProject_Id(UUID projectId);

	Optional<GitRepo> findByProviderAndRepositoryId(
			com.saga.be.entity.enums.GitProvider provider, Long repositoryId);
}

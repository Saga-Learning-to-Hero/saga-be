package com.saga.be.repository;

import com.saga.be.entity.jira.Task;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskRepository extends JpaRepository<Task, UUID> {

	Optional<Task> findByProject_IdAndExternalKeyIgnoreCase(UUID projectId, String externalKey);

	Optional<Task> findByProject_IdAndExternalId(UUID projectId, String externalId);
}

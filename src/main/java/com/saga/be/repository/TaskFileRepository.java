package com.saga.be.repository;

import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.TaskFile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskFileRepository extends JpaRepository<TaskFile, UUID> {

	List<TaskFile> findByTask_IdOrderByCreatedAtAsc(UUID taskId);

	List<TaskFile> findByTask_IdIn(Collection<UUID> taskIds);

	Optional<TaskFile> findByTask_IdAndContentHash(UUID taskId, String contentHash);

	Optional<TaskFile> findByTask_IdAndExternalId(UUID taskId, String externalId);

	List<TaskFile> findByTask_IdAndSource(UUID taskId, EvidenceSource source);

	long countByTask_Id(UUID taskId);

	long countByTask_IdAndSource(UUID taskId, EvidenceSource source);
}

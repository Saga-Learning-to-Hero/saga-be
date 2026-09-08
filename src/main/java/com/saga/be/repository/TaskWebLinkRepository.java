package com.saga.be.repository;

import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.TaskWebLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskWebLinkRepository extends JpaRepository<TaskWebLink, UUID> {

	List<TaskWebLink> findByTask_IdOrderByCreatedAtAsc(UUID taskId);

	List<TaskWebLink> findByTask_IdIn(Collection<UUID> taskIds);

	Optional<TaskWebLink> findByTask_IdAndUrlHash(UUID taskId, String urlHash);

	Optional<TaskWebLink> findByTask_IdAndExternalId(UUID taskId, String externalId);

	List<TaskWebLink> findByTask_IdAndSource(UUID taskId, EvidenceSource source);
}

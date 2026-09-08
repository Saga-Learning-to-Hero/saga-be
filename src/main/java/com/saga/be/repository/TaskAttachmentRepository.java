package com.saga.be.repository;

import com.saga.be.entity.jira.TaskAttachment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {

	List<TaskAttachment> findByTask_IdIn(Collection<UUID> taskIds);

	List<TaskAttachment> findByTask_Id(UUID taskId);

	Optional<TaskAttachment> findByTask_IdAndExternalId(UUID taskId, String externalId);
}

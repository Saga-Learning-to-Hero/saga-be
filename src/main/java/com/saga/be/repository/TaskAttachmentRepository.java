package com.saga.be.repository;

import com.saga.be.entity.jira.TaskAttachment;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskAttachmentRepository extends JpaRepository<TaskAttachment, UUID> {

	List<TaskAttachment> findByTask_IdIn(Collection<UUID> taskIds);

	List<TaskAttachment> findByTask_Id(UUID taskId);

	Optional<TaskAttachment> findByTask_IdAndExternalId(UUID taskId, String externalId);

	/** Heatmap documents from Jira attachments: {@code Object[]{UUID studentId, LocalDateTime createdAt}}. */
	@Query(
			"""
			select t.assigneeStudent.id, a.createdAt
			from TaskAttachment a
			join a.task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.assigneeStudent is not null
			""")
	List<Object[]> findAssigneeAndCreatedAtByProject(@Param("projectId") UUID projectId);
}

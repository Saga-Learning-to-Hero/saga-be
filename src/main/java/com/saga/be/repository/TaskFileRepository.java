package com.saga.be.repository;

import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.TaskFile;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskFileRepository extends JpaRepository<TaskFile, UUID> {

	List<TaskFile> findByTask_IdOrderByCreatedAtAsc(UUID taskId);

	List<TaskFile> findByTask_IdIn(Collection<UUID> taskIds);

	Optional<TaskFile> findByTask_IdAndContentHash(UUID taskId, String contentHash);

	Optional<TaskFile> findByTask_IdAndExternalId(UUID taskId, String externalId);

	List<TaskFile> findByTask_IdAndSource(UUID taskId, EvidenceSource source);

	long countByTask_Id(UUID taskId);

	long countByTask_IdAndSource(UUID taskId, EvidenceSource source);

	long countByTask_Project_Id(UUID projectId);

	long countByTask_Project_IdAndCreatedBy_Id(UUID projectId, UUID userId);

	/**
	 * Heatmap documents: {@code Object[]{UUID studentId, LocalDateTime createdAt}}. Prefers the
	 * uploading student; falls back to the task assignee.
	 */
	@Query(
			"""
			select coalesce(sp.id, t.assigneeStudent.id), f.createdAt
			from TaskFile f
			join f.task t
			left join f.createdBy u
			left join com.saga.be.entity.account.StudentProfile sp on sp.userAccount = u
			where t.project.id = :projectId
			  and t.deletedAt is null
			""")
	List<Object[]> findAuthorAndCreatedAtByProject(@Param("projectId") UUID projectId);
}

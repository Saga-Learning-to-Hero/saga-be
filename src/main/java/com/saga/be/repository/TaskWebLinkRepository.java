package com.saga.be.repository;

import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.jira.TaskWebLink;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskWebLinkRepository extends JpaRepository<TaskWebLink, UUID> {

	List<TaskWebLink> findByTask_IdOrderByCreatedAtAsc(UUID taskId);

	org.springframework.data.domain.Page<TaskWebLink> findByTask_Id(
			UUID taskId, org.springframework.data.domain.Pageable pageable);

	long countByTask_Id(UUID taskId);

	List<TaskWebLink> findByTask_IdIn(Collection<UUID> taskIds);

	Optional<TaskWebLink> findByTask_IdAndUrlHash(UUID taskId, String urlHash);

	Optional<TaskWebLink> findByTask_IdAndExternalId(UUID taskId, String externalId);

	List<TaskWebLink> findByTask_IdAndSource(UUID taskId, EvidenceSource source);

	long countByTask_Project_Id(UUID projectId);

	long countByTask_Project_IdAndCreatedBy_Id(UUID projectId, UUID userId);

	/** Heatmap documents: {@code Object[]{UUID studentId, LocalDateTime createdAt}}. */
	@Query(
			"""
			select coalesce(sp.id, t.assigneeStudent.id), w.createdAt
			from TaskWebLink w
			join w.task t
			left join w.createdBy u
			left join com.saga.be.entity.account.StudentProfile sp on sp.userAccount = u
			where t.project.id = :projectId
			  and t.deletedAt is null
			""")
	List<Object[]> findAuthorAndCreatedAtByProject(@Param("projectId") UUID projectId);
}

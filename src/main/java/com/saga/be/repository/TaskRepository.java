package com.saga.be.repository;

import com.saga.be.entity.jira.Task;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

	Optional<Task> findByProject_IdAndExternalKeyIgnoreCase(UUID projectId, String externalKey);

	Optional<Task> findByProject_IdAndExternalId(UUID projectId, String externalId);

	List<Task> findByProject_IdAndExternalIdIn(UUID projectId, Collection<String> externalIds);

	List<Task> findByProject_IdAndExternalKeyIgnoreCaseIn(UUID projectId, Collection<String> externalKeys);

	@Query(
			"""
			select t from Task t
			left join fetch t.assigneeStudent
			where t.project.id = :projectId
			  and t.deletedAt is null
			order by coalesce(t.externalUpdatedAt, t.updatedAt) desc
			""")
	List<Task> findActiveFetchedByProject_Id(@Param("projectId") UUID projectId);

	long countByProject_IdAndDeletedAtIsNull(UUID projectId);
}

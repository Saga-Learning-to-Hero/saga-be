package com.saga.be.repository;

import com.saga.be.entity.jira.Task;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

	Optional<Task> findByProject_IdAndExternalKeyIgnoreCase(UUID projectId, String externalKey);

	Optional<Task> findByProject_IdAndExternalId(UUID projectId, String externalId);

	@Query(
			"""
			SELECT t FROM Task t
			LEFT JOIN FETCH t.sprint
			LEFT JOIN FETCH t.assigneeStudent
			WHERE t.project.id = :projectId AND t.deletedAt IS NULL
			""")
	List<Task> findActiveFetchedByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			SELECT t FROM Task t
			JOIN FETCH t.project p
			JOIN FETCH p.course c
			LEFT JOIN FETCH c.instructor ins
			LEFT JOIN FETCH ins.userAccount
			WHERE t.id = :id AND t.deletedAt IS NULL
			""")
	Optional<Task> findActiveFetchedById(@Param("id") UUID id);
}

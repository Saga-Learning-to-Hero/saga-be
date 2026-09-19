package com.saga.be.repository;

import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.WorkSessionStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskWorkSessionRepository extends JpaRepository<TaskWorkSession, UUID> {

	List<TaskWorkSession> findByTask_IdAndUser_IdAndStatusOrderByStartedAtAsc(
			UUID taskId, UUID userId, WorkSessionStatus status);

	List<TaskWorkSession> findByTask_IdAndUser_IdOrderByStartedAtAsc(UUID taskId, UUID userId);

	boolean existsByProject_Id(UUID projectId);

	boolean existsByTask_Project_Id(UUID projectId);

	boolean existsByTask_Id(UUID taskId);

	long countByProject_Id(UUID projectId);

	long countByProject_IdAndUser_Id(UUID projectId, UUID userId);

	long countByTask_Id(UUID taskId);

	/**
	 * Scalar time bounds for full-task elapsed totals — not full entities. Callers sum duration in
	 * Java with a single captured {@code now} so OPEN rows and STOPPED+null-endedAt match
	 * {@code TaskEvidenceService} semantics without loading user/project graphs.
	 */
	@Query(
			"""
			select s.startedAt, s.endedAt
			from TaskWorkSession s
			where s.task.id = :taskId
			""")
	List<Object[]> findStartedAndEndedByTaskId(@Param("taskId") UUID taskId);

	@Query(
			"""
			select s from TaskWorkSession s
			join fetch s.user
			where s.task.id = :taskId
			  and s.status = com.saga.be.entity.enums.WorkSessionStatus.OPEN
			order by s.startedAt asc, s.id asc
			""")
	List<TaskWorkSession> findOpenFetchedByTaskId(@Param("taskId") UUID taskId);

	/**
	 * Paged history ids — newest first. Sort is in JPQL; callers must pass an unsorted {@link
	 * Pageable}.
	 */
	@Query(
			value =
					"""
					select s.id
					from TaskWorkSession s
					where s.task.id = :taskId
					order by s.startedAt desc, s.id desc
					""",
			countQuery =
					"""
					select count(s.id)
					from TaskWorkSession s
					where s.task.id = :taskId
					""")
	Page<UUID> findPageIdsByTaskId(@Param("taskId") UUID taskId, Pageable pageable);

	@Query(
			"""
			select s from TaskWorkSession s
			join fetch s.user
			where s.id in :ids
			""")
	List<TaskWorkSession> findFetchedByIdIn(@Param("ids") Collection<UUID> ids);
}

package com.saga.be.repository;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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
			left join fetch t.sprint
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			where t.project.id = :projectId
			  and t.deletedAt is null
			order by coalesce(t.externalUpdatedAt, t.updatedAt) desc
			""")
	List<Task> findActiveFetchedByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			select t from Task t
			join fetch t.project p
			join fetch p.course c
			left join fetch c.instructor ins
			left join fetch ins.userAccount
			left join fetch t.sprint
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			where t.id = :id and t.project.id = :projectId and t.deletedAt is null
			""")
	Optional<Task> findActiveFetchedByIdAndProject_Id(@Param("id") UUID id, @Param("projectId") UUID projectId);

	@Query(
			"""
			select t from Task t
			join fetch t.project p
			join fetch p.course c
			left join fetch c.instructor ins
			left join fetch ins.userAccount
			where t.id = :id and t.deletedAt is null
			""")
	Optional<Task> findActiveFetchedById(@Param("id") UUID id);

	Optional<Task> findByIdAndProject_IdAndDeletedAtIsNull(UUID id, UUID projectId);

	long countByProject_IdAndDeletedAtIsNull(UUID projectId);

	@Query(
			"""
			select t from Task t
			left join fetch t.sprint
			where t.project.id = :projectId
			  and t.assigneeStudent.id = :studentId
			  and t.deletedAt is null
			order by coalesce(t.externalUpdatedAt, t.updatedAt) desc
			""")
	List<Task> findActiveFetchedByProject_IdAndAssigneeStudent_Id(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/** Progress dashboard: one row per (assignee, status) for a project — {@code Object[]{UUID studentId, TaskStatus status, Long count}}. */
	@Query(
			"""
			select t.assigneeStudent.id, t.status, count(t)
			from Task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			group by t.assigneeStudent.id, t.status
			""")
	List<Object[]> countGroupedByAssigneeAndStatus(@Param("projectId") UUID projectId);

	/** Progress dashboard (Lecturer course overview): one row per (project, status) — {@code Object[]{UUID projectId, TaskStatus status, Long count}}. */
	@Query(
			"""
			select t.project.id, t.status, count(t)
			from Task t
			where t.project.id in :projectIds
			  and t.deletedAt is null
			group by t.project.id, t.status
			""")
	List<Object[]> countGroupedByStatusForProjects(@Param("projectIds") Collection<UUID> projectIds);

	long countByProject_IdAndSprint_IdAndDeletedAtIsNull(UUID projectId, UUID sprintId);

	long countByProject_IdAndSprint_IdAndStatusAndDeletedAtIsNull(UUID projectId, UUID sprintId, TaskStatus status);

	@Query(
			"select max(coalesce(t.externalUpdatedAt, t.updatedAt)) from Task t where t.project.id = :projectId and t.deletedAt is null")
	LocalDateTime findMaxUpdatedAtByProject_Id(@Param("projectId") UUID projectId);

	/** Progress dashboard (Lecturer course overview): one row per project — {@code Object[]{UUID projectId, LocalDateTime maxUpdatedAt}}. */
	@Query(
			"""
			select t.project.id, max(coalesce(t.externalUpdatedAt, t.updatedAt))
			from Task t
			where t.project.id in :projectIds
			  and t.deletedAt is null
			group by t.project.id
			""")
	List<Object[]> findMaxUpdatedAtGroupedByProjects(@Param("projectIds") Collection<UUID> projectIds);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Task t set t.blocksTask = null where t.project.id = :projectId")
	int clearBlocksTaskReferencesByProjectId(@Param("projectId") UUID projectId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Task t where t.project.id = :projectId")
	int deleteByProject_Id(@Param("projectId") UUID projectId);
}

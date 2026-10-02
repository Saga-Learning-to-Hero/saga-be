package com.saga.be.repository;

import com.saga.be.entity.delay.TaskDelayCase;
import com.saga.be.entity.enums.DelayCaseStatus;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskDelayCaseRepository extends JpaRepository<TaskDelayCase, UUID> {

	boolean existsByTask_IdAndDueDate(UUID taskId, LocalDateTime dueDate);

	boolean existsByProject_Id(UUID projectId);

	@Query(
			"""
			select c from TaskDelayCase c
			join fetch c.task t
			join fetch c.studentProfile sp
			join fetch sp.userAccount
			left join fetch c.blockingTask
			join fetch c.project p
			join fetch p.course
			where p.id = :projectId
			order by c.openedAt desc, c.id desc
			""")
	List<TaskDelayCase> findFetchedByProject(@Param("projectId") UUID projectId);

	@Query(
			"""
			select c from TaskDelayCase c
			join fetch c.task t
			join fetch c.studentProfile sp
			join fetch sp.userAccount
			left join fetch c.blockingTask
			join fetch c.project p
			join fetch p.course
			where c.id = :id and p.id = :projectId
			""")
	Optional<TaskDelayCase> findFetchedByIdAndProject(@Param("id") UUID id, @Param("projectId") UUID projectId);

	/** Cases waiting for a lecturer, in the courses this lecturer teaches. */
	@Query(
			"""
			select c from TaskDelayCase c
			join fetch c.task t
			join fetch c.studentProfile sp
			join fetch sp.userAccount
			left join fetch c.blockingTask
			join fetch c.project p
			join fetch p.course course
			join course.instructor instructor
			where instructor.userAccount.id = :lecturerUserId
			  and c.status in :statuses
			order by c.openedAt asc, c.id asc
			""")
	List<TaskDelayCase> findFetchedForLecturer(
			@Param("lecturerUserId") UUID lecturerUserId, @Param("statuses") Collection<DelayCaseStatus> statuses);

	List<TaskDelayCase> findByStatusAndExplanationDueAtBefore(DelayCaseStatus status, LocalDateTime cutoff);

	/** (task id, due date) of cases closed as objective: excused in the on-time rate. */
	@Query(
			"""
			select c.task.id, c.dueDate from TaskDelayCase c
			where c.project.id = :projectId
			  and c.status = com.saga.be.entity.enums.DelayCaseStatus.CLOSED_OBJECTIVE
			""")
	List<Object[]> findObjectiveClosedTaskDues(@Param("projectId") UUID projectId);
}

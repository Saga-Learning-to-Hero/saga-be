package com.saga.be.repository;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TaskRepository extends JpaRepository<Task, UUID> {

	Optional<Task> findByProject_IdAndExternalKeyIgnoreCase(UUID projectId, String externalKey);

	/**
	 * Legacy project-scoped lookup. Prefer {@link #findByJiraIntegration_IdAndExternalId} for
	 * provider upsert identity — the same {@code externalId} may exist under two sources.
	 */
	Optional<Task> findByProject_IdAndExternalId(UUID projectId, String externalId);

	List<Task> findByProject_IdAndExternalIdIn(UUID projectId, Collection<String> externalIds);

	Optional<Task> findByJiraIntegration_IdAndExternalId(UUID jiraIntegrationId, String externalId);

	List<Task> findByJiraIntegration_IdAndExternalIdIn(UUID jiraIntegrationId, Collection<String> externalIds);

	List<Task> findByProject_IdAndExternalKeyIgnoreCaseIn(UUID projectId, Collection<String> externalKeys);

	Optional<Task> findByIdAndProject_Id(UUID id, UUID projectId);

	@Query(
			"""
			select t from Task t
			join fetch t.jiraIntegration
			left join fetch t.sprint
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			left join fetch t.parentTask
			where t.project.id = :projectId
			  and t.deletedAt is null
			order by coalesce(t.externalUpdatedAt, t.updatedAt) desc
			""")
	List<Task> findActiveFetchedByProject_Id(@Param("projectId") UUID projectId);

	/**
	 * Current operational Task candidates. Historical project lists deliberately use
	 * {@link #findActiveFetchedByProject_Id}; a source is excluded here only after its outbound
	 * failover has actually succeeded and bound a target Task.
	 */
	@Query(
			"""
			select t from Task t
			join fetch t.jiraIntegration
			left join fetch t.sprint
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			left join fetch t.parentTask
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			order by coalesce(t.externalUpdatedAt, t.updatedAt) desc
			""")
	List<Task> findCurrentFetchedByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			select t from Task t
			join fetch t.jiraIntegration
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			left join fetch t.parentTask
			left join fetch t.sprint
			where t.project.id = :projectId
			  and t.jiraIntegration.id = :jiraIntegrationId
			  and t.deletedAt is null
			""")
	List<Task> findActiveFetchedByProjectAndJiraIntegration(
			@Param("projectId") UUID projectId, @Param("jiraIntegrationId") UUID jiraIntegrationId);

	@Query(
			"""
			select t from Task t
			join fetch t.project p
			join fetch p.course c
			left join fetch c.instructor ins
			left join fetch ins.userAccount
			left join fetch t.jiraIntegration
			left join fetch t.sprint
			left join fetch t.assigneeStudent ass
			left join fetch ass.userAccount
			left join fetch t.parentTask
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

	/**
	 * Serializes work-session starts for one Task so two concurrent START requests cannot both
	 * insert an OPEN row for the same student. Empty SELECT-FOR-UPDATE on sessions would not lock
	 * the "no row yet" case; locking the Task row does.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select t from Task t where t.id = :id and t.deletedAt is null")
	Optional<Task> lockActiveById(@Param("id") UUID id);

	long countByProject_IdAndDeletedAtIsNull(UUID projectId);

	@Query(
			"""
			select t from Task t
			left join fetch t.sprint
			where t.project.id = :projectId
			  and t.assigneeStudent.id = :studentId
			  and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
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
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
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
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			group by t.project.id, t.status
			""")
	List<Object[]> countGroupedByStatusForProjects(@Param("projectIds") Collection<UUID> projectIds);

	@Query("""
			select count(t) from Task t
			where t.project.id = :projectId and t.sprint.id = :sprintId and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			""")
	long countCurrentByProjectAndSprint(@Param("projectId") UUID projectId, @Param("sprintId") UUID sprintId);

	@Query("""
			select count(t) from Task t
			where t.project.id = :projectId and t.sprint.id = :sprintId and t.status = :status and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			""")
	long countCurrentByProjectAndSprintAndStatus(
			@Param("projectId") UUID projectId, @Param("sprintId") UUID sprintId, @Param("status") TaskStatus status);

	/**
	 * Student dashboard sprint progress: one row per status for non-deleted tasks in one sprint —
	 * {@code Object[]{TaskStatus status, Long count}}. Includes subtasks (same as ProjectProgress).
	 */
	@Query(
			"""
			select t.status, count(t)
			from Task t
			where t.project.id = :projectId
			  and t.sprint.id = :sprintId
			  and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			group by t.status
			""")
	List<Object[]> countGroupedByStatusForProjectAndSprint(
			@Param("projectId") UUID projectId, @Param("sprintId") UUID sprintId);

	/**
	 * Sprint activity: one row per (sprint, status) for non-deleted tasks in a sprint —
	 * {@code Object[]{UUID sprintId, TaskStatus status, Long count}}. Backlog (null sprint) is
	 * excluded.
	 */
	@Query(
			"""
			select t.sprint.id, t.status, count(t)
			from Task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.sprint is not null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			group by t.sprint.id, t.status
			""")
	List<Object[]> countGroupedBySprintAndStatus(@Param("projectId") UUID projectId);

	/**
	 * Personal sprint activity: same shape as {@link #countGroupedBySprintAndStatus}, filtered to
	 * tasks assigned to {@code studentId}.
	 */
	@Query(
			"""
			select t.sprint.id, t.status, count(t)
			from Task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.sprint is not null
			  and t.assigneeStudent.id = :studentId
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			group by t.sprint.id, t.status
			""")
	List<Object[]> countGroupedBySprintAndStatusForAssignee(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/** Heatmap: {@code Object[]{UUID studentId, LocalDateTime createdAt}} for assigned, non-deleted tasks. */
	@Query(
			"""
			select t.assigneeStudent.id, t.createdAt
			from Task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.assigneeStudent is not null
			""")
	List<Object[]> findAssigneeAndCreatedAtByProject(@Param("projectId") UUID projectId);

	/**
	 * Burndown: {@code Object[]{TaskStatus status, LocalDateTime completedAt, LocalDateTime resolvedAt,
	 * LocalDateTime createdAt}} for non-deleted tasks in one sprint.
	 */
	@Query(
			"""
			select t.status, t.completedAt, t.resolvedAt, t.createdAt
			from Task t
			where t.project.id = :projectId
			  and t.sprint.id = :sprintId
			  and t.deletedAt is null
			""")
	List<Object[]> findBurndownRowsByProjectAndSprint(
			@Param("projectId") UUID projectId, @Param("sprintId") UUID sprintId);

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

	@Query("select t.parentTask.id from Task t where t.id = :id")
	Optional<UUID> findParentTaskIdById(@Param("id") UUID id);

	@Query(
			"""
			select t.id as id, t.project.id as projectId, t.deletedAt as deletedAt
			from Task t
			where t.id = :id
			""")
	Optional<TaskParentIdentity> findParentIdentity(@Param("id") UUID id);

	@Query("select t.id from Task t where t.parentTask.id in :parentIds and t.deletedAt is null")
	List<UUID> findActiveChildIdsByParentIds(@Param("parentIds") Collection<UUID> parentIds);

	boolean existsByParentTask_IdAndDeletedAtIsNull(UUID parentTaskId);

	@Query(
			"""
			select t.id, t.title, t.status
			from Task t
			where t.parentTask.id = :parentId
			  and t.deletedAt is null
			order by t.title
			""")
	List<Object[]> findActiveDirectChildSummaries(@Param("parentId") UUID parentId);

	@Query(
			"""
			select t.id, t.title, t.status, t.parentTask.id, t.externalKey
			from Task t
			where t.project.id = :projectId
			  and t.deletedAt is null
			  and t.id not in :excludedIds
			  and (
			    :qBlank = true
			    or lower(t.title) like concat(:qPrefix, '%')
			    or lower(coalesce(t.externalKey, '')) like concat(:qPrefix, '%')
			  )
			order by t.title asc, t.id asc
			""")
	Page<Object[]> findParentOptions(
			@Param("projectId") UUID projectId,
			@Param("excludedIds") Collection<UUID> excludedIds,
			@Param("qBlank") boolean qBlank,
			@Param("qPrefix") String qPrefix,
			Pageable pageable);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Task t set t.parentTask = null where t.project.id = :projectId")
	int clearParentTaskReferencesByProjectId(@Param("projectId") UUID projectId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("update Task t set t.blocksTask = null where t.project.id = :projectId")
	int clearBlocksTaskReferencesByProjectId(@Param("projectId") UUID projectId);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("delete from Task t where t.project.id = :projectId")
	int deleteByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			select count(t.id)
			from Task t
			join t.project p
			join p.course c
			where c.semester.id = :semesterId
			  and c.deletedAt is null
			  and t.deletedAt is null
			""")
	long countActiveByCourseSemester(@Param("semesterId") UUID semesterId);

	/**
	 * Phase B weekly completions: current {@code DONE} tasks only. Effective timestamp is
	 * {@code coalesce(completedAt, resolvedAt, createdAt)}. This is <strong>not</strong> an
	 * immutable status-transition history — a task DONE in week 2 then reopened is omitted.
	 */
	@Query(
			"""
			select coalesce(t.completedAt, t.resolvedAt, t.createdAt)
			from Task t
			join t.project p
			join p.course c
			where c.semester.id = :semesterId
			  and c.deletedAt is null
			  and t.deletedAt is null
			  and t.status = com.saga.be.entity.enums.TaskStatus.DONE
			  and coalesce(t.completedAt, t.resolvedAt, t.createdAt) >= :startInclusive
			  and coalesce(t.completedAt, t.resolvedAt, t.createdAt) < :endExclusive
			""")
	List<LocalDateTime> findDoneCompletionTimestampsByCourseSemester(
			@Param("semesterId") UUID semesterId,
			@Param("startInclusive") LocalDateTime startInclusive,
			@Param("endExclusive") LocalDateTime endExclusive);

	/**
	 * Student dashboard personal task metrics: one row per status —
	 * {@code Object[]{TaskStatus status, Long count, Long storyPointSum}}.
	 * {@code storyPointSum} uses {@code coalesce(storyPoint, 0)}. Project-wide, not sprint-scoped.
	 */
	@Query(
			"""
			select t.status, count(t), coalesce(sum(coalesce(t.storyPoint, 0)), 0)
			from Task t
			where t.project.id = :projectId
			  and t.assigneeStudent.id = :studentId
			  and t.deletedAt is null
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			group by t.status
			""")
	List<Object[]> countStatusAndStoryPointsForAssignee(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/**
	 * Needs-attention preview: personal non-DONE tasks. Sort is in JPQL; pass an unsorted pageable.
	 * Order is exactly the final secondary keys: dueDate ASC null-last, business priority
	 * HIGHEST→LOWEST→null, id ASC. Top 10 is enough because anomalies always rank first.
	 */
	@Query(
			"""
			select t from Task t
			where t.project.id = :projectId
			  and t.assigneeStudent.id = :studentId
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			order by
			  case when t.dueDate is null then 1 else 0 end,
			  t.dueDate asc,
			  case t.priority
			    when com.saga.be.entity.enums.Priority.HIGHEST then 5
			    when com.saga.be.entity.enums.Priority.HIGH then 4
			    when com.saga.be.entity.enums.Priority.MEDIUM then 3
			    when com.saga.be.entity.enums.Priority.LOW then 2
			    when com.saga.be.entity.enums.Priority.LOWEST then 1
			    else 0
			  end desc,
			  t.id asc
			""")
	List<Task> findAttentionNonDoneByProjectAndAssignee(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId, Pageable pageable);

	/**
	 * All personal DONE tasks with zero V23 coding-evidence links. Uncapped lightweight projection
	 * so Java can apply {@code ReservedContributionMarkerClassifier} without a hidden row cutoff.
	 */
	@Query(
			"""
			select new com.saga.be.repository.StudentDashboardAnomalyCandidateRow(
			    t.id, t.externalKey, t.title, t.status, t.priority, t.storyPoint, t.dueDate, t.labelsJson)
			from Task t
			where t.project.id = :projectId
			  and t.assigneeStudent.id = :studentId
			  and t.deletedAt is null
			  and t.status = com.saga.be.entity.enums.TaskStatus.DONE
			  and not exists (select 1 from JiraTaskFailoverItem fi where fi.sourceTask = t and fi.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED and fi.targetTask is not null)
			  and not exists (
			    select 1
			    from TaskGitCommitLink l
			    join l.gitCommit c
			    where l.task = t
			      and (c.parentCount is null or c.parentCount <= 1)
			  )
			""")
	List<StudentDashboardAnomalyCandidateRow> findDoneWithoutV23EvidenceCandidates(
			@Param("projectId") UUID projectId, @Param("studentId") UUID studentId);

	/** Lecturer dashboard: {@code Object[]{UUID sprintId, TaskStatus status, Long count}}. */
	@Query(
			"""
			select t.sprint.id, t.status, count(t)
			from Task t
			where t.sprint.id in :sprintIds
			  and t.deletedAt is null
			group by t.sprint.id, t.status
			""")
	List<Object[]> countGroupedBySprintIdsAndStatus(@Param("sprintIds") Collection<UUID> sprintIds);

	/** Lecturer dashboard: overdue non-DONE tasks — {@code Object[]{UUID sprintId, Long count}}. */
	@Query(
			"""
			select t.sprint.id, count(t)
			from Task t
			where t.sprint.id in :sprintIds
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate < :now
			group by t.sprint.id
			""")
	List<Object[]> countOverdueBySprintIds(
			@Param("sprintIds") Collection<UUID> sprintIds, @Param("now") LocalDateTime now);

	/**
	 * Deterministic deadline scan candidates: non-DONE tasks with a due date at or before the
	 * due-soon cutoff (this covers both already-overdue and due-soon-within-window tasks in one
	 * bounded, paged query; {@link com.saga.be.service.task.TaskDeadlinePolicy} classifies each row).
	 */
	@Query(
			"""
			select t
			from Task t
			where t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate <= :dueSoonCutoff
			order by t.dueDate asc, t.id asc
			""")
	Page<Task> findDeadlineScanCandidates(@Param("dueSoonCutoff") LocalDateTime dueSoonCutoff, Pageable pageable);

	/** Progress facts: exact overdue count for one project (optionally one assignee). */
	@Query(
			"""
			select count(t) from Task t
			where t.project.id = :projectId
			  and (:studentId is null or t.assigneeStudent.id = :studentId)
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate < :now
			""")
	long countOverdueForProject(@Param("projectId") UUID projectId, @Param("studentId") UUID studentId, @Param("now") LocalDateTime now);

	/** Progress facts: exact due-soon (not yet overdue) count for one project (optionally one assignee). */
	@Query(
			"""
			select count(t) from Task t
			where t.project.id = :projectId
			  and (:studentId is null or t.assigneeStudent.id = :studentId)
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate >= :now
			  and t.dueDate <= :dueSoonCutoff
			""")
	long countDueSoonForProject(@Param("projectId") UUID projectId, @Param("studentId") UUID studentId, @Param("now") LocalDateTime now, @Param("dueSoonCutoff") LocalDateTime dueSoonCutoff);

	/** Course-wide progress facts: one bulk query across every project in the course (no N+1). */
	@Query(
			"""
			select count(t) from Task t
			where t.project.id in :projectIds
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate < :now
			""")
	long countOverdueForProjects(@Param("projectIds") Collection<UUID> projectIds, @Param("now") LocalDateTime now);

	@Query(
			"""
			select count(t) from Task t
			where t.project.id in :projectIds
			  and t.deletedAt is null
			  and t.status <> com.saga.be.entity.enums.TaskStatus.DONE
			  and t.dueDate is not null
			  and t.dueDate >= :now
			  and t.dueDate <= :dueSoonCutoff
			""")
	long countDueSoonForProjects(@Param("projectIds") Collection<UUID> projectIds, @Param("now") LocalDateTime now, @Param("dueSoonCutoff") LocalDateTime dueSoonCutoff);

	/** Lecturer dashboard: DONE tasks in sprints — {@code Object[]{UUID sprintId, UUID taskId}}. */
	@Query(
			"""
			select t.sprint.id, t.id
			from Task t
			where t.sprint.id in :sprintIds
			  and t.deletedAt is null
			  and t.status = com.saga.be.entity.enums.TaskStatus.DONE
			""")
	List<Object[]> findDoneTaskIdsBySprintIds(@Param("sprintIds") Collection<UUID> sprintIds);

	/** Lecturer dashboard activity: {@code Object[]{UUID projectId, LocalDateTime createdAt}}. */
	@Query(
			"""
			select t.project.id, t.createdAt
			from Task t
			where t.project.id in :projectIds
			  and t.deletedAt is null
			""")
	List<Object[]> findProjectIdAndCreatedAtByProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}

package com.saga.be.repository;

import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JiraTaskFailoverItemRepository extends JpaRepository<JiraTaskFailoverItem, UUID> {

	Optional<JiraTaskFailoverItem> findByIdAndRun_Project_Id(UUID id, UUID projectId);

	List<JiraTaskFailoverItem> findBySourceTask_IdAndStatus(UUID sourceTaskId, JiraFailoverItemStatus status);

	List<JiraTaskFailoverItem> findBySourceTask_IdAndStatusIn(
			UUID sourceTaskId, Collection<JiraFailoverItemStatus> statuses);

	List<JiraTaskFailoverItem> findBySourceTask_IdInAndStatus(
			Collection<UUID> sourceTaskIds, JiraFailoverItemStatus status);

	List<JiraTaskFailoverItem> findBySourceTask_IdInAndStatusIn(
			Collection<UUID> sourceTaskIds, Collection<JiraFailoverItemStatus> statuses);

	@Query(
			"""
			select i from JiraTaskFailoverItem i
			join fetch i.sourceTask
			left join fetch i.targetTask
			join fetch i.run
			where i.sourceTask.id in :sourceTaskIds
			  and i.status = :status
			""")
	List<JiraTaskFailoverItem> findFetchedBySourceTask_IdInAndStatus(
			@Param("sourceTaskIds") Collection<UUID> sourceTaskIds,
			@Param("status") JiraFailoverItemStatus status);

	@Query(
			"""
			select i from JiraTaskFailoverItem i
			join fetch i.sourceTask
			left join fetch i.targetTask
			join fetch i.run
			join fetch i.run.targetJiraIntegration
			where i.sourceTask.id in :sourceTaskIds
			  and i.status in :statuses
			""")
	List<JiraTaskFailoverItem> findFetchedBySourceTask_IdInAndStatusIn(
			@Param("sourceTaskIds") Collection<UUID> sourceTaskIds,
			@Param("statuses") Collection<JiraFailoverItemStatus> statuses);

	@Query(
			"""
			select i.status, count(i)
			from JiraTaskFailoverItem i
			where i.run.id = :runId
			group by i.status
			""")
	List<Object[]> countByRun_IdAndStatusGrouped(@Param("runId") UUID runId);

	@Query(
			"""
			select count(i) > 0 from JiraTaskFailoverItem i
			where i.sourceTask.id = :sourceTaskId
			  and i.status = com.saga.be.entity.enums.JiraFailoverItemStatus.SUCCEEDED
			  and i.targetTask is not null
			""")
	boolean existsSuccessfullySuperseded(@Param("sourceTaskId") UUID sourceTaskId);

	@Query(
			"""
			select count(i) > 0 from JiraTaskFailoverItem i
			where i.sourceTask.id = :sourceTaskId
			  and i.status in :statuses
			""")
	boolean existsBySourceTask_IdAndStatusIn(
			@Param("sourceTaskId") UUID sourceTaskId,
			@Param("statuses") Collection<JiraFailoverItemStatus> statuses);

	/**
	 * Successfully superseded = status {@code SUCCEEDED} and a non-null target task.
	 */
	default boolean isSuccessfullySuperseded(UUID sourceTaskId) {
		return existsSuccessfullySuperseded(sourceTaskId);
	}

	/** Any claim-holding outbound item for this source Task (DB unique enforces at most one). */
	default boolean existsOutboundClaim(UUID sourceTaskId) {
		return existsBySourceTask_IdAndStatusIn(sourceTaskId, JiraTaskFailoverItem.CLAIM_HOLDING_STATUSES);
	}

	default List<JiraTaskFailoverItem> findInFlightBySourceTaskId(UUID sourceTaskId) {
		return findBySourceTask_IdAndStatusIn(
				sourceTaskId, JiraFailoverSupersedeQueries.IN_FLIGHT_CLAIM_STATUSES);
	}

	default List<JiraTaskFailoverItem> findClaimHoldingBySourceTaskId(UUID sourceTaskId) {
		return findBySourceTask_IdAndStatusIn(sourceTaskId, JiraTaskFailoverItem.CLAIM_HOLDING_STATUSES);
	}
}

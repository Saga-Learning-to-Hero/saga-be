package com.saga.be.repository;

import com.saga.be.entity.jira.Sprint;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SprintRepository extends JpaRepository<Sprint, UUID> {

	Optional<Sprint> findByJiraIntegration_IdAndExternalSprintId(UUID jiraIntegrationId, String externalSprintId);

	long deleteByJiraIntegration_Id(UUID jiraIntegrationId);

	List<Sprint> findByJiraIntegration_IdAndExternalSprintIdIn(
			UUID jiraIntegrationId, Collection<String> externalSprintIds);

	@Query(
			"""
			select s from Sprint s
			where s.jiraIntegration.project.id = :projectId
			  and s.deletedAt is null
			order by coalesce(s.startDate, s.createdAt) desc
			""")
	List<Sprint> findActiveByProject_Id(@Param("projectId") UUID projectId);

	@Query(
			"""
			select s from Sprint s
			join fetch s.jiraIntegration ji
			join fetch ji.project
			where s.id = :sprintId
			  and ji.project.id = :projectId
			  and s.deletedAt is null
			""")
	Optional<Sprint> findActiveByIdAndProject_Id(@Param("sprintId") UUID sprintId, @Param("projectId") UUID projectId);

	Optional<Sprint> findByIdAndJiraIntegration_Project_IdAndDeletedAtIsNull(UUID id, UUID projectId);

	/** Progress dashboard (Lecturer course overview): all non-deleted sprints for several projects in one query. */
	@Query(
			"""
			select s from Sprint s
			join fetch s.jiraIntegration ji
			join fetch ji.project p
			where p.id in :projectIds
			  and s.deletedAt is null
			order by coalesce(s.startDate, s.createdAt) desc
			""")
	List<Sprint> findActiveByProjectIdIn(@Param("projectIds") Collection<UUID> projectIds);
}

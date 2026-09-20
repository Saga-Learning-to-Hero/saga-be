package com.saga.be.repository;

import com.saga.be.entity.jira.JiraTaskFailoverRun;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JiraTaskFailoverRunRepository extends JpaRepository<JiraTaskFailoverRun, UUID> {

	Optional<JiraTaskFailoverRun> findByIdAndProject_Id(UUID id, UUID projectId);

	@Query("""
			select r from JiraTaskFailoverRun r
			join fetch r.project
			join fetch r.sourceJiraIntegration
			join fetch r.targetJiraIntegration
			left join fetch r.targetSprint
			where r.id = :id and r.project.id = :projectId
			""")
	Optional<JiraTaskFailoverRun> findFetchedByIdAndProject_Id(@Param("id") UUID id, @Param("projectId") UUID projectId);
}

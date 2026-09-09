package com.saga.be.repository;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JiraIntegrationRepository extends JpaRepository<JiraIntegration, UUID> {

	Optional<JiraIntegration> findByProject_Id(UUID projectId);

	Optional<JiraIntegration> findByCloudIdAndJiraProjectId(String cloudId, String jiraProjectId);

	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			left join fetch j.connectedBy
			where j.jiraProjectId = :jiraProjectId and j.connectionStatus = :status
			""")
	List<JiraIntegration> findByJiraProjectIdAndConnectionStatus(
			@Param("jiraProjectId") String jiraProjectId, @Param("status") IntegrationStatus status);

	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			left join fetch j.connectedBy
			where j.project.id = :projectId
			""")
	Optional<JiraIntegration> findFetchedByProject_Id(@Param("projectId") UUID projectId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select j from JiraIntegration j left join fetch j.connectedBy where j.id = :id")
	Optional<JiraIntegration> lockById(@Param("id") UUID id);

	List<JiraIntegration> findByConnectionStatusAndWebhookExpiresAtBefore(
			IntegrationStatus status, LocalDateTime expiresAt);

	List<JiraIntegration> findByConnectionStatusAndJiraProjectIdAndProjectKeyIgnoreCase(
			IntegrationStatus status, String jiraProjectId, String projectKey);

	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			where j.connectionStatus = :status
			  and j.jiraProjectId = :jiraProjectId
			  and lower(j.projectKey) = lower(:projectKey)
			""")
	List<JiraIntegration> findFetchedActiveByJiraProject(
			@Param("status") IntegrationStatus status,
			@Param("jiraProjectId") String jiraProjectId,
			@Param("projectKey") String projectKey);
}

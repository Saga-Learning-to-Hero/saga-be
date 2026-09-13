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

	/**
	 * Since V14, {@code (cloud_id, jira_project_id)} is unique only among ACTIVE rows
	 * ({@code uk_jira_active_cloud_project}) -- multiple REVOKED rows may legitimately share a
	 * source. A lookup that ignores {@code connectionStatus} is no longer safely singular and
	 * would risk {@code NonUniqueResultException}; always scope this by {@code connectionStatus}.
	 */
	Optional<JiraIntegration> findByConnectionStatusAndCloudIdAndJiraProjectId(
			IntegrationStatus connectionStatus, String cloudId, String jiraProjectId);

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

	/**
	 * ACTIVE integrations that never completed webhook registration at all -- {@code webhook_id}
	 * or {@code webhook_expires_at} still {@code NULL} -- which {@link
	 * #findByConnectionStatusAndWebhookExpiresAtBefore} can never select: SQL {@code < threshold}
	 * is never true against a {@code NULL} column, so a webhook that failed registration before a
	 * value was ever stored (as opposed to one that registered successfully and is now approaching
	 * expiry) had no scheduled repair path before this method existed. Scoped to {@code status}
	 * (always ACTIVE in production use) so a REVOKED integration is never treated as a repair
	 * candidate.
	 */
	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			where j.connectionStatus = :status
			  and (j.webhookId is null or j.webhookExpiresAt is null)
			""")
	List<JiraIntegration> findByConnectionStatusAndWebhookMissing(@Param("status") IntegrationStatus status);

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

	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			where j.connectionStatus = :status
			  and j.jiraBoardId = :boardId
			""")
	List<JiraIntegration> findFetchedActiveByBoardId(
			@Param("status") IntegrationStatus status, @Param("boardId") String boardId);

	@Query(
			"""
			select j from JiraIntegration j
			join fetch j.project
			where j.connectionStatus = :status
			  and j.cloudId = :cloudId
			  and j.jiraBoardId = :boardId
			""")
	List<JiraIntegration> findFetchedActiveByCloudAndBoard(
			@Param("status") IntegrationStatus status,
			@Param("cloudId") String cloudId,
			@Param("boardId") String boardId);
}

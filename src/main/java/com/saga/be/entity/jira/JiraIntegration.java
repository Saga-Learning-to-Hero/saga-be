package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.BoardType;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.project.Project;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "jira_integration",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_jira_integration_project", columnNames = {"project_id"}),
		@UniqueConstraint(name = "uk_jira_cloud_project", columnNames = {"cloud_id", "jira_project_id"})
	}
)
public class JiraIntegration extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@Column(name = "name", length = 255)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(name = "board_type", length = 32)
	private BoardType boardType;

	@Column(name = "jira_board_id", length = 64)
	private String jiraBoardId;

	@Column(name = "cloud_id", length = 128)
	private String cloudId;

	@Column(name = "site_url", length = 500)
	private String siteUrl;

	@Column(name = "site_name", length = 255)
	private String siteName;

	@Column(name = "jira_project_id", length = 64)
	private String jiraProjectId;

	@Column(name = "project_key", length = 64)
	private String projectKey;

	@Column(name = "encrypted_access_token", columnDefinition = "TEXT")
	private String encryptedAccessToken;

	@Column(name = "encrypted_refresh_token", columnDefinition = "TEXT")
	private String encryptedRefreshToken;

	@Column(name = "token_expires_at")
	private LocalDateTime tokenExpiresAt;

	@Column(name = "granted_scopes", columnDefinition = "TEXT")
	private String grantedScopes;

	@Enumerated(EnumType.STRING)
	@Column(name = "connection_status", length = 32, nullable = false)
	private IntegrationStatus connectionStatus;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "connected_by_user_id", nullable = true)
	private UserAccount connectedBy;

	@Column(name = "webhook_id", length = 128)
	private String webhookId;

	@Column(name = "webhook_expires_at")
	private LocalDateTime webhookExpiresAt;

	@Column(name = "webhook_secret_hash", length = 64)
	private String webhookSecretHash;

	@Column(name = "encrypted_webhook_secret", columnDefinition = "TEXT")
	private String encryptedWebhookSecret;

	@Column(name = "sync_cursor")
	private LocalDateTime syncCursor;

	@Column(name = "consecutive_failures", nullable = false)
	private Integer consecutiveFailures;

	@Column(name = "last_error_code", length = 64)
	private String lastErrorCode;

	@Column(name = "last_synced_at")
	private LocalDateTime lastSyncedAt;

	@Column(name = "last_successful_sync_at")
	private LocalDateTime lastSuccessfulSyncAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

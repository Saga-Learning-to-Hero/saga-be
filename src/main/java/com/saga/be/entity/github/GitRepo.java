package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GithubInstallation;
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
	name = "git_repo",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_repo_provider_id", columnNames = {"provider", "repository_id"}),
		@UniqueConstraint(name = "uk_git_repo_project_full_name", columnNames = {"project_id", "full_name"})
	}
)
public class GitRepo extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "installation_id", nullable = true)
	private GithubInstallation installation;

	@Column(name = "name", length = 255)
	private String name;

	@Column(name = "url", length = 500)
	private String url;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", length = 32, nullable = false)
	private GitProvider provider;

	@Column(name = "repository_id")
	private Long repositoryId;

	@Column(name = "owner_login", length = 255)
	private String ownerLogin;

	@Column(name = "full_name", length = 255)
	private String fullName;

	@Column(name = "default_branch", length = 128)
	private String defaultBranch;

	@Enumerated(EnumType.STRING)
	@Column(name = "connection_status", length = 32, nullable = false)
	private IntegrationStatus connectionStatus;

	@Column(name = "sync_cursor")
	private LocalDateTime syncCursor;

	@Column(name = "consecutive_failures", nullable = false)
	private Integer consecutiveFailures;

	@Column(name = "last_synced_at")
	private LocalDateTime lastSyncedAt;

	@Column(name = "review_cutover_at")
	private LocalDateTime reviewCutoverAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

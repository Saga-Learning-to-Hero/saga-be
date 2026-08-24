package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.GitHubInstallationStatus;
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
	name = "github_installation",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_github_installation_id", columnNames = {"installation_id"})
	}
)
public class GithubInstallation extends BaseEntity {

	@Column(name = "installation_id", nullable = false)
	private Long installationId;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "installed_by_user_id", nullable = true)
	private UserAccount installedBy;

	@Column(name = "account_login", length = 255)
	private String accountLogin;

	@Column(name = "account_type", length = 64)
	private String accountType;

	@Enumerated(EnumType.STRING)
	@Column(name = "installation_status", length = 32, nullable = false)
	private GitHubInstallationStatus installationStatus;

	@Column(name = "last_verified_at")
	private LocalDateTime lastVerifiedAt;

	@Column(name = "consecutive_failures", nullable = false)
	private Integer consecutiveFailures;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

package com.saga.be.entity.integration;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
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
	name = "identity_map",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_identity_user_provider", columnNames = {"user_account_id", "provider"}),
		@UniqueConstraint(name = "uk_identity_provider_external", columnNames = {"provider", "external_account_id"})
	}
)
public class IdentityMap extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;

	@Enumerated(EnumType.STRING)
	@Column(name = "provider", length = 32, nullable = false)
	private IntegrationProvider provider;

	@Column(name = "external_account_id", length = 255)
	private String externalAccountId;

	@Column(name = "external_username", length = 255)
	private String externalUsername;

	@Column(name = "external_email", length = 255)
	private String externalEmail;

	@Enumerated(EnumType.STRING)
	@Column(name = "mapping_status", length = 32, nullable = false)
	private IdentityMappingStatus mappingStatus;

	@Column(name = "verified_at")
	private LocalDateTime verifiedAt;

	@Column(name = "disconnected_at")
	private LocalDateTime disconnectedAt;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "reviewed_by_user_id", nullable = true)
	private UserAccount reviewedBy;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

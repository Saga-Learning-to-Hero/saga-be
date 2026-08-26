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
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "identity_map",
	uniqueConstraints = {
		@UniqueConstraint(
				name = "uk_identity_active_provider_subject",
				columnNames = {"provider", "active_provider_subject"})
	},
	indexes = {
		@Index(name = "ix_identity_user_provider", columnList = "user_account_id, provider"),
		@Index(name = "ix_identity_user_provider_primary", columnList = "user_account_id, provider, is_primary")
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

	@Column(name = "provider_display_name", length = 255)
	private String providerDisplayName;

	@Column(name = "provider_avatar_url", length = 500)
	private String providerAvatarUrl;

	@Column(name = "provider_instance_id", length = 255)
	private String providerInstanceId;

	@Enumerated(EnumType.STRING)
	@Column(name = "mapping_status", length = 32, nullable = false)
	private IdentityMappingStatus mappingStatus;

	@Column(name = "is_primary", nullable = false)
	private boolean primary;

	@Column(name = "verified_at")
	private LocalDateTime verifiedAt;

	@Column(name = "linked_at")
	private LocalDateTime linkedAt;

	@Column(name = "last_verified_at")
	private LocalDateTime lastVerifiedAt;

	@Column(name = "disconnected_at")
	private LocalDateTime disconnectedAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Generated
	@Column(name = "active_provider_subject", length = 255, insertable = false, updatable = false)
	private String activeProviderSubject;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "reviewed_by_user_id", nullable = true)
	private UserAccount reviewedBy;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

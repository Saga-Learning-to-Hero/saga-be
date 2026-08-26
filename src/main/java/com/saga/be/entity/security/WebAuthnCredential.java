package com.saga.be.entity.security;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "webauthn_credential",
	uniqueConstraints = {@UniqueConstraint(name = "uk_webauthn_credential_id", columnNames = {"credential_id"})},
	indexes = {@Index(name = "ix_webauthn_user", columnList = "user_account_id")}
)
public class WebAuthnCredential extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_account_id", nullable = false)
	private UserAccount userAccount;

	@Column(name = "credential_id", length = 512, nullable = false)
	private String credentialId;

	@Column(name = "public_key_cose", columnDefinition = "TEXT", nullable = false)
	private String publicKeyCose;

	@Column(name = "signature_count", nullable = false)
	private Long signatureCount;

	@Column(name = "uv_initialized")
	private Boolean uvInitialized;

	@Column(name = "backup_eligible")
	private Boolean backupEligible;

	@Column(name = "backup_state")
	private Boolean backupState;

	@Column(name = "transports", length = 255)
	private String transports;

	@Column(name = "label", length = 255)
	private String label;

	@Column(name = "last_used_at")
	private LocalDateTime lastUsedAt;
}

package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A team's own AI key, entered by its leader and used to review the team's commits. One row per
 * project (team); replacing the key overwrites the row and resets it to UNVERIFIED, revoking clears
 * the secret material. Encrypted exactly like a course key.
 */
@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_team_credential", uniqueConstraints = @UniqueConstraint(name = "uk_ai_team_credential_project", columnNames = "project_id"))
public class AiTeamCredential extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
	@Enumerated(EnumType.STRING) @Column(name = "provider", length = 32, nullable = false) private AiProvider provider;
	@Column(name = "model_id", length = 128, nullable = false) private String modelId;
	/** Base64 AES-256-GCM ciphertext of the raw provider API key. Never returned by any API. */
	@Column(name = "encrypted_secret", columnDefinition = "TEXT", nullable = false) private String encryptedSecret;
	@Column(name = "encryption_nonce", length = 32, nullable = false) private String encryptionNonce;
	@Column(name = "encryption_key_version", nullable = false) private int encryptionKeyVersion;
	@Column(name = "fingerprint", length = 64, nullable = false) private String fingerprint;
	@Column(name = "last_four", length = 4, nullable = false) private String lastFour;
	@Enumerated(EnumType.STRING) @Column(name = "status", length = 32, nullable = false) private AiCredentialStatus status;
	/** Last provider failure that changed the status (auth / quota), for the leader to read. */
	@Column(name = "last_error_code", length = 64) private String lastErrorCode;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(name = "last_successful_use_at") private LocalDateTime lastSuccessfulUseAt;
	@Column(name = "revoked_at") private LocalDateTime revokedAt;
}

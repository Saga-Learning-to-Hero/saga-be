package com.saga.be.entity.ai;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One row per (course, providerRole, provider) -- never versioned/history-tracked. Replacing a
 * key overwrites {@code encryptedSecret}/{@code encryptionNonce} on the same row and resets status
 * to UNVERIFIED; revoking clears the secret material and sets REVOKED. A course may hold one
 * credential per provider for each role at the same time (V34). */
@Getter @Setter @NoArgsConstructor @Entity
@Table(name = "ai_course_provider_credential", uniqueConstraints = @UniqueConstraint(name = "uk_ai_course_provider_credential_role_provider", columnNames = {"course_id", "provider_role", "provider"}))
public class CourseAiProviderCredential extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "course_id", nullable = false) private Course course;
	@Enumerated(EnumType.STRING) @Column(name = "provider_role", length = 32, nullable = false) private AiProviderRole providerRole;
	@Enumerated(EnumType.STRING) @Column(name = "provider", length = 32, nullable = false) private AiProvider provider;
	/** Base64 AES-256-GCM ciphertext of the raw provider API key. Never returned by any API. */
	@Column(name = "encrypted_secret", columnDefinition = "TEXT", nullable = false) private String encryptedSecret;
	@Column(name = "encryption_nonce", length = 32, nullable = false) private String encryptionNonce;
	@Column(name = "encryption_key_version", nullable = false) private int encryptionKeyVersion;
	/** SHA-256 hex of the raw key -- lets two submissions detect "same credential" without ever
	 * decrypting, and lets rotation change identity without exposing any part of the real key. */
	@Column(name = "fingerprint", length = 64, nullable = false) private String fingerprint;
	@Column(name = "last_four", length = 4, nullable = false) private String lastFour;
	@Enumerated(EnumType.STRING) @Column(name = "status", length = 32, nullable = false) private AiCredentialStatus status;
	@ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "created_by_user_id") private UserAccount createdBy;
	@Column(name = "last_successful_use_at") private LocalDateTime lastSuccessfulUseAt;
	@Column(name = "revoked_at") private LocalDateTime revokedAt;
}

package com.saga.be.entity.account;

import com.saga.be.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * One-time password reset token. Only the SHA-256 hash of the raw token is stored;
 * the raw token exists only in the outbound email link and the reset API request.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "password_reset_token",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_password_reset_token_hash", columnNames = {"token_hash"})
	}
)
public class PasswordResetToken extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private UserAccount user;

	@Column(name = "token_hash", length = 64, nullable = false)
	private String tokenHash;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "used_at")
	private LocalDateTime usedAt;
}

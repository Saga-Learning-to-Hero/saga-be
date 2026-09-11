package com.saga.be.auth;

import com.saga.be.config.AuthProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.account.PasswordResetToken;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import com.saga.be.integration.oauth.Pkce;
import com.saga.be.repository.PasswordResetTokenRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.confirmation.EvidenceHasher;
import com.saga.be.service.mail.EmailOutboxService;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

/**
 * Forgot/reset password via a one-time emailed link. Not an OTP flow.
 *
 * <p>The raw token is generated with the project's existing SecureRandom generator
 * ({@link Pkce#newVerifier()}, 32 bytes / 256 bits, URL-safe Base64 without padding) and is
 * never persisted or logged — only its SHA-256 hex digest ({@link EvidenceHasher#sha256(String)})
 * is stored, matching the hashing convention already used for evidence snapshots.
 */
@Service
@Profile("!test")
public class PasswordResetService {

	private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
	private static final String EMAIL_TYPE = "PASSWORD_RESET";

	private final UserAccountRepository users;
	private final PasswordResetTokenRepository tokens;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final EmailOutboxService emails;
	private final AuthProperties properties;
	private final TransactionTemplate writes;

	public PasswordResetService(
			UserAccountRepository users,
			PasswordResetTokenRepository tokens,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			EmailOutboxService emails,
			AuthProperties properties,
			PlatformTransactionManager transactionManager) {
		this.users = users;
		this.tokens = tokens;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.emails = emails;
		this.properties = properties;
		this.writes = new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager"));
	}

	/**
	 * Always safe to call for any input. Never reveals whether the account exists, whether it is
	 * Google-only, or whether a local password is already set — callers must always return the
	 * same generic public response regardless of what happens here.
	 *
	 * <p>Uses a programmatic {@link TransactionTemplate} (not {@code @Transactional}) so the
	 * pessimistic row lock below is guaranteed to be held for the whole invalidate+insert+enqueue
	 * sequence regardless of how this bean is obtained/proxied — the same pattern already used by
	 * the project's other lock-then-mutate services (e.g. sync claim/credential services).
	 */
	public void forgotPassword(String rawEmail) {
		if (!StringUtils.hasText(rawEmail)) {
			return;
		}
		String email = rawEmail.trim().toLowerCase(Locale.ROOT);
		Optional<UserAccount> found = users.findByEmail(email);
		if (found.isEmpty()) {
			return;
		}
		UUID userId = found.get().getId();
		writes.executeWithoutResult(status -> {
			// Row lock: two concurrent forgot-password requests for the same user must serialize
			// here so the second call's invalidateUnusedForUser sees the first call's
			// already-committed token (bulk UPDATE-then-INSERT alone cannot see a sibling
			// transaction's uncommitted row).
			UserAccount account = users.findByIdForUpdate(userId).orElse(null);
			if (account == null) {
				return;
			}
			String rawToken = Pkce.newVerifier();
			String tokenHash = EvidenceHasher.sha256(rawToken);
			LocalDateTime now = LocalDateTime.now();
			tokens.invalidateUnusedForUser(account.getId(), now);
			PasswordResetToken token = new PasswordResetToken();
			token.setUser(account);
			token.setTokenHash(tokenHash);
			token.setExpiresAt(now.plus(properties.getPasswordResetTtl()));
			tokens.save(token);
			enqueueResetEmail(account, rawToken);
			log.info("auth method=PASSWORD_RESET_REQUEST result=queued userId={}", account.getId());
		});
	}

	/** Verifies the raw token, applies the new password, and consumes the token atomically. */
	public void resetPassword(String rawToken, String newPassword) {
		if (!StringUtils.hasText(rawToken)) {
			throw invalidToken();
		}
		String tokenHash = EvidenceHasher.sha256(rawToken.trim());
		writes.executeWithoutResult(status -> {
			PasswordResetToken token = tokens.findByTokenHashForUpdate(tokenHash).orElseThrow(this::invalidToken);
			if (token.getUsedAt() != null) {
				throw invalidToken();
			}
			if (token.getExpiresAt() == null || token.getExpiresAt().isBefore(LocalDateTime.now())) {
				throw new AuthException(
						AuthErrorCode.PASSWORD_RESET_TOKEN_EXPIRED,
						HttpStatus.BAD_REQUEST,
						"This password reset link has expired.");
			}
			// Self-confirmed: the reset API contract carries only newPassword, not a second field.
			// This still runs the project's exact length/strength policy.
			passwordPolicy.validate(newPassword, newPassword);
			UserAccount account = token.getUser();
			account.setPasswordHash(passwordEncoder.encode(newPassword));
			users.save(account);
			token.setUsedAt(LocalDateTime.now());
			tokens.save(token);
			log.info("auth method=PASSWORD_RESET result=success userId={}", account.getId());
		});
	}

	private void enqueueResetEmail(UserAccount account, String rawToken) {
		String resetUrl = buildResetUrl(rawToken);
		long minutes = Math.max(1, properties.getPasswordResetTtl().toMinutes());
		String greetingName = StringUtils.hasText(account.getFullName()) ? account.getFullName() : account.getEmail();
		String subject = "SAGA — Đặt lại mật khẩu";
		String textBody = "Xin chào " + greetingName + ",\n\n"
				+ "Có yêu cầu đặt lại mật khẩu cho tài khoản SAGA của bạn.\n\n"
				+ "Đặt lại mật khẩu: " + resetUrl + "\n\n"
				+ "Liên kết này hết hạn sau " + minutes + " phút.\n\n"
				+ "Nếu bạn không yêu cầu điều này, hãy bỏ qua email này — mật khẩu của bạn sẽ không đổi.\n\n"
				+ "SAGA — Student Activity Graph Based Continuous Assessment\nThis is an automated email.";
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("subject", subject);
		payload.put("textBody", textBody);
		emails.enqueue(new EmailEnqueueRequest(account.getEmail(), account.getId(), EMAIL_TYPE, "password-reset", payload, null));
	}

	private String buildResetUrl(String rawToken) {
		String base = properties.getPasswordResetUrl();
		String encoded;
		try {
			encoded = URLEncoder.encode(rawToken, StandardCharsets.UTF_8.name());
		} catch (UnsupportedEncodingException ex) {
			throw new IllegalStateException("UTF-8 unavailable", ex);
		}
		String separator = base.contains("?") ? "&" : "?";
		return base + separator + "token=" + encoded;
	}

	private AuthException invalidToken() {
		return new AuthException(
				AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID,
				HttpStatus.BAD_REQUEST,
				"This password reset link is invalid or has already been used.");
	}
}

package com.saga.be.auth;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.AuthException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

public class StepUpAuthenticationService {

	public static final String SESSION_ATTR = "saga.stepUpAuthenticatedAt";

	public interface Users {
		java.util.Optional<UserAccount> findById(UUID id);
	}

	public interface Clock {
		Instant now();
	}

	private final Users users;
	private final PasswordEncoder passwordEncoder;
	private final Duration window;
	private final int maxFailures;
	private final ConcurrentHashMap<UUID, AtomicInteger> failures = new ConcurrentHashMap<>();
	private final Clock clock;

	public StepUpAuthenticationService(Users users, PasswordEncoder passwordEncoder, Duration window, int maxFailures) {
		this(users, passwordEncoder, window, maxFailures, Instant::now);
	}

	public StepUpAuthenticationService(
			Users users, PasswordEncoder passwordEncoder, Duration window, int maxFailures, Clock clock) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.window = window;
		this.maxFailures = maxFailures;
		this.clock = clock;
	}

	public Instant reauthPassword(UUID userId, String password) {
		if (failures.computeIfAbsent(userId, id -> new AtomicInteger()).get() >= maxFailures) {
			throw new IntegrationException(
					IntegrationErrorCode.REAUTH_RATE_LIMITED,
					HttpStatus.TOO_MANY_REQUESTS,
					"Too many re-authentication attempts.");
		}
		UserAccount account = users.findById(userId)
				.orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed."));
		if (!StringUtils.hasText(password)
				|| !StringUtils.hasText(account.getPasswordHash())
				|| !passwordEncoder.matches(password, account.getPasswordHash())) {
			failures.get(userId).incrementAndGet();
			throw new AuthException(AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
		}
		failures.remove(userId);
		return clock.now();
	}

	public Instant markWebAuthn(UUID userId) {
		failures.remove(userId);
		return clock.now();
	}

	public boolean isFresh(Instant stepUpAt) {
		return stepUpAt != null && !clock.now().isAfter(stepUpAt.plus(window));
	}

	public void requireFresh(Instant stepUpAt) {
		if (stepUpAt == null) {
			throw new IntegrationException(
					IntegrationErrorCode.STEP_UP_REQUIRED, HttpStatus.FORBIDDEN, "Recent re-authentication is required.");
		}
		if (!isFresh(stepUpAt)) {
			throw new IntegrationException(
					IntegrationErrorCode.STEP_UP_EXPIRED, HttpStatus.FORBIDDEN, "Re-authentication has expired.");
		}
	}

	public AuditSource source() {
		return AuditSource.AUTH;
	}
}

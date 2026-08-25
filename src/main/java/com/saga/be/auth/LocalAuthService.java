package com.saga.be.auth;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class LocalAuthService {

	private static final Logger log = LoggerFactory.getLogger(LocalAuthService.class);

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final AccountStatusGuard statusGuard;

	public LocalAuthService(
			UserAccountRepository users, PasswordEncoder passwordEncoder, AccountStatusGuard statusGuard) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.statusGuard = statusGuard;
	}

	@Transactional(readOnly = true)
	public Authentication authenticate(String identifier, String password) {
		if (!StringUtils.hasText(identifier) || !StringUtils.hasText(password)) {
			throw invalid();
		}
		Optional<UserAccount> found = resolve(identifier.trim());
		if (found.isEmpty()) {
			throw invalid();
		}
		UserAccount account = found.get();
		statusGuard.requireActive(account);
		if (!StringUtils.hasText(account.getPasswordHash())
				|| !passwordEncoder.matches(password, account.getPasswordHash())) {
			log.info("auth method=LOCAL result=failure category=INVALID_CREDENTIALS");
			throw invalid();
		}
		log.info("auth method=LOCAL result=success userId={} role={}", account.getId(), account.getAccountRole());
		return SagaAuthentications.authenticated(account);
	}

	private Optional<UserAccount> resolve(String identifier) {
		if (identifier.contains("@")) {
			return users.findByEmail(identifier.toLowerCase(Locale.ROOT));
		}
		return users.findByUsername(identifier);
	}

	private static AuthException invalid() {
		return new AuthException(AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
	}
}

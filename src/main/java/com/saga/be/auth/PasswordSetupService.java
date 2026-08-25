package com.saga.be.auth;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import java.util.UUID;
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
public class PasswordSetupService {

	private static final Logger log = LoggerFactory.getLogger(PasswordSetupService.class);

	private final UserAccountRepository users;
	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy passwordPolicy;
	private final AccountStatusGuard statusGuard;

	public PasswordSetupService(
			UserAccountRepository users,
			PasswordEncoder passwordEncoder,
			PasswordPolicy passwordPolicy,
			AccountStatusGuard statusGuard) {
		this.users = users;
		this.passwordEncoder = passwordEncoder;
		this.passwordPolicy = passwordPolicy;
		this.statusGuard = statusGuard;
	}

	@Transactional
	public Authentication setup(UUID userId, String newPassword, String confirmPassword) {
		passwordPolicy.validate(newPassword, confirmPassword);
		UserAccount account = users.findById(userId).orElseThrow(() -> new AuthException(
				AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed."));
		statusGuard.requireActive(account);
		if (account.getAccountRole() != AccountRole.STUDENT && account.getAccountRole() != AccountRole.LECTURER) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE, HttpStatus.FORBIDDEN, "Password setup is not required.");
		}
		if (!StringUtils.hasText(account.getGoogleSubject())) {
			throw new AuthException(
					AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE, HttpStatus.FORBIDDEN, "Password setup is not required.");
		}
		if (StringUtils.hasText(account.getPasswordHash())) {
			throw new AuthException(
					AuthErrorCode.PASSWORD_ALREADY_SET, HttpStatus.CONFLICT, "A password is already set for this account.");
		}
		account.setPasswordHash(passwordEncoder.encode(newPassword));
		UserAccount saved = users.save(account);
		log.info("auth method=PASSWORD_SETUP result=success userId={} role={}", saved.getId(), saved.getAccountRole());
		return SagaAuthentications.authenticated(saved);
	}
}

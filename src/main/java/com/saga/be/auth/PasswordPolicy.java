package com.saga.be.auth;

import com.saga.be.config.AuthProperties;
import com.saga.be.exception.AuthException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PasswordPolicy {

	private final AuthProperties properties;

	public PasswordPolicy(AuthProperties properties) {
		this.properties = properties;
	}

	public void validate(String password, String confirmPassword) {
		if (!StringUtils.hasText(password) || !StringUtils.hasText(confirmPassword)) {
			throw new AuthException(
					AuthErrorCode.PASSWORD_POLICY_VIOLATION, HttpStatus.BAD_REQUEST, "Password does not meet policy.");
		}
		if (!password.equals(confirmPassword)) {
			throw new AuthException(
					AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH, HttpStatus.BAD_REQUEST, "Password confirmation does not match.");
		}
		int min = properties.getPassword().getMinLength();
		int max = properties.getPassword().getMaxLength();
		if (password.length() < min || password.length() > max) {
			throw new AuthException(
					AuthErrorCode.PASSWORD_POLICY_VIOLATION, HttpStatus.BAD_REQUEST, "Password does not meet policy.");
		}
	}
}

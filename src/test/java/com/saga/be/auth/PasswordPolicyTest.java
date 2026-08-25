package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.saga.be.config.AuthProperties;
import com.saga.be.exception.AuthException;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

	private final PasswordPolicy policy = new PasswordPolicy(new AuthProperties());

	@Test
	void rejectsBlank() {
		AuthException ex = assertThrows(AuthException.class, () -> policy.validate(" ", " "));
		assertEquals(AuthErrorCode.PASSWORD_POLICY_VIOLATION, ex.getCode());
	}

	@Test
	void rejectsMismatch() {
		AuthException ex = assertThrows(AuthException.class, () -> policy.validate("longenough1", "longenough2"));
		assertEquals(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH, ex.getCode());
	}

	@Test
	void rejectsTooShort() {
		AuthException ex = assertThrows(AuthException.class, () -> policy.validate("short", "short"));
		assertEquals(AuthErrorCode.PASSWORD_POLICY_VIOLATION, ex.getCode());
	}

	@Test
	void acceptsMinimumLength() {
		policy.validate("longenough1", "longenough1");
	}
}

package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.security.SagaUserPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SagaAuthenticationsTest {

	@Test
	void googleStudentWithoutPasswordRequiresSetup() {
		UserAccount account = account(AccountRole.STUDENT, "sub-student", null);
		SagaUserPrincipal principal = (SagaUserPrincipal) SagaAuthentications.authenticated(account).getPrincipal();
		assertTrue(principal.isPasswordSetupRequired());
	}

	@Test
	void googleLecturerWithoutPasswordRequiresSetup() {
		UserAccount account = account(AccountRole.LECTURER, "sub-lecturer", null);
		SagaUserPrincipal principal = (SagaUserPrincipal) SagaAuthentications.authenticated(account).getPrincipal();
		assertTrue(principal.isPasswordSetupRequired());
	}

	@Test
	void registeredPersonalStudentDoesNotRequireSetup() {
		UserAccount account = account(AccountRole.STUDENT, null, "$argon2id$hash");
		SagaUserPrincipal principal = (SagaUserPrincipal) SagaAuthentications.authenticated(account).getPrincipal();
		assertFalse(principal.isPasswordSetupRequired());
	}

	@Test
	void adminNeverRequiresPasswordSetup() {
		UserAccount account = account(AccountRole.ADMIN, null, "$argon2id$hash");
		SagaUserPrincipal principal = (SagaUserPrincipal) SagaAuthentications.authenticated(account).getPrincipal();
		assertFalse(principal.isPasswordSetupRequired());
		assertEquals(AccountRole.ADMIN, principal.getRole());
	}

	private static UserAccount account(AccountRole role, String googleSubject, String hash) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("user@example.com");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setGoogleSubject(googleSubject);
		account.setPasswordHash(hash);
		return account;
	}
}

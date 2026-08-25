package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.security.SagaUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class PasswordSetupServiceTest {

	@Mock
	private UserAccountRepository users;

	private PasswordEncoder passwordEncoder;
	private PasswordSetupService service;

	@BeforeEach
	void setUp() {
		AuthProperties properties = new AuthProperties();
		passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
		service = new PasswordSetupService(users, passwordEncoder, new PasswordPolicy(properties), new AccountStatusGuard());
	}

	@Test
	void validSetupPersistsArgon2HashAndClearsSetupFlag() {
		UUID id = UUID.randomUUID();
		UserAccount account = student(id, null);
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Authentication authentication = service.setup(id, "longenough1", "longenough1");
		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		assertTrue(captor.getValue().getPasswordHash().startsWith("$argon2id$"));
		assertTrue(passwordEncoder.matches("longenough1", captor.getValue().getPasswordHash()));
		assertFalse(((SagaUserPrincipal) authentication.getPrincipal()).isPasswordSetupRequired());
	}

	@Test
	void lecturerGoogleSetupPersistsArgon2HashAndClearsSetupFlag() {
		UUID id = UUID.randomUUID();
		UserAccount account = lecturer(id, "sub-lecturer", null);
		when(users.findById(id)).thenReturn(Optional.of(account));
		when(users.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		Authentication authentication = service.setup(id, "longenough1", "longenough1");
		ArgumentCaptor<UserAccount> captor = ArgumentCaptor.forClass(UserAccount.class);
		verify(users).save(captor.capture());
		assertTrue(captor.getValue().getPasswordHash().startsWith("$argon2id$"));
		assertTrue(passwordEncoder.matches("longenough1", captor.getValue().getPasswordHash()));
		SagaUserPrincipal principal = (SagaUserPrincipal) authentication.getPrincipal();
		assertEquals(AccountRole.LECTURER, principal.getRole());
		assertFalse(principal.isPasswordSetupRequired());

		when(users.findByEmail("antv12@fpt.edu.vn")).thenReturn(Optional.of(captor.getValue()));
		Authentication local = new LocalAuthService(users, passwordEncoder, new AccountStatusGuard())
				.authenticate("antv12@fpt.edu.vn", "longenough1");
		SagaUserPrincipal localPrincipal = (SagaUserPrincipal) local.getPrincipal();
		assertEquals(AccountRole.LECTURER, localPrincipal.getRole());
		assertFalse(localPrincipal.isPasswordSetupRequired());
	}

	@Test
	void existingPasswordCannotBeOverwrittenBySetup() {
		UUID id = UUID.randomUUID();
		when(users.findById(id)).thenReturn(Optional.of(student(id, "already")));

		AuthException ex = assertThrows(AuthException.class, () -> service.setup(id, "longenough1", "longenough1"));
		assertEquals(AuthErrorCode.PASSWORD_ALREADY_SET, ex.getCode());
	}

	@Test
	void adminCannotUsePasswordSetup() {
		UUID id = UUID.randomUUID();
		UserAccount admin = new UserAccount();
		admin.setId(id);
		admin.setEmail("admin@saga.local");
		admin.setAccountRole(AccountRole.ADMIN);
		admin.setAccountStatus(AccountStatus.ACTIVE);
		when(users.findById(id)).thenReturn(Optional.of(admin));

		AuthException ex = assertThrows(AuthException.class, () -> service.setup(id, "longenough1", "longenough1"));
		assertEquals(AuthErrorCode.GOOGLE_ACCOUNT_NOT_ELIGIBLE, ex.getCode());
	}

	@Test
	void googleStudentWithoutPasswordRequiresSetup() {
		UserAccount account = student(UUID.randomUUID(), null);
		assertTrue(SagaAuthentications.isGooglePasswordSetupRequired(account));
	}

	private static UserAccount student(UUID id, String hash) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail("anvse170102@fpt.edu.vn");
		account.setGoogleSubject("google-sub-student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(hash);
		return account;
	}

	private static UserAccount lecturer(UUID id, String googleSubject, String hash) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail("antv12@fpt.edu.vn");
		account.setGoogleSubject(googleSubject);
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(hash);
		return account;
	}
}

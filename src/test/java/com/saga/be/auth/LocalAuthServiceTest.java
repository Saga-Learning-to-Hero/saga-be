package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;

@ExtendWith(MockitoExtension.class)
class LocalAuthServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private PasswordEncoder passwordEncoder;

	private LocalAuthService service;

	@BeforeEach
	void setUp() {
		service = new LocalAuthService(users, passwordEncoder, new AccountStatusGuard());
	}

	@Test
	void correctPasswordAuthenticatesWithDatabaseRole() {
		UserAccount account = user("a@fpt.edu.vn", "hash", AccountRole.STUDENT);
		when(users.findByEmail("a@fpt.edu.vn")).thenReturn(Optional.of(account));
		when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

		Authentication authentication = service.authenticate("a@fpt.edu.vn", "secret");
		SagaUserPrincipal principal = (SagaUserPrincipal) authentication.getPrincipal();
		assertEquals(AccountRole.STUDENT, principal.getRole());
		assertEquals(account.getId(), principal.getUserId());
	}

	@Test
	void wrongPasswordIsGenericFailure() {
		UserAccount account = user("a@fpt.edu.vn", "hash", AccountRole.STUDENT);
		when(users.findByEmail("a@fpt.edu.vn")).thenReturn(Optional.of(account));
		when(passwordEncoder.matches("bad", "hash")).thenReturn(false);

		AuthException ex = assertThrows(AuthException.class, () -> service.authenticate("a@fpt.edu.vn", "bad"));
		assertEquals(AuthErrorCode.INVALID_CREDENTIALS, ex.getCode());
	}

	@Test
	void missingPasswordHashIsGenericFailure() {
		UserAccount account = user("a@fpt.edu.vn", null, AccountRole.STUDENT);
		when(users.findByEmail("a@fpt.edu.vn")).thenReturn(Optional.of(account));

		AuthException ex = assertThrows(AuthException.class, () -> service.authenticate("a@fpt.edu.vn", "secret"));
		assertEquals(AuthErrorCode.INVALID_CREDENTIALS, ex.getCode());
	}

	@Test
	void adminLogsInByUsername() {
		UserAccount account = user("admin@saga.local", "hash", AccountRole.ADMIN);
		account.setUsername("admin");
		when(users.findByUsername("admin")).thenReturn(Optional.of(account));
		when(passwordEncoder.matches("pw", "hash")).thenReturn(true);

		Authentication authentication = service.authenticate("admin", "pw");
		assertEquals(AccountRole.ADMIN, ((SagaUserPrincipal) authentication.getPrincipal()).getRole());
	}

	@Test
	void lecturerLocalLoginAfterPasswordUsesDatabaseRole() {
		UserAccount account = user("antv12@fpt.edu.vn", "hash", AccountRole.LECTURER);
		when(users.findByEmail("antv12@fpt.edu.vn")).thenReturn(Optional.of(account));
		when(passwordEncoder.matches("secret", "hash")).thenReturn(true);

		Authentication authentication = service.authenticate("antv12@fpt.edu.vn", "secret");
		assertEquals(AccountRole.LECTURER, ((SagaUserPrincipal) authentication.getPrincipal()).getRole());
	}

	@Test
	void unknownIdentifierDoesNotCreateAnAccount() {
		when(users.findByEmail("unknown@gmail.com")).thenReturn(Optional.empty());
		AuthException ex = assertThrows(AuthException.class, () -> service.authenticate("unknown@gmail.com", "secret"));
		assertEquals(AuthErrorCode.INVALID_CREDENTIALS, ex.getCode());
		org.mockito.Mockito.verify(users, org.mockito.Mockito.never()).save(org.mockito.ArgumentMatchers.any());
	}

	@Test
	void inactiveAccountIsDisabledNotEnumeratedAsWrongPassword() {
		UserAccount account = user("a@fpt.edu.vn", "hash", AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.INACTIVE);
		when(users.findByEmail("a@fpt.edu.vn")).thenReturn(Optional.of(account));

		AuthException ex = assertThrows(AuthException.class, () -> service.authenticate("a@fpt.edu.vn", "secret"));
		assertEquals(AuthErrorCode.ACCOUNT_DISABLED, ex.getCode());
	}

	private static UserAccount user(String email, String hash, AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setPasswordHash(hash);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.roster.InvitationClaimService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GoogleAccountServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private LecturerProfileRepository lecturers;

	private GoogleAccountService service;
	private final Set<String> allowed = Set.of("fpt.edu.vn", "fe.edu.vn");

	@BeforeEach
	void setUp() {
		service = new GoogleAccountService(users, students, lecturers, new GoogleRoleResolver(), new AccountStatusGuard());
	}

	@Test
	void existingSubjectKeepsDatabaseRole() {
		UserAccount existing = account(AccountRole.STUDENT, "anvse170102@fpt.edu.vn", "sub-1");
		when(users.findByGoogleSubject("sub-1")).thenReturn(Optional.of(existing));

		UserAccount result = service.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity(
						"sub-1", "anvse170102@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
				allowed);

		assertEquals(AccountRole.STUDENT, result.getAccountRole());
		verify(users, never()).save(any());
	}

	@Test
	void emailMatchLinksSubjectWhenNull() {
		UserAccount existing = account(AccountRole.STUDENT, "anvse170102@fpt.edu.vn", null);
		when(users.findByGoogleSubject("sub-2")).thenReturn(Optional.empty());
		when(users.findByEmail("anvse170102@fpt.edu.vn")).thenReturn(Optional.of(existing));
		when(users.save(existing)).thenReturn(existing);

		UserAccount result = service.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity(
						"sub-2", "anvse170102@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
				allowed);

		assertEquals("sub-2", result.getGoogleSubject());
		assertEquals(AccountRole.STUDENT, result.getAccountRole());
	}

	@Test
	void differentSubjectConflicts() {
		UserAccount existing = account(AccountRole.STUDENT, "anvse170102@fpt.edu.vn", "other-sub");
		when(users.findByGoogleSubject("sub-3")).thenReturn(Optional.empty());
		when(users.findByEmail("anvse170102@fpt.edu.vn")).thenReturn(Optional.of(existing));

		AuthException ex = assertThrows(
				AuthException.class,
				() -> service.authenticateOrProvision(
						new GoogleAccountService.GoogleOidcIdentity(
								"sub-3", "anvse170102@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
						allowed));
		assertEquals(AuthErrorCode.GOOGLE_IDENTITY_CONFLICT, ex.getCode());
	}

	@Test
	void existingStudentDoesNotBecomeLecturer() {
		UserAccount existing = account(AccountRole.STUDENT, "antv12@fpt.edu.vn", "sub-4");
		when(users.findByGoogleSubject("sub-4")).thenReturn(Optional.of(existing));

		UserAccount result = service.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity("sub-4", "antv12@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
				allowed);
		assertEquals(AccountRole.STUDENT, result.getAccountRole());
	}

	@Test
	void personalGmailCannotUseGoogleLogin() {
		AuthException ex = assertThrows(
				AuthException.class,
				() -> service.authenticateOrProvision(
						new GoogleAccountService.GoogleOidcIdentity(
								"sub-g", "student@gmail.com", true, null, "A", null),
						allowed));
		assertEquals(AuthErrorCode.GOOGLE_DOMAIN_NOT_ALLOWED, ex.getCode());
	}

	@Test
	void newFptStudentIsProvisionedAsStudent() {
		when(users.findByGoogleSubject("sub-new")).thenReturn(Optional.empty());
		when(users.findByEmail("anvse170102@fpt.edu.vn")).thenReturn(Optional.empty());
		when(users.save(any(UserAccount.class))).thenAnswer(invocation -> {
			UserAccount account = invocation.getArgument(0);
			account.setId(UUID.randomUUID());
			return account;
		});
		when(students.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

		UserAccount created = service.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity(
						"sub-new", "anvse170102@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
				allowed);
		assertEquals(AccountRole.STUDENT, created.getAccountRole());
		assertEquals("sub-new", created.getGoogleSubject());
	}

	@Test
	void successfulStudentGoogleLoginClaimsInvitations() {
		InvitationClaimService claims = org.mockito.Mockito.mock(InvitationClaimService.class);
		GoogleAccountService withClaims =
				new GoogleAccountService(users, students, lecturers, new GoogleRoleResolver(), new AccountStatusGuard(), claims);
		UserAccount existing = account(AccountRole.STUDENT, "anvse170102@fpt.edu.vn", "sub-claim");
		when(users.findByGoogleSubject("sub-claim")).thenReturn(Optional.of(existing));
		withClaims.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity(
						"sub-claim", "anvse170102@fpt.edu.vn", true, "fpt.edu.vn", "A", null),
				allowed);
		verify(claims).claimQuietly(existing);
	}

	@Test
	void lecturerGoogleLoginDoesNotClaimStudentInvitations() {
		InvitationClaimService claims = org.mockito.Mockito.mock(InvitationClaimService.class);
		GoogleAccountService withClaims =
				new GoogleAccountService(users, students, lecturers, new GoogleRoleResolver(), new AccountStatusGuard(), claims);
		UserAccount existing = account(AccountRole.LECTURER, "antv12@fe.edu.vn", "sub-lec");
		when(users.findByGoogleSubject("sub-lec")).thenReturn(Optional.of(existing));
		withClaims.authenticateOrProvision(
				new GoogleAccountService.GoogleOidcIdentity("sub-lec", "antv12@fe.edu.vn", true, "fe.edu.vn", "A", null),
				allowed);
		verify(claims, never()).claimQuietly(any());
	}

	private static UserAccount account(AccountRole role, String email, String subject) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setGoogleSubject(subject);
		return account;
	}
}

package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.config.AuthProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.entity.account.PasswordResetToken;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.PasswordResetTokenRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.confirmation.EvidenceHasher;
import com.saga.be.service.mail.EmailOutboxService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

	@Mock
	private UserAccountRepository users;

	@Mock
	private PasswordResetTokenRepository tokens;

	@Mock
	private EmailOutboxService emails;

	@Mock
	private PlatformTransactionManager transactionManager;

	private PasswordEncoder passwordEncoder;
	private AuthProperties properties;
	private PasswordResetService service;

	@BeforeEach
	void setUp() {
		properties = new AuthProperties();
		passwordEncoder = new Argon2PasswordEncoder(16, 32, 1, 19_456, 2);
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(ArgumentMatchers.any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		service = new PasswordResetService(
				users, tokens, passwordEncoder, new PasswordPolicy(properties), emails, properties, transactionManager);
	}

	// ---------------------------------------------------------------- forgot

	@Test
	void forgotWithExistingEmailInvalidatesPriorTokensPersistsNewOneAndEnqueuesEmail() {
		UserAccount account = googleStudent(null);
		when(users.findByEmail("student@fpt.edu.vn")).thenReturn(Optional.of(account));
		when(users.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
		when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(emails.enqueue(any())).thenReturn(sentRecord());

		service.forgotPassword("Student@FPT.edu.vn");

		verify(tokens).invalidateUnusedForUser(eq(account.getId()), any());
		ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
		verify(tokens).save(tokenCaptor.capture());
		PasswordResetToken saved = tokenCaptor.getValue();
		assertEquals(account, saved.getUser());
		assertNull(saved.getUsedAt());
		assertTrue(saved.getExpiresAt().isAfter(LocalDateTime.now()));

		ArgumentCaptor<EmailEnqueueRequest> emailCaptor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(emailCaptor.capture());
		EmailEnqueueRequest sentEmail = emailCaptor.getValue();
		assertEquals("student@fpt.edu.vn", sentEmail.recipientEmail());
		assertEquals("PASSWORD_RESET", sentEmail.emailType());
		String rawTokenInLink = extractToken((String) sentEmail.payload().get("textBody"));
		assertEquals(saved.getTokenHash(), EvidenceHasher.sha256(rawTokenInLink));
		// Raw token itself must never equal the persisted hash (i.e. it really was hashed, not stored raw).
		assertNotEquals(rawTokenInLink, saved.getTokenHash());
	}

	@Test
	void forgotWithNonexistentEmailCreatesNoTokenAndSendsNoEmail() {
		when(users.findByEmail("ghost@fpt.edu.vn")).thenReturn(Optional.empty());

		service.forgotPassword("ghost@fpt.edu.vn");

		verify(tokens, never()).invalidateUnusedForUser(any(), any());
		verify(tokens, never()).save(any());
		verifyNoInteractions(emails);
	}

	@Test
	void forgotNormalizesEmailSameAsLogin() {
		UserAccount account = googleStudent(null);
		when(users.findByEmail("mixedcase@fpt.edu.vn")).thenReturn(Optional.of(account));
		when(users.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
		when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(emails.enqueue(any())).thenReturn(sentRecord());

		service.forgotPassword("  MixedCase@FPT.edu.vn  ");

		verify(users).findByEmail("mixedcase@fpt.edu.vn");
	}

	@Test
	void forgotOnGoogleAccountWithoutLocalPasswordStillIssuesToken() {
		UserAccount account = googleStudent(null); // passwordHash == null (Google-only so far)
		when(users.findByEmail(account.getEmail())).thenReturn(Optional.of(account));
		when(users.findByIdForUpdate(account.getId())).thenReturn(Optional.of(account));
		when(tokens.save(any())).thenAnswer(inv -> inv.getArgument(0));
		when(emails.enqueue(any())).thenReturn(sentRecord());

		service.forgotPassword(account.getEmail());

		verify(tokens).save(any());
		verify(emails).enqueue(any());
	}

	// ----------------------------------------------------------------- reset

	@Test
	void resetWithValidTokenUpdatesPasswordAndConsumesToken() {
		UserAccount account = googleStudent(null);
		PasswordResetToken token = validToken(account);
		when(tokens.findByTokenHashForUpdate(token.getTokenHash())).thenReturn(Optional.of(token));

		service.resetPassword(rawTokenFor(token), "brand-new-pass1");

		assertTrue(passwordEncoder.matches("brand-new-pass1", account.getPasswordHash()));
		assertNotEquals(null, token.getUsedAt());
		verify(users).save(account);
		verify(tokens).save(token);
	}

	@Test
	void resetEstablishesFirstLocalPasswordForGoogleAccountWithoutBreakingGoogleLink() {
		UserAccount account = googleStudent(null);
		String googleSubjectBefore = account.getGoogleSubject();
		PasswordResetToken token = validToken(account);
		when(tokens.findByTokenHashForUpdate(token.getTokenHash())).thenReturn(Optional.of(token));

		service.resetPassword(rawTokenFor(token), "brand-new-pass1");

		assertTrue(passwordEncoder.matches("brand-new-pass1", account.getPasswordHash()));
		assertEquals(googleSubjectBefore, account.getGoogleSubject());
	}

	@Test
	void replayingAConsumedTokenFails() {
		UserAccount account = googleStudent(null);
		PasswordResetToken token = validToken(account);
		token.setUsedAt(LocalDateTime.now().minusMinutes(1));
		when(tokens.findByTokenHashForUpdate(token.getTokenHash())).thenReturn(Optional.of(token));

		AuthException ex = assertThrows(
				AuthException.class, () -> service.resetPassword(rawTokenFor(token), "brand-new-pass1"));
		assertEquals(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void expiredTokenFails() {
		UserAccount account = googleStudent(null);
		PasswordResetToken token = validToken(account);
		token.setExpiresAt(LocalDateTime.now().minusMinutes(1));
		when(tokens.findByTokenHashForUpdate(token.getTokenHash())).thenReturn(Optional.of(token));

		AuthException ex = assertThrows(
				AuthException.class, () -> service.resetPassword(rawTokenFor(token), "brand-new-pass1"));
		assertEquals(AuthErrorCode.PASSWORD_RESET_TOKEN_EXPIRED, ex.getCode());
		verify(users, never()).save(any());
	}

	@Test
	void unknownOrMalformedTokenFails() {
		when(tokens.findByTokenHashForUpdate(any())).thenReturn(Optional.empty());

		AuthException ex = assertThrows(
				AuthException.class, () -> service.resetPassword("totally-random-not-a-real-token", "brand-new-pass1"));
		assertEquals(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, ex.getCode());
	}

	@Test
	void olderTokenAfterResendIsInvalidBecauseForgotAlreadyMarkedItUsed() {
		// forgotPassword() calls invalidateUnusedForUser before saving the new token; simulate the
		// effect here directly on the older token instance to prove reset rejects it afterward.
		UserAccount account = googleStudent(null);
		PasswordResetToken older = validToken(account);
		older.setUsedAt(LocalDateTime.now()); // as invalidateUnusedForUser would have set it
		when(tokens.findByTokenHashForUpdate(older.getTokenHash())).thenReturn(Optional.of(older));

		AuthException ex = assertThrows(
				AuthException.class, () -> service.resetPassword(rawTokenFor(older), "brand-new-pass1"));
		assertEquals(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID, ex.getCode());
	}

	@Test
	void resetReusesExistingPasswordPolicy() {
		UserAccount account = googleStudent(null);
		PasswordResetToken token = validToken(account);
		when(tokens.findByTokenHashForUpdate(token.getTokenHash())).thenReturn(Optional.of(token));

		AuthException ex =
				assertThrows(AuthException.class, () -> service.resetPassword(rawTokenFor(token), "short"));
		assertEquals(AuthErrorCode.PASSWORD_POLICY_VIOLATION, ex.getCode());
		verify(users, never()).save(any());
	}

	// --------------------------------------------------------------- helpers

	private String lastRawToken;

	/** Builds a token entity whose hash matches a freshly generated raw token we can hand to reset(). */
	private PasswordResetToken validToken(UserAccount account) {
		lastRawToken = com.saga.be.integration.oauth.Pkce.newVerifier();
		PasswordResetToken token = new PasswordResetToken();
		token.setId(UUID.randomUUID());
		token.setUser(account);
		token.setTokenHash(EvidenceHasher.sha256(lastRawToken));
		token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
		return token;
	}

	private String rawTokenFor(PasswordResetToken token) {
		if (!EvidenceHasher.sha256(lastRawToken).equals(token.getTokenHash())) {
			throw new IllegalStateException("test helper misuse: token hash does not match lastRawToken");
		}
		return lastRawToken;
	}

	private static String extractToken(String textBody) {
		int idx = textBody.indexOf("token=");
		assertTrue(idx > 0, "reset email body must contain the reset link with a token query param");
		String tail = textBody.substring(idx + "token=".length());
		int end = 0;
		while (end < tail.length() && !Character.isWhitespace(tail.charAt(end))) {
			end++;
		}
		try {
			return java.net.URLDecoder.decode(tail.substring(0, end), java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static UserAccount googleStudent(String passwordHash) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("student@fpt.edu.vn");
		account.setFullName("Student One");
		account.setGoogleSubject("google-sub-student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash(passwordHash);
		return account;
	}

	private static EmailOutboxRecord sentRecord() {
		return new EmailOutboxRecord(
				UUID.randomUUID(),
				"student@fpt.edu.vn",
				"PASSWORD_RESET",
				"password-reset",
				EmailDeliveryStatus.PENDING,
				0,
				LocalDateTime.now(),
				null,
				null,
				null,
				LocalDateTime.now(),
				LocalDateTime.now());
	}
}

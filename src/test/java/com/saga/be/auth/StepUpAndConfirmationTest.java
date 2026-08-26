package com.saga.be.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.exception.AuthException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.service.confirmation.EvidenceHasher;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class StepUpAndConfirmationTest {

	@Test
	void confirmationRequiresFreshStepUp() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setPasswordHash("hashed");
		PasswordEncoder encoder = new PasswordEncoder() {
			@Override
			public String encode(CharSequence rawPassword) {
				return "hashed";
			}

			@Override
			public boolean matches(CharSequence rawPassword, String encodedPassword) {
				return "correct-password".contentEquals(rawPassword);
			}
		};
		Instant now = Instant.parse("2026-08-25T00:00:00Z");
		StepUpAuthenticationService stepUp = new StepUpAuthenticationService(
				id -> Optional.of(account), encoder, Duration.ofMinutes(10), 5, () -> now);
		assertThrows(IntegrationException.class, () -> stepUp.requireFresh(null));
		assertThrows(AuthException.class, () -> stepUp.reauthPassword(account.getId(), "wrong"));
		Instant stamped = stepUp.reauthPassword(account.getId(), "correct-password");
		assertDoesNotThrow(() -> stepUp.requireFresh(stamped));
		StepUpAuthenticationService later = new StepUpAuthenticationService(
				id -> Optional.of(account), encoder, Duration.ofMinutes(10), 5, () -> now.plus(Duration.ofMinutes(11)));
		assertThrows(IntegrationException.class, () -> later.requireFresh(stamped));
	}

	@Test
	void webauthnAssertionSatisfiesStepUpWhenEnabled() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		StepUpAuthenticationService stepUp = new StepUpAuthenticationService(
				id -> Optional.of(account),
				new PasswordEncoder() {
					@Override
					public String encode(CharSequence rawPassword) {
						return "x";
					}

					@Override
					public boolean matches(CharSequence rawPassword, String encodedPassword) {
						return false;
					}
				},
				Duration.ofMinutes(10),
				5);
		Instant stamped = stepUp.markWebAuthn(account.getId());
		assertDoesNotThrow(() -> stepUp.requireFresh(stamped));
	}

	@Test
	void evidenceHashIsStableAndHistoryPreserving() {
		String first = EvidenceHasher.canonical("SAGA-1", List.of("aaa"), List.of("1"), "DONE");
		String second = EvidenceHasher.canonical("SAGA-1", List.of("aaa"), List.of("1"), "DONE");
		assertEqualsHash(first, second);
		assertTrue(!EvidenceHasher.sha256(first).equals(EvidenceHasher.sha256(EvidenceHasher.canonical("SAGA-1", List.of("bbb"), List.of("1"), "DONE"))));
	}

	@Test
	void reauthRateLimit() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setPasswordHash("hashed");
		StepUpAuthenticationService stepUp = new StepUpAuthenticationService(
				id -> Optional.of(account),
				new PasswordEncoder() {
					@Override
					public String encode(CharSequence rawPassword) {
						return "hashed";
					}

					@Override
					public boolean matches(CharSequence rawPassword, String encodedPassword) {
						return false;
					}
				},
				Duration.ofMinutes(10),
				2);
		assertThrows(AuthException.class, () -> stepUp.reauthPassword(account.getId(), "x"));
		assertThrows(AuthException.class, () -> stepUp.reauthPassword(account.getId(), "x"));
		assertThrows(IntegrationException.class, () -> stepUp.reauthPassword(account.getId(), "x"));
	}

	private static void assertEqualsHash(String left, String right) {
		if (!EvidenceHasher.sha256(left).equals(EvidenceHasher.sha256(right))) {
			throw new AssertionError("hashes differ");
		}
	}
}

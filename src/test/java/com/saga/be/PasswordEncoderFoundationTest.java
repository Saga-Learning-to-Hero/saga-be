package com.saga.be;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(properties = "spring.profiles.active=test")
@ActiveProfiles("test")
class PasswordEncoderFoundationTest {

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Test
	void argon2idEncodesAndMatchesWithoutStoringPlaintext() {
		String encoded = passwordEncoder.encode("foundation-check-only");
		assertTrue(encoded.startsWith("$argon2id$"), encoded);
		assertTrue(encoded.contains("m=19456"));
		assertTrue(encoded.contains("t=2"));
		assertTrue(encoded.contains("p=1"));
		assertTrue(passwordEncoder.matches("foundation-check-only", encoded));
		assertTrue(!encoded.contains("foundation-check-only"));
	}
}

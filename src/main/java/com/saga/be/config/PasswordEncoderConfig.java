package com.saga.be.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Auth V1 password encoder. Argon2id parameters follow current OWASP guidance:
 * ~19 MiB memory, 2 iterations, parallelism 1, salt 16 bytes, hash 32 bytes.
 */
@Configuration
public class PasswordEncoderConfig {

	private static final int SALT_LENGTH = 16;
	private static final int HASH_LENGTH = 32;
	private static final int PARALLELISM = 1;
	private static final int MEMORY_KIB = 19_456;
	private static final int ITERATIONS = 2;

	@Bean
	public PasswordEncoder passwordEncoder() {
		return new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM, MEMORY_KIB, ITERATIONS);
	}
}

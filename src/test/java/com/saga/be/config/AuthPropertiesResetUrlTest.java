package com.saga.be.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AuthPropertiesResetUrlTest {

	@Test
	void withoutAnExplicitUrlTheResetLinkFollowsTheFrontendDomain() {
		AuthProperties auth = new AuthProperties();
		auth.setFrontendOrigins(List.of("https://saga-fe.vercel.app/", "http://localhost:3000"));

		assertThat(auth.resolvedPasswordResetUrl()).isEqualTo("https://saga-fe.vercel.app/reset-password");

		auth.setPasswordResetUrl("   ");
		assertThat(auth.resolvedPasswordResetUrl()).isEqualTo("https://saga-fe.vercel.app/reset-password");
	}

	@Test
	void anExplicitAbsoluteUrlWins() {
		AuthProperties auth = new AuthProperties();
		auth.setFrontendOrigins(List.of("https://saga-fe.vercel.app"));
		auth.setPasswordResetUrl(" https://saga.example.edu.vn/auth/reset ");

		assertThat(auth.resolvedPasswordResetUrl()).isEqualTo("https://saga.example.edu.vn/auth/reset");
	}

	@Test
	void aRelativeOrMissingConfigFallsBackSafely() {
		AuthProperties auth = new AuthProperties();
		auth.setPasswordResetUrl("/reset-password");
		auth.setFrontendOrigins(List.of(" ", ""));

		assertThat(auth.resolvedPasswordResetUrl()).isEqualTo("http://localhost:3000/reset-password");

		auth.setFrontendOrigins(null);
		assertThat(auth.resolvedPasswordResetUrl()).isEqualTo("http://localhost:3000/reset-password");
	}
}

package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.AuthenticatedPrincipal;
import org.springframework.security.core.userdetails.UserDetails;

class SessionRevocationFeasibilityTest {

	@Test
	void runtimeSessionRepositoryIsUnindexedAndPrincipalIsNotALookupKey() throws Exception {
		String application = Files.readString(Path.of("src/main/resources/application.properties"));
		assertTrue(application.contains("spring.session.redis.namespace=saga:session"));
		assertFalse(application.contains("spring.session.redis.repository-type=indexed"));

		String timing = Files.readString(Path.of("src/main/java/com/saga/be/config/SessionRepositoryTimingConfiguration.java"));
		assertTrue(timing.contains("implements SessionRepository<S>"));
		assertFalse(timing.contains("FindByIndexNameSessionRepository"));

		assertFalse(UserDetails.class.isAssignableFrom(SagaUserPrincipal.class));
		assertFalse(AuthenticatedPrincipal.class.isAssignableFrom(SagaUserPrincipal.class));
		assertFalse(java.security.Principal.class.isAssignableFrom(SagaUserPrincipal.class));

		String reset = Files.readString(Path.of("src/main/java/com/saga/be/auth/PasswordResetService.java"));
		assertFalse(reset.contains("SessionRepository"));
		assertFalse(reset.contains("FindByIndexName"));
		assertFalse(reset.contains("deleteById"));

		String guard = Files.readString(Path.of("src/main/java/com/saga/be/auth/AccountStatusGuard.java"));
		assertTrue(guard.contains("requireActive"));
		String security = Files.readString(Path.of("src/main/java/com/saga/be/security/SecurityConfig.java"));
		assertFalse(security.contains("AccountStatusGuard"));
	}
}

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
	}

	@Test
	void requestTimeGuardUsesPrimaryKeyAndDoesNotIndexOrScanSessions() throws Exception {
		String filter = Files.readString(Path.of("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java"));
		assertTrue(filter.contains("principal.getUserId()"));
		assertTrue(filter.contains("users.findById("));
		assertFalse(filter.contains("findByEmail"));
		assertFalse(filter.contains("findByUsername"));
		assertFalse(filter.contains("toString()"));
		assertFalse(filter.contains("SessionRepository"));
		assertFalse(filter.contains("FindByIndexName"));
		assertFalse(filter.contains(".keys("));
		assertFalse(filter.contains("scan("));

		String security = Files.readString(Path.of("src/main/java/com/saga/be/security/SecurityConfig.java"));
		assertTrue(security.contains("AccountStatusEnforcementFilter"));
		assertTrue(security.contains("AccountStatusGuard"));
		assertFalse(security.contains("FindByIndexNameSessionRepository"));

		String command = Files.readString(Path.of("src/main/java/com/saga/be/service/admin/AdminUserCommandService.java"));
		assertTrue(command.contains("findByIdForUpdate"));
		assertFalse(command.contains("SessionRepository"));
		assertFalse(command.contains("FindByIndexName"));
		assertFalse(command.contains("enrollment"));
		assertFalse(command.contains("TeamMembership"));
	}

	@Test
	void alreadyOpenSseConnectionsAreNotClosedOnDisable() throws Exception {
		String hub = Files.readString(Path.of("src/main/java/com/saga/be/realtime/ProjectSseHub.java"));
		assertFalse(hub.contains("userId"));
		assertFalse(hub.contains("AccountStatus"));
		assertFalse(hub.contains("SessionRepository"));
		assertFalse(hub.contains("Redis"));
		String filter = Files.readString(Path.of("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java"));
		assertFalse(filter.contains("SseEmitter"));
		assertFalse(filter.contains("ProjectSseHub"));
		assertTrue(filter.contains("/api/auth/logout"));
	}
}

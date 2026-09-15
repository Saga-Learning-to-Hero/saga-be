package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

class SessionRevocationFeasibilityTest {

	@Test
	void indexedSessionsUseStableUserIdPrincipalAndNoScan() throws Exception {
		String application = Files.readString(Path.of("src/main/resources/application.properties"));
		assertTrue(application.contains("spring.session.data.redis.namespace=spring:session"));
		assertTrue(application.contains("spring.session.data.redis.repository-type=indexed"));
		assertTrue(application.contains("spring.session.data.redis.configure-action=none"));
		assertFalse(application.contains("spring.session.data.redis.namespace=saga:session"));
		assertFalse(application.contains("spring.session.redis.namespace="));
		assertFalse(application.contains("spring.session.redis.repository-type="));

		String timing = Files.readString(Path.of("src/main/java/com/saga/be/config/SessionRepositoryTimingConfiguration.java"));
		assertTrue(timing.contains("implements FindByIndexNameSessionRepository<S>"));
		assertTrue(timing.contains("class TimingSessionRepository"));
		assertTrue(timing.contains("setProxyTargetClass(true)"));
		assertFalse(timing.contains(".keys("));
		assertFalse(timing.contains("scan("));

		assertFalse(UserDetails.class.isAssignableFrom(SagaUserPrincipal.class));
		assertTrue(java.security.Principal.class.isAssignableFrom(SagaUserPrincipal.class));

		String principal = Files.readString(Path.of("src/main/java/com/saga/be/security/SagaUserPrincipal.java"));
		assertTrue(principal.contains("implements Principal"));
		assertTrue(principal.contains("return userId == null ? \"\" : userId.toString();"));

		String revocation = Files.readString(Path.of("src/main/java/com/saga/be/security/IndexedSessionRevocationService.java"));
		assertTrue(revocation.contains("findByPrincipalName"));
		assertTrue(revocation.contains("deleteById"));
		assertFalse(revocation.contains(".keys("));
		assertFalse(revocation.contains("scan("));
		assertFalse(revocation.contains("session.getId()"));
	}

	@Test
	void requestTimeGuardRemainsAndDoesNotScanSessions() throws Exception {
		String filter = Files.readString(Path.of("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java"));
		assertTrue(filter.contains("principal.getUserId()"));
		assertTrue(filter.contains("users.findById("));
		assertFalse(filter.contains("findByEmail"));
		assertFalse(filter.contains("SessionRepository"));
		assertTrue(filter.contains("/api/auth/logout"));

		String command = Files.readString(Path.of("src/main/java/com/saga/be/service/admin/AdminUserCommandService.java"));
		assertTrue(command.contains("findByIdForUpdate"));
		assertTrue(command.contains("AccountDisabledEvent"));
		assertFalse(command.contains("deleteById"));

		String listener = Files.readString(Path.of("src/main/java/com/saga/be/security/AccountDisabledAfterCommitListener.java"));
		assertTrue(listener.contains("AFTER_COMMIT"));
		assertTrue(listener.contains("revokeAllForUser"));
		assertTrue(listener.contains("notifyDisabled"));
		assertTrue(listener.contains("closeForUser"));
	}

	@Test
	void userSseIsTheKickChannelAndProjectHubCanCloseByUser() throws Exception {
		String userHub = Files.readString(Path.of("src/main/java/com/saga/be/realtime/UserSseHub.java"));
		assertTrue(userHub.contains("ACCOUNT_DISABLED"));
		assertTrue(userHub.contains("process-local"));
		String controller = Files.readString(Path.of("src/main/java/com/saga/be/controller/UserRealtimeController.java"));
		assertTrue(controller.contains("/api/users/me/events"));
		assertFalse(controller.contains("@PathVariable"));
		String projectHub = Files.readString(Path.of("src/main/java/com/saga/be/realtime/ProjectSseHub.java"));
		assertTrue(projectHub.contains("closeForUser"));
		assertTrue(projectHub.contains("process-local"));
		String auth = Files.readString(Path.of("src/main/java/com/saga/be/controller/AuthController.java"));
		assertTrue(auth.contains("authenticated=false"));
		assertTrue(auth.contains("SAGA_SESSION"));
		String filter = Files.readString(Path.of("src/main/java/com/saga/be/security/AccountStatusEnforcementFilter.java"));
		assertTrue(filter.contains("unauthenticated"));
		assertTrue(filter.contains("ACCOUNT_DISABLED"));
	}
}

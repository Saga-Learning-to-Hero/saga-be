package com.saga.be.integration.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.exception.IntegrationException;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OAuthStateServiceTest {

	@Test
	void validStateIsAcceptedOnce() {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started = service.start(user, OAuthFlowType.GITHUB_USER_LINK, "/me", null, null, Pkce.newVerifier());
		OAuthState consumed = service.consumeForUser(started.state(), user);
		assertEquals(user, consumed.sagaUserId());
		assertTrue(consumed.pkceVerifier() != null && !consumed.pkceVerifier().isBlank());
	}

	@Test
	void nullFrontendReturnPathIsStored() {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started =
				service.start(user, OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, null, UUID.randomUUID(), UUID.randomUUID(), "v");
		assertEquals(null, service.consumeForUser(started.state(), user).frontendReturnPath());
	}

	@Test
	void invalidStateRejected() {
		OAuthStateService service = new OAuthStateService(new InMemoryOAuthStateStore(), Duration.ofMinutes(10));
		assertThrows(IntegrationException.class, () -> service.consumeForUser("nope", UUID.randomUUID()));
	}

	@Test
	void expiredStateRejected() throws InterruptedException {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService shortTtl = new OAuthStateService(store, Duration.ofMillis(1));
		UUID user = UUID.randomUUID();
		OAuthState started = shortTtl.start(user, OAuthFlowType.JIRA_USER_LINK, "/", null, null, "verifier");
		Thread.sleep(20);
		assertThrows(IntegrationException.class, () -> shortTtl.consumeForUser(started.state(), user));
	}

	@Test
	void replayedStateRejected() {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started = service.start(user, OAuthFlowType.GITHUB_USER_LINK, "/", null, null, "v");
		service.consumeForUser(started.state(), user);
		assertThrows(IntegrationException.class, () -> service.consumeForUser(started.state(), user));
	}

	@Test
	void stateFromDifferentUserRejected() {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started = service.start(user, OAuthFlowType.GITHUB_USER_LINK, "/", null, null, "v");
		assertThrows(IntegrationException.class, () -> service.consumeForUser(started.state(), UUID.randomUUID()));
	}

	@Test
	void pkceVerifierIsBoundToState() {
		String verifier = Pkce.newVerifier();
		String challenge = Pkce.challengeS256(verifier);
		assertEquals(43, challenge.length(), "S256 challenge is base64url without padding");
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started = service.start(user, OAuthFlowType.GITHUB_USER_LINK, "/", null, null, verifier);
		assertEquals(verifier, service.consumeForUser(started.state(), user).pkceVerifier());
	}

	@Test
	void wrongFlowTypeIsRejected() {
		InMemoryOAuthStateStore store = new InMemoryOAuthStateStore();
		OAuthStateService service = new OAuthStateService(store, Duration.ofMinutes(10));
		UUID user = UUID.randomUUID();
		OAuthState started = service.start(user, OAuthFlowType.JIRA_TEAM_CONNECT, "/", null, null, "v");
		assertThrows(
				IntegrationException.class,
				() -> service.consumeForUser(started.state(), user, OAuthFlowType.JIRA_USER_LINK));
	}
}

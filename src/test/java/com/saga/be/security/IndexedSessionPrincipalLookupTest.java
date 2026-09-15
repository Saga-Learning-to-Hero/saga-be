package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.config.SessionRepositoryTimingConfiguration.IndexedTimingSessionRepository;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;

class IndexedSessionPrincipalLookupTest {

	@Test
	void localAndGoogleSessionsIndexTheSameUserIdAndLeaveOthersAlone() {
		PrincipalIndexedMapSessionRepository sessions = new PrincipalIndexedMapSessionRepository();
		UserAccount local = account(AccountRole.STUDENT, null, "hash");
		UserAccount google = account(AccountRole.STUDENT, "google-sub", null);
		google.setId(local.getId());
		UserAccount other = account(AccountRole.LECTURER, null, "hash");

		save(sessions, SagaAuthentications.authenticated(local));
		save(sessions, SagaAuthentications.authenticated(google));
		MapSession otherSession = save(sessions, SagaAuthentications.authenticated(other));

		assertEquals(local.getId().toString(), SagaAuthentications.authenticated(local).getName());
		assertEquals(local.getId().toString(), SagaAuthentications.authenticated(google).getName());
		assertEquals(2, sessions.findByPrincipalName(local.getId().toString()).size());
		assertEquals(1, sessions.findByPrincipalName(other.getId().toString()).size());

		IndexedSessionRevocationService revocation = revocation(sessions);
		assertEquals(2, revocation.revokeAllForUser(local.getId()));
		assertTrue(sessions.findByPrincipalName(local.getId().toString()).isEmpty());
		assertEquals(otherSession.getId(), sessions.findById(otherSession.getId()).getId());
		assertEquals(0, revocation.revokeAllForUser(local.getId()));
	}

	@Test
	void unindexedSessionIsNotDiscoverableUntilSavedWithPrincipal() {
		PrincipalIndexedMapSessionRepository sessions = new PrincipalIndexedMapSessionRepository();
		UserAccount account = account(AccountRole.STUDENT, null, "hash");
		MapSession orphan = sessions.createSession();
		sessions.save(orphan);
		assertTrue(sessions.findByPrincipalName(account.getId().toString()).isEmpty());
		assertEquals(orphan.getId(), sessions.findById(orphan.getId()).getId());

		org.springframework.security.core.context.SecurityContext context =
				org.springframework.security.core.context.SecurityContextHolder.createEmptyContext();
		context.setAuthentication(SagaAuthentications.authenticated(account));
		orphan.setAttribute("SPRING_SECURITY_CONTEXT", context);
		sessions.save(orphan);
		assertEquals(1, sessions.findByPrincipalName(account.getId().toString()).size());
		assertEquals(0, revocationUnavailable().revokeAllForUser(account.getId()));
	}

	@Test
	void revokedSessionCannotBeLoadedById() {
		PrincipalIndexedMapSessionRepository sessions = new PrincipalIndexedMapSessionRepository();
		UserAccount account = account(AccountRole.STUDENT, null, "hash");
		MapSession session = save(sessions, SagaAuthentications.authenticated(account));
		assertEquals(1, revocation(sessions).revokeAllForUser(account.getId()));
		assertNull(sessions.findById(session.getId()));
	}

	@Test
	void timingWrapperPreservesIndexedLookup() {
		PrincipalIndexedMapSessionRepository inner = new PrincipalIndexedMapSessionRepository();
		IndexedTimingSessionRepository<MapSession> wrapped = new IndexedTimingSessionRepository<>(inner);
		UserAccount account = account(AccountRole.LECTURER, null, "hash");
		save(wrapped, SagaAuthentications.authenticated(account));
		assertEquals(1, wrapped.findByPrincipalName(account.getId().toString()).size());
		assertTrue(wrapped instanceof FindByIndexNameSessionRepository<?>);
	}

	@Test
	void targetClassProxyPreservesIndexedLookup() {
		PrincipalIndexedMapSessionRepository inner = new PrincipalIndexedMapSessionRepository();
		org.springframework.aop.framework.ProxyFactory factory = new org.springframework.aop.framework.ProxyFactory(inner);
		factory.setProxyTargetClass(true);
		factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
		@SuppressWarnings("unchecked")
		FindByIndexNameSessionRepository<MapSession> proxy =
				(FindByIndexNameSessionRepository<MapSession>) factory.getProxy();
		UserAccount account = account(AccountRole.STUDENT, null, "hash");
		save(proxy, SagaAuthentications.authenticated(account));
		assertEquals(1, proxy.findByPrincipalName(account.getId().toString()).size());
		assertTrue(proxy instanceof FindByIndexNameSessionRepository<?>);
	}

	private static IndexedSessionRevocationService revocation(FindByIndexNameSessionRepository<MapSession> sessions) {
		@SuppressWarnings("unchecked")
		ObjectProvider<FindByIndexNameSessionRepository<? extends org.springframework.session.Session>> provider =
				Mockito.mock(ObjectProvider.class);
		Mockito.doReturn(sessions).when(provider).getIfAvailable();
		return new IndexedSessionRevocationService(provider);
	}

	private static IndexedSessionRevocationService revocationUnavailable() {
		@SuppressWarnings("unchecked")
		ObjectProvider<FindByIndexNameSessionRepository<? extends org.springframework.session.Session>> provider =
				Mockito.mock(ObjectProvider.class);
		Mockito.when(provider.getIfAvailable()).thenReturn(null);
		return new IndexedSessionRevocationService(provider);
	}

	private static MapSession save(FindByIndexNameSessionRepository<MapSession> sessions, Authentication authentication) {
		MapSession session = sessions.createSession();
		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(authentication);
		session.setAttribute("SPRING_SECURITY_CONTEXT", context);
		sessions.save(session);
		return session;
	}

	private static UserAccount account(AccountRole role, String googleSubject, String hash) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(account.getId() + "@fpt.edu.vn");
		account.setUsername("user");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setGoogleSubject(googleSubject);
		account.setPasswordHash(hash);
		return account;
	}
}

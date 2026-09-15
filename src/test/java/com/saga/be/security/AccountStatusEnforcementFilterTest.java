package com.saga.be.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.auth.AccountStatusGuard;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.util.ServletRequestPathUtils;

@ExtendWith(MockitoExtension.class)
class AccountStatusEnforcementFilterTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private FilterChain chain;

	private final AccountStatusGuard guard = new AccountStatusGuard();

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void inactiveAuthenticatedRequestIsForbidden() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(account));
		when(users.findById(account.getId())).thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request("GET", "/api/student/anything"), response, chain);

		assertEquals(403, response.getStatus());
		assertTrue(response.getContentAsString().contains("ACCOUNT_DISABLED"));
		verify(chain, never()).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
		verify(users).findById(account.getId());
	}

	@Test
	void logoutAndMeAndForgotRemainAllowedWhileInactive() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(account));
		AccountStatusEnforcementFilter filter = filter();

		assertProceeds(filter, request("GET", "/api/auth/me"));
		assertProceeds(filter, request("POST", "/api/auth/logout"));
		assertProceeds(filter, request("POST", "/api/auth/password/forgot"));
		assertProceeds(filter, request("POST", "/api/auth/password/reset"));
		assertProceeds(filter, request("GET", "/api/auth/csrf"));
		assertProceeds(filter, request("POST", "/api/webhooks/github"));
		assertProceeds(filter, request("GET", "/actuator/health"));
		verify(users, never()).findById(account.getId());
	}

	@Test
	void sseReconnectIsDeniedWhileInactive() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(account));
		when(users.findById(account.getId())).thenReturn(Optional.of(account));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter().doFilter(request("GET", "/api/projects/" + UUID.randomUUID() + "/events"), response, chain);

		assertEquals(403, response.getStatus());
		assertTrue(response.getContentAsString().contains("ACCOUNT_DISABLED"));
	}

	@Test
	void missingUserAccountRepositorySkipsGuard() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		SecurityContextHolder.getContext().setAuthentication(SagaAuthentications.authenticated(account));
		AccountStatusEnforcementFilter filter = new AccountStatusEnforcementFilter(null, guard);
		assertProceeds(filter, request("GET", "/api/student/anything"));
	}

	@Test
	void anonymousRequestIsNotLookedUp() throws Exception {
		assertProceeds(filter(), request("GET", "/api/student/anything"));
		verify(users, never()).findById(org.mockito.ArgumentMatchers.any());
	}

	private void assertProceeds(AccountStatusEnforcementFilter filter, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		assertEquals(HttpServletResponse.SC_OK, response.getStatus());
		verify(chain).doFilter(request, response);
		org.mockito.Mockito.clearInvocations(chain);
	}

	private AccountStatusEnforcementFilter filter() {
		return new AccountStatusEnforcementFilter(users, guard);
	}

	private static MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setServletPath(path);
		ServletRequestPathUtils.parseAndCache(request);
		return request;
	}

	private static UserAccount student(AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		account.setEmail("ada@fpt.edu.vn");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(status);
		account.setPasswordHash("hash");
		return account;
	}
}

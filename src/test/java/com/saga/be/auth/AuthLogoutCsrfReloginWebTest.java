package com.saga.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
import jakarta.servlet.http.Cookie;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Cookie-jar auth lifecycle matching Swagger UI:
 * GET csrf → POST login → POST logout → immediate POST login.
 * Springdoc reads {@code document.cookie} on every write; logout must leave a usable XSRF-TOKEN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLogoutCsrfReloginWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LocalAuthService localAuthService;

	@BeforeEach
	void resetAuthService() {
		reset(localAuthService);
		when(localAuthService.authenticate("admin", "admin123")).thenReturn(SagaAuthentications.authenticated(admin()));
	}

	@Test
	void logoutRotatesXsrfSoImmediateLoginSucceedsWithoutExtraCsrfGet() throws Exception {
		Jar jar = bootstrapLoggedIn();
		String preLogoutToken = jar.xsrf.getValue();

		MvcResult logout = mockMvc.perform(post("/api/auth/logout")
						.session(jar.session)
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", preLogoutToken))
				.andExpect(status().isNoContent())
				.andReturn();
		assertThat(jar.session.isInvalid()).isTrue();
		jar.applySetCookies(logout);

		assertThat(jar.xsrf).isNotNull();
		assertThat(jar.xsrf.getMaxAge()).isNotZero();
		assertThat(jar.xsrf.getValue()).isNotBlank().isNotEqualTo(preLogoutToken);

		MockHttpSession anonymousSession = (MockHttpSession) logout.getRequest().getSession(false);
		MockHttpServletRequestBuilder login = post("/api/auth/login")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
				.cookie(jar.cookieArray())
				.header("X-XSRF-TOKEN", jar.xsrf.getValue());
		if (anonymousSession != null && !anonymousSession.isInvalid()) {
			login.session(anonymousSession);
		}
		mockMvc.perform(login)
				.andExpect(status().isOk())
				.andExpect(authenticated())
				.andExpect(jsonPath("$.authenticated").value(true));
	}

	@Test
	void logoutThenReusePreLogoutCsrfTokenIsForbidden() throws Exception {
		Jar jar = bootstrapLoggedIn();
		String staleToken = jar.xsrf.getValue();

		MvcResult logout = mockMvc.perform(post("/api/auth/logout")
						.session(jar.session)
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", staleToken))
				.andExpect(status().isNoContent())
				.andReturn();
		jar.applySetCookies(logout);

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", staleToken))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void logoutThenFreshCsrfGetThenLoginSucceeds() throws Exception {
		Jar jar = bootstrapLoggedIn();

		MvcResult logout = mockMvc.perform(post("/api/auth/logout")
						.session(jar.session)
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", jar.xsrf.getValue()))
				.andExpect(status().isNoContent())
				.andReturn();
		jar.applySetCookies(logout);

		MvcResult csrf = mockMvc.perform(withCookies(get("/api/auth/csrf"), jar))
				.andExpect(status().isOk())
				.andReturn();
		jar.applySetCookies(csrf);
		jar.session = (MockHttpSession) csrf.getRequest().getSession();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		assertThat(token).isEqualTo(jar.xsrf.getValue());

		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", token)
						.session(jar.session))
				.andExpect(status().isOk())
				.andExpect(authenticated())
				.andExpect(jsonPath("$.authenticated").value(true));
	}

	@Test
	void meAfterLogoutWithoutReloginIsUnauthenticatedOkNot401() throws Exception {
		Jar jar = bootstrapLoggedIn();
		MvcResult logout = mockMvc.perform(post("/api/auth/logout")
						.session(jar.session)
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", jar.xsrf.getValue()))
				.andExpect(status().isNoContent())
				.andReturn();
		jar.applySetCookies(logout);

		mockMvc.perform(withCookies(get("/api/auth/me"), jar))
				.andExpect(status().isOk())
				.andExpect(unauthenticated())
				.andExpect(jsonPath("$.authenticated").value(false));
	}

	@Test
	void loginWithoutAnyCsrfHeaderIsForbiddenMissingTokenShape() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	private Jar bootstrapLoggedIn() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Jar jar = new Jar();
		jar.applySetCookies(csrf);
		jar.session = (MockHttpSession) csrf.getRequest().getSession();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");

		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(jar.cookieArray())
						.header("X-XSRF-TOKEN", token)
						.session(jar.session))
				.andExpect(status().isOk())
				.andReturn();
		jar.session = (MockHttpSession) login.getRequest().getSession();
		jar.applySetCookies(login);
		if (jar.xsrf == null) {
			jar.xsrf = new Cookie("XSRF-TOKEN", token);
		}
		mockMvc.perform(get("/api/auth/me").session(jar.session).cookie(jar.cookieArray()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true));
		return jar;
	}

	private static MockHttpServletRequestBuilder withCookies(MockHttpServletRequestBuilder builder, Jar jar) {
		Cookie[] cookies = jar.cookieArray();
		if (cookies.length > 0) {
			builder.cookie(cookies);
		}
		return builder;
	}

	private static UserAccount admin() {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("admin@saga.local");
		account.setUsername("admin");
		account.setFullName("System Admin");
		account.setAccountRole(AccountRole.ADMIN);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("not-plaintext");
		return account;
	}

	private static final class Jar {
		MockHttpSession session;
		Cookie sagaSession;
		Cookie xsrf;

		void applySetCookies(MvcResult result) {
			Cookie[] cookies = result.getResponse().getCookies();
			if (cookies == null) {
				return;
			}
			for (Cookie cookie : cookies) {
				if ("SAGA_SESSION".equals(cookie.getName())) {
					sagaSession = cookie.getMaxAge() == 0 ? null : copy(cookie);
				}
				if ("XSRF-TOKEN".equals(cookie.getName())) {
					if (cookie.getMaxAge() == 0 || cookie.getValue() == null || cookie.getValue().isBlank()) {
						xsrf = null;
					} else {
						xsrf = copy(cookie);
					}
				}
			}
		}

		Cookie[] cookieArray() {
			List<Cookie> out = new ArrayList<>(2);
			if (sagaSession != null) {
				out.add(sagaSession);
			}
			if (xsrf != null) {
				out.add(xsrf);
			}
			return out.toArray(Cookie[]::new);
		}

		private static Cookie copy(Cookie source) {
			Cookie cookie = new Cookie(source.getName(), source.getValue());
			if (source.getPath() != null) {
				cookie.setPath(source.getPath());
			}
			cookie.setHttpOnly(source.isHttpOnly());
			cookie.setSecure(source.getSecure());
			cookie.setMaxAge(source.getMaxAge());
			return cookie;
		}
	}
}

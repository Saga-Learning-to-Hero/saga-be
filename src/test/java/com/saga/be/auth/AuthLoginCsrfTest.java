package com.saga.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
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
import com.saga.be.exception.AuthException;
import com.saga.be.security.SagaAuthentications;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthLoginCsrfTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private LocalAuthService localAuthService;

	@BeforeEach
	void resetAuthService() {
		reset(localAuthService);
	}

	@Test
	void csrfEndpointIsPublic() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
				.andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		assertThat(cookie.isHttpOnly()).isFalse();
		String bodyToken = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		assertThat(bodyToken).isEqualTo(cookie.getValue());
	}

	@Test
	void loginWithoutCsrfIsDeniedAndDoesNotReachLocalAuthService() throws Exception {
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verifyNoInteractions(localAuthService);
	}

	@Test
	void loginWithCsrfReachesLocalAuthServiceAndEstablishesSession() throws Exception {
		when(localAuthService.authenticate("admin", "admin123")).thenReturn(SagaAuthentications.authenticated(admin()));

		Csrf csrf = fetchCsrf();
		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.session(csrf.session()))
				.andExpect(status().isOk())
				.andExpect(authenticated())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andExpect(jsonPath("$.user.username").value("admin"))
				.andExpect(jsonPath("$.user.role").value("ADMIN"))
				.andReturn();

		verify(localAuthService).authenticate("admin", "admin123");

		MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
		mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andExpect(jsonPath("$.user.role").value("ADMIN"));
	}

	@Test
	void wrongCredentialsReturnInvalidCredentials() throws Exception {
		when(localAuthService.authenticate("admin", "wrong-password"))
				.thenThrow(new AuthException(
						AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed."));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"wrong-password\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.session(csrf.session()))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void logoutInvalidatesSession() throws Exception {
		when(localAuthService.authenticate("admin", "admin123")).thenReturn(SagaAuthentications.authenticated(admin()));
		Csrf csrf = fetchCsrf();
		MvcResult login = mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.session(csrf.session()))
				.andExpect(status().isOk())
				.andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession();
		Cookie xsrf = login.getResponse().getCookie("XSRF-TOKEN");
		if (xsrf == null) {
			xsrf = csrf.cookie();
		}

		mockMvc.perform(post("/api/auth/logout")
						.session(session)
						.cookie(xsrf)
						.header("X-XSRF-TOKEN", xsrf.getValue()))
				.andExpect(status().isNoContent());
		assertThat(session.isInvalid()).isTrue();

		mockMvc.perform(get("/api/auth/me")).andExpect(status().isOk()).andExpect(unauthenticated())
				.andExpect(jsonPath("$.authenticated").value(false))
				.andExpect(jsonPath("$.user").value(org.hamcrest.Matchers.nullValue()));
	}

	@Test
	void passwordSetupRequiredDoesNotBlockLogin() throws Exception {
		when(localAuthService.authenticate("admin", "admin123")).thenReturn(SagaAuthentications.authenticated(admin()));
		Csrf csrf = fetchCsrf();
		UserAccount student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("sv@fpt.edu.vn");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student.setGoogleSubject("google-sub-restricted");
		student.setPasswordHash(null);

		mockMvc.perform(post("/api/auth/login")
						.with(authentication(SagaAuthentications.authenticated(student)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"admin\",\"password\":\"admin123\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.user.role").value("ADMIN"));
		verify(localAuthService).authenticate("admin", "admin123");
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
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

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}
}

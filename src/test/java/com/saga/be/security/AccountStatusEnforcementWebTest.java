package com.saga.be.security;

import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.auth.AuthErrorCode;
import com.saga.be.auth.LocalAuthService;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AuthException;
import com.saga.be.repository.UserAccountRepository;
import jakarta.servlet.http.Cookie;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountStatusEnforcementWebTest {

	@TestConfiguration
	static class UserAccountRepositoryStub {
		@Bean
		@Primary
		UserAccountRepository userAccountRepository() {
			return Mockito.mock(UserAccountRepository.class);
		}
	}

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private LocalAuthService localAuthService;

	@BeforeEach
	void resetRepo() {
		reset(users, localAuthService);
	}

	@Test
	void disabledSessionCannotUseProtectedApiButMeAndLogoutWork() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		when(users.findById(account.getId())).thenReturn(Optional.of(account));

		mockMvc.perform(get("/api/student/anything").with(authentication(SagaAuthentications.authenticated(account))))
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"code\":\"ACCOUNT_DISABLED\",\"message\":\"Account is not available.\"}"));

		mockMvc.perform(get("/api/auth/me").with(authentication(SagaAuthentications.authenticated(account))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andExpect(jsonPath("$.user.role").value("STUDENT"));

		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/logout")
						.with(authentication(SagaAuthentications.authenticated(account)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isNoContent());
	}

	@Test
	void reEnabledAccountCanUseProtectedApiAgain() throws Exception {
		UserAccount inactive = student(AccountStatus.INACTIVE);
		when(users.findById(inactive.getId())).thenReturn(Optional.of(inactive));
		mockMvc.perform(get("/api/student/anything").with(authentication(SagaAuthentications.authenticated(inactive))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));

		inactive.setAccountStatus(AccountStatus.ACTIVE);
		when(users.findById(inactive.getId())).thenReturn(Optional.of(inactive));
		mockMvc.perform(get("/api/student/anything").with(authentication(SagaAuthentications.authenticated(inactive))))
				.andExpect(status().isOk())
				.andExpect(content().string("student-ok"));
	}

	@Test
	void loginWhileDisabledReturnsAccountDisabled() throws Exception {
		when(localAuthService.authenticate("ada@fpt.edu.vn", "secret"))
				.thenThrow(new AuthException(
						AuthErrorCode.ACCOUNT_DISABLED, HttpStatus.FORBIDDEN, "Account is not available."));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/login")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"identifier\":\"ada@fpt.edu.vn\",\"password\":\"secret\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.session(csrf.session()))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
	}

	@Test
	void publicForgotAndWebhooksAreUnaffectedByDisabledSession() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		when(users.findById(account.getId())).thenReturn(Optional.of(account));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/password/forgot")
						.with(authentication(SagaAuthentications.authenticated(account)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ada@fpt.edu.vn\"}")
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").exists());

		mockMvc.perform(post("/api/webhooks/github").with(authentication(SagaAuthentications.authenticated(account))))
				.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/webhooks/jira").with(authentication(SagaAuthentications.authenticated(account))))
				.andExpect(status().isNoContent());
		mockMvc.perform(get("/actuator/health")).andExpect(status().isOk());
	}

	@Test
	void sseReconnectWhileDisabledIsDenied() throws Exception {
		UserAccount account = student(AccountStatus.INACTIVE);
		when(users.findById(account.getId())).thenReturn(Optional.of(account));
		mockMvc.perform(get("/api/projects/" + UUID.randomUUID() + "/events")
						.with(authentication(SagaAuthentications.authenticated(account))))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCOUNT_DISABLED"));
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
	}

	private static UserAccount student(AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
		account.setEmail("ada@fpt.edu.vn");
		account.setUsername("ada");
		account.setFullName("Ada");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(status);
		account.setPasswordHash("hash");
		return account;
	}

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}
}

package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.notification.PushInstallationService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
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
class UserPushInstallationControllerWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PushInstallationService installations;

	@BeforeEach
	void resetService() {
		reset(installations);
	}

	@Test
	void anonymousRegisterWithoutCsrfIsForbidden() throws Exception {
		mockMvc.perform(put("/api/users/me/push-installations")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("fid", "token", "WEB")))
				.andExpect(status().isForbidden());
		verify(installations, never()).register(any(), any(), any(), any());
	}

	@Test
	void anonymousRegisterWithCsrfIsUnauthorized() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(put("/api/users/me/push-installations")
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("fid", "token", "WEB")))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		verify(installations, never()).register(any(), any(), any(), any());
	}

	@Test
	void registerRequiresCsrf() throws Exception {
		UUID userId = UUID.randomUUID();
		mockMvc.perform(put("/api/users/me/push-installations")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("fid", "token", "WEB")))
				.andExpect(status().isForbidden());
		verify(installations, never()).register(any(), any(), any(), any());
	}

	@Test
	void registerReturnsSafeDtoWithoutToken() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID installationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(installations.register(eq(userId), eq("fid-web"), eq("secret-token"), eq(PushPlatform.WEB)))
				.thenReturn(new PushInstallationResponse(
						installationId, "WEB", true, LocalDateTime.of(2026, 9, 16, 0, 0)));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(put("/api/users/me/push-installations")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("fid-web", "secret-token", "WEB")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(installationId.toString()))
				.andExpect(jsonPath("$.platform").value("WEB"))
				.andExpect(jsonPath("$.active").value(true))
				.andExpect(jsonPath("$.lastRegisteredAt").exists())
				.andExpect(jsonPath("$.fcmToken").doesNotExist())
				.andExpect(jsonPath("$.firebaseInstallationId").doesNotExist())
				.andExpect(jsonPath("$.ownerUserId").doesNotExist());
		verify(installations).register(userId, "fid-web", "secret-token", PushPlatform.WEB);
	}

	@Test
	void registerConflictDoesNotRevealPreviousOwner() throws Exception {
		UUID userId = UUID.randomUUID();
		when(installations.register(eq(userId), eq("fid-taken"), eq("token-taken"), eq(PushPlatform.WEB)))
				.thenThrow(new AcademicException(
						AcademicErrorCode.PUSH_INSTALLATION_CONFLICT,
						HttpStatus.CONFLICT,
						"Push installation is already registered."));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(put("/api/users/me/push-installations")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(body("fid-taken", "token-taken", "WEB")))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("PUSH_INSTALLATION_CONFLICT"))
				.andExpect(jsonPath("$.message").value("Push installation is already registered."))
				.andExpect(jsonPath("$.ownerUserId").doesNotExist())
				.andExpect(jsonPath("$.firebaseInstallationId").doesNotExist())
				.andExpect(jsonPath("$.fcmToken").doesNotExist());
	}

	@Test
	void revokeForeignOrMissingIsSame404() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID installationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(installations.revoke(userId, installationId))
				.thenThrow(new AcademicException(
						AcademicErrorCode.PUSH_INSTALLATION_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Push installation was not found."));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(delete("/api/users/me/push-installations/" + installationId)
						.with(authentication(auth(userId, AccountRole.LECTURER)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("PUSH_INSTALLATION_NOT_FOUND"));
	}

	@Test
	void studentLecturerAndAdminCanRegister() throws Exception {
		for (AccountRole role : java.util.List.of(AccountRole.STUDENT, AccountRole.LECTURER, AccountRole.ADMIN)) {
			UUID userId = UUID.randomUUID();
			when(installations.register(eq(userId), eq("fid"), eq("tok"), eq(PushPlatform.WEB)))
					.thenReturn(new PushInstallationResponse(
							UUID.randomUUID(), "WEB", true, LocalDateTime.of(2026, 9, 16, 0, 0)));
			Csrf csrf = fetchCsrf();
			mockMvc.perform(put("/api/users/me/push-installations")
							.with(authentication(auth(userId, role)))
							.session(csrf.session())
							.cookie(csrf.cookie())
							.header("X-XSRF-TOKEN", csrf.token())
							.contentType(MediaType.APPLICATION_JSON)
							.content(body("fid", "tok", "WEB")))
					.andExpect(status().isOk());
			reset(installations);
		}
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
	}

	private static String body(String fid, String token, String platform) {
		return "{\"firebaseInstallationId\":\""
				+ fid
				+ "\",\"fcmToken\":\""
				+ token
				+ "\",\"platform\":\""
				+ platform
				+ "\"}";
	}

	private static org.springframework.security.core.Authentication auth(UUID userId, AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setEmail(userId + "@fpt.edu.vn");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("hash");
		return SagaAuthentications.authenticated(account);
	}

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}
}

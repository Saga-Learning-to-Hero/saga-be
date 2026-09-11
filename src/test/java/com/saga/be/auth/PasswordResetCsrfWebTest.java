package com.saga.be.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.exception.AuthException;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Anonymous access + CSRF behavior for the forgot/reset endpoints, and that the public forgot
 * response never varies by outcome (no user enumeration).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordResetCsrfWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private PasswordResetService passwordResetService;

	@BeforeEach
	void resetService() {
		reset(passwordResetService);
	}

	@Test
	void forgotWithoutCsrfIsDenied() throws Exception {
		mockMvc.perform(post("/api/auth/password/forgot")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"someone@fpt.edu.vn\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verifyNoInteractions(passwordResetService);
	}

	@Test
	void resetWithoutCsrfIsDenied() throws Exception {
		mockMvc.perform(post("/api/auth/password/reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"abc\",\"newPassword\":\"brand-new-pass1\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		verifyNoInteractions(passwordResetService);
	}

	@Test
	void forgotIsPublicAndReturnsSameGenericMessageRegardlessOfOutcome() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/password/forgot")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"exists@fpt.edu.vn\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi."));
		verify(passwordResetService).forgotPassword("exists@fpt.edu.vn");

		reset(passwordResetService);
		Csrf csrf2 = fetchCsrf();
		mockMvc.perform(post("/api/auth/password/forgot")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"email\":\"ghost-does-not-exist@fpt.edu.vn\"}")
						.cookie(csrf2.cookie())
						.header("X-XSRF-TOKEN", csrf2.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").value("Nếu email tồn tại, hướng dẫn đặt lại mật khẩu đã được gửi."));
		verify(passwordResetService).forgotPassword("ghost-does-not-exist@fpt.edu.vn");
	}

	@Test
	void resetWithCsrfReachesServiceAndSurfacesInvalidTokenAsAuthDomainError() throws Exception {
		doThrow(new AuthException(
						AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID,
						HttpStatus.BAD_REQUEST,
						"This password reset link is invalid or has already been used."))
				.when(passwordResetService)
				.resetPassword(eq("bad-token"), eq("brand-new-pass1"));
		Csrf csrf = fetchCsrf();

		mockMvc.perform(post("/api/auth/password/reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"bad-token\",\"newPassword\":\"brand-new-pass1\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("PASSWORD_RESET_TOKEN_INVALID"));
	}

	@Test
	void resetWithCsrfSucceedsAndReturnsSuccessMessage() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/auth/password/reset")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"token\":\"good-token\",\"newPassword\":\"brand-new-pass1\"}")
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.message").exists());
		verify(passwordResetService).resetPassword("good-token", "brand-new-pass1");
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie);
	}

	private record Csrf(String token, Cookie cookie) {}
}

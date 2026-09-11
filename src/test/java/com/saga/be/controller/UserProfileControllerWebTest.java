package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.auth.UserProfileService;
import com.saga.be.dto.auth.UserProfileResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
import jakarta.servlet.http.Cookie;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.Authentication;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Mocks {@link UserProfileService} (as {@link com.saga.be.auth.AuthServiceTestConfiguration}
 * registers it under the "test" profile) so this stays a pure web-layer test: authentication,
 * CSRF, self-only identification via {@code SagaUserPrincipal}, and the post-PATCH session
 * refresh. Field-level business rules (which fields are editable, validation) are covered by
 * {@link com.saga.be.auth.UserProfileServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserProfileControllerWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserProfileService userProfileService;

	@BeforeEach
	void resetService() {
		reset(userProfileService);
	}

	@Test
	void getProfileWithoutAuthenticationIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/users/me/profile"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
	}

	@Test
	void patchProfileWithoutAuthenticationIsRejected() throws Exception {
		// CSRF is checked before authentication in the filter chain, so an anonymous PATCH with no
		// CSRF token is rejected as 403 ACCESS_DENIED rather than 401 — same behavior as every other
		// state-changing endpoint (see AuthLoginCsrfTest#loginWithoutCsrfIsDeniedAndDoesNotReachLocalAuthService).
		mockMvc.perform(patch("/api/users/me/profile")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"New Name\"}"))
				.andExpect(status().isForbidden());
		verify(userProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void patchProfileWithCsrfButNoAuthenticationIsUnauthorized() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"New Name\"}"))
				.andExpect(status().isUnauthorized());
		verify(userProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void patchProfileWithoutCsrfIsForbidden() throws Exception {
		UUID id = UUID.randomUUID();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"New Name\"}"))
				.andExpect(status().isForbidden());
		verify(userProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void getProfileReturnsCallersOwnProfileOnly() throws Exception {
		UUID id = UUID.randomUUID();
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", "Original Name", null, "STUDENT", "ACTIVE", "SE123456"));

		mockMvc.perform(get("/api/users/me/profile").with(authentication(SagaAuthentications.authenticated(student(id)))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.studentCode").value("SE123456"));
		verify(userProfileService).getProfile(id);
	}

	@Test
	void patchProfileDelegatesToServiceUsingPrincipalIdAndRefreshesSession() throws Exception {
		UUID id = UUID.randomUUID();
		UserAccount updated = student(id);
		updated.setFullName("Updated Name");
		Authentication refreshed = SagaAuthentications.authenticated(updated);
		when(userProfileService.updateProfile(eq(id), any())).thenReturn(refreshed);
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id,
						"anvse170102@fpt.edu.vn",
						"anvse170102",
						"Updated Name",
						null,
						"STUDENT",
						"ACTIVE",
						"SE123456"));

		Csrf csrf = fetchCsrf();
		MvcResult result = mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Updated Name\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()))
				.andExpect(jsonPath("$.role").value("STUDENT"))
				.andExpect(jsonPath("$.accountStatus").value("ACTIVE"))
				.andExpect(jsonPath("$.fullName").value("Updated Name"))
				.andReturn();

		verify(userProfileService).updateProfile(eq(id), any());

		// SessionEstablisher rotates the session id but the caller stays authenticated with the
		// same identity (id/role/status unchanged) and /api/auth/me immediately reflects the change.
		MockHttpSession session = (MockHttpSession) result.getRequest().getSession();
		mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.authenticated").value(true))
				.andExpect(jsonPath("$.user.id").value(id.toString()))
				.andExpect(jsonPath("$.user.role").value("STUDENT"))
				.andExpect(jsonPath("$.user.fullName").value("Updated Name"));
	}

	@Test
	void csrfCookieStaysValidAcrossSessionRotationFromPatch() throws Exception {
		// CookieCsrfTokenRepository is a double-submit cookie, independent of the session id, so
		// SessionEstablisher's request.changeSessionId() during PATCH must not invalidate it: the
		// same cookie/header pair must still work for a second PATCH right after.
		UUID id = UUID.randomUUID();
		when(userProfileService.updateProfile(eq(id), any())).thenReturn(SagaAuthentications.authenticated(student(id)));
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", "Original Name", null, "STUDENT", "ACTIVE", "SE123456"));

		Csrf csrf = fetchCsrf();
		MvcResult first = mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"First Update\"}"))
				.andExpect(status().isOk())
				.andReturn();

		MockHttpSession rotatedSession = (MockHttpSession) first.getRequest().getSession();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(rotatedSession)
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"Second Update\"}"))
				.andExpect(status().isOk());

		verify(userProfileService, org.mockito.Mockito.times(2)).updateProfile(eq(id), any());
	}

	@Test
	void extraJsonFieldsInPatchBodyAreIgnoredNotBound() throws Exception {
		UUID id = UUID.randomUUID();
		when(userProfileService.updateProfile(eq(id), any()))
				.thenReturn(SagaAuthentications.authenticated(student(id)));
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", "Original Name", null, "STUDENT", "ACTIVE", "SE123456"));

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content(
								"{\"fullName\":\"New Name\",\"role\":\"ADMIN\",\"studentCode\":\"HACKED\",\"id\":\"00000000-0000-0000-0000-000000000099\"}"))
				.andExpect(status().isOk());

		verify(userProfileService).updateProfile(eq(id), any());
	}

	@Test
	void fullNameAtMaxLengthIsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		String maxName = "A".repeat(255); // user_account.full_name is VARCHAR(255)
		when(userProfileService.updateProfile(eq(id), any())).thenReturn(SagaAuthentications.authenticated(student(id)));
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", maxName, null, "STUDENT", "ACTIVE", "SE123456"));

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"" + maxName + "\"}"))
				.andExpect(status().isOk());
		verify(userProfileService).updateProfile(eq(id), any());
	}

	@Test
	void fullNameOverMaxLengthIsRejectedWithoutReachingService() throws Exception {
		UUID id = UUID.randomUUID();
		String tooLong = "A".repeat(256); // one over user_account.full_name VARCHAR(255)

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"" + tooLong + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		verify(userProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void avatarUrlAtMaxLengthIsAccepted() throws Exception {
		UUID id = UUID.randomUUID();
		String prefix = "https://example.com/";
		String maxUrl = prefix + "a".repeat(500 - prefix.length()); // user_account.avatar_url is VARCHAR(500)
		assertThat(maxUrl).hasSize(500);
		when(userProfileService.updateProfile(eq(id), any())).thenReturn(SagaAuthentications.authenticated(student(id)));
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", "Original Name", maxUrl, "STUDENT", "ACTIVE", "SE123456"));

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"avatarUrl\":\"" + maxUrl + "\"}"))
				.andExpect(status().isOk());
		verify(userProfileService).updateProfile(eq(id), any());
	}

	@Test
	void avatarUrlOverMaxLengthIsRejectedWithoutReachingService() throws Exception {
		UUID id = UUID.randomUUID();
		String prefix = "https://example.com/";
		String tooLong = prefix + "a".repeat(501 - prefix.length()); // one over VARCHAR(500)
		assertThat(tooLong).hasSize(501);

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"avatarUrl\":\"" + tooLong + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		verify(userProfileService, never()).updateProfile(any(), any());
	}

	@Test
	void vietnameseDiacriticsCountOneCharEachMatchingMySqlUtf8mb4Semantics() throws Exception {
		// Precomposed Vietnamese diacritics (NFC, as any real client sends) are single UTF-16 code
		// units, so Java's @Size(max=255) counts them 1-for-1 with MySQL's utf8mb4 VARCHAR(255)
		// character length — a 255-character Vietnamese name is accepted, not rejected.
		UUID id = UUID.randomUUID();
		String name = "Nguyễn Văn ".repeat(23) + "Anh"; // 11*23 + 3 = 256 chars if miscounted; verify exact size below
		String maxName = adjustToExactLength(name, 255);
		when(userProfileService.updateProfile(eq(id), any())).thenReturn(SagaAuthentications.authenticated(student(id)));
		when(userProfileService.getProfile(id))
				.thenReturn(new UserProfileResponse(
						id, "anvse170102@fpt.edu.vn", "anvse170102", maxName, null, "STUDENT", "ACTIVE", "SE123456"));

		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/profile")
						.with(authentication(SagaAuthentications.authenticated(student(id))))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"fullName\":\"" + maxName + "\"}"))
				.andExpect(status().isOk());
		verify(userProfileService).updateProfile(eq(id), any());
	}

	private static String adjustToExactLength(String source, int length) {
		assertThat(source.length()).isGreaterThanOrEqualTo(length);
		return source.substring(0, length);
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
	}

	private static UserAccount student(UUID id) {
		UserAccount account = new UserAccount();
		account.setId(id);
		account.setEmail("anvse170102@fpt.edu.vn");
		account.setUsername("anvse170102");
		account.setFullName("Original Name");
		account.setGoogleSubject("google-sub-student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("not-plaintext");
		return account;
	}

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}
}

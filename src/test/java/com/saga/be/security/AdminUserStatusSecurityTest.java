package com.saga.be.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.service.admin.AdminUserCommandService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminUserStatusSecurityTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private AdminUserCommandService commands;

	@BeforeEach
	void resetMocks() {
		reset(commands);
	}

	@Test
	void anonymousStatusPatchIsUnauthorized() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/admin/users/" + UUID.randomUUID() + "/status")
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INACTIVE\"}"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}"));
		verifyNoInteractions(commands);
	}

	@Test
	void studentAndLecturerCannotPatchStatus() throws Exception {
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/admin/users/" + UUID.randomUUID() + "/status")
						.with(authentication(auth(AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INACTIVE\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
		mockMvc.perform(patch("/api/admin/users/" + UUID.randomUUID() + "/status")
						.with(authentication(auth(AccountRole.LECTURER)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"ACTIVE\"}"))
				.andExpect(status().isForbidden());
		verifyNoInteractions(commands);
	}

	@Test
	void adminCanPatchStatus() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(commands.updateStatus(any(), eq(id), eq("INACTIVE"), any()))
				.thenReturn(new AdminUserResponse(
						id,
						"ada@fpt.edu.vn",
						"ada",
						"Ada",
						null,
						"STUDENT",
						"INACTIVE",
						"SE123456",
						null,
						LocalDateTime.of(2026, 1, 2, 3, 4)));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/admin/users/" + id + "/status")
						.with(authentication(auth(AccountRole.ADMIN)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"status\":\"INACTIVE\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountStatus").value("INACTIVE"))
				.andExpect(jsonPath("$.role").value("STUDENT"))
				.andExpect(jsonPath("$.passwordHash").doesNotExist());
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
	}

	private static org.springframework.security.core.Authentication auth(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail("a@fpt.edu.vn");
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("hash");
		return SagaAuthentications.authenticated(account);
	}

	private record Csrf(String token, Cookie cookie, MockHttpSession session) {}
}

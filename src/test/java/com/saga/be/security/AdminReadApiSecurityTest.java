package com.saga.be.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.service.admin.AdminAuditLogQueryService;
import com.saga.be.service.admin.AdminUserQueryService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
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
class AdminReadApiSecurityTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminUserQueryService users;

	@Autowired
	private AdminAuditLogQueryService auditLogs;

	@BeforeEach
	void resetMocks() {
		Mockito.reset(users, auditLogs);
	}

	@Test
	void unauthenticatedUserAndAuditReadsAreDenied() throws Exception {
		mockMvc.perform(get("/api/admin/users"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}"));
		mockMvc.perform(get("/api/admin/audit-logs"))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"INVALID_CREDENTIALS\",\"message\":\"Authentication failed.\"}"));
	}

	@Test
	void studentAndLecturerCannotReadUsersOrAuditLogs() throws Exception {
		mockMvc.perform(get("/api/admin/users").with(authentication(auth(AccountRole.STUDENT))))
				.andExpect(status().isForbidden())
				.andExpect(content().json("{\"code\":\"ACCESS_DENIED\",\"message\":\"Access denied.\"}"));
		mockMvc.perform(get("/api/admin/users").with(authentication(auth(AccountRole.LECTURER))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/audit-logs").with(authentication(auth(AccountRole.STUDENT))))
				.andExpect(status().isForbidden());
		mockMvc.perform(get("/api/admin/audit-logs").with(authentication(auth(AccountRole.LECTURER))))
				.andExpect(status().isForbidden());
	}

	@Test
	void adminCanReadUsersAndAuditLogs() throws Exception {
		UUID id = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(users.list(isNull(), isNull(), isNull(), isNull(), isNull()))
				.thenReturn(new AdminUserPageResponse(
						List.of(new AdminUserResponse(
								id,
								"ada@fpt.edu.vn",
								"ada",
								"Ada",
								null,
								"STUDENT",
								"ACTIVE",
								"SE123456",
								null,
								LocalDateTime.of(2026, 1, 2, 3, 4))),
						0,
						50,
						1));
		when(users.get(id))
				.thenReturn(new AdminUserResponse(
						id, "ada@fpt.edu.vn", "ada", "Ada", null, "STUDENT", "ACTIVE", "SE123456", null, LocalDateTime.of(2026, 1, 2, 3, 4)));
		when(auditLogs.list(any(), any(), any(), any(), any(), any(), any(), any()))
				.thenReturn(new AdminAuditLogPageResponse(List.of(), 0, 50, 0));

		mockMvc.perform(get("/api/admin/users").with(authentication(auth(AccountRole.ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].email").value("ada@fpt.edu.vn"))
				.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist());
		mockMvc.perform(get("/api/admin/users/" + id).with(authentication(auth(AccountRole.ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(id.toString()));
		mockMvc.perform(get("/api/admin/audit-logs").with(authentication(auth(AccountRole.ADMIN))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0));
	}

	@Test
	void adminCannotMutateAuditLogs() throws Exception {
		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession();

		mockMvc.perform(post("/api/admin/audit-logs")
						.with(authentication(auth(AccountRole.ADMIN)))
						.session(session)
						.cookie(cookie)
						.header("X-XSRF-TOKEN", token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mockMvc.perform(patch("/api/admin/audit-logs")
						.with(authentication(auth(AccountRole.ADMIN)))
						.session(session)
						.cookie(cookie)
						.header("X-XSRF-TOKEN", token)
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isMethodNotAllowed());
		mockMvc.perform(delete("/api/admin/audit-logs")
						.with(authentication(auth(AccountRole.ADMIN)))
						.session(session)
						.cookie(cookie)
						.header("X-XSRF-TOKEN", token))
				.andExpect(status().isMethodNotAllowed());
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
}

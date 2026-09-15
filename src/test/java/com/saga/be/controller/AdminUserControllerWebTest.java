package com.saga.be.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.GlobalExceptionHandler;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.admin.AdminUserCommandService;
import com.saga.be.service.admin.AdminUserQueryService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class AdminUserControllerWebTest {

	@Mock
	private AdminUserQueryService users;

	@Mock
	private AdminUserCommandService commands;

	private MockMvc mockMvc;

	@BeforeEach
	void setUp() {
		mockMvc = MockMvcBuilders.standaloneSetup(new AdminUserController(users, commands))
				.setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
				.setControllerAdvice(new GlobalExceptionHandler())
				.build();
	}

	@Test
	void listExposesSafeFieldsOnly() throws Exception {
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
								LocalDateTime.of(2026, 1, 2, 3, 4, 5))),
						0,
						50,
						1));
		mockMvc.perform(get("/api/admin/users"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.page").value(0))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].id").value(id.toString()))
				.andExpect(jsonPath("$.items[0].email").value("ada@fpt.edu.vn"))
				.andExpect(jsonPath("$.items[0].studentCode").value("SE123456"))
				.andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
				.andExpect(jsonPath("$.items[0].googleSubject").doesNotExist())
				.andExpect(jsonPath("$.items[0].password_hash").doesNotExist());
	}

	@Test
	void detail404() throws Exception {
		UUID id = UUID.fromString("22222222-2222-2222-2222-222222222222");
		when(users.get(id))
				.thenThrow(new AcademicException(
						AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
		mockMvc.perform(get("/api/admin/users/" + id))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("USER_ACCOUNT_NOT_FOUND"));
	}

	@Test
	void listPassesQueryParams() throws Exception {
		when(users.list("ada", "STUDENT", "ACTIVE", 1, 20))
				.thenReturn(new AdminUserPageResponse(List.of(), 1, 20, 0));
		mockMvc.perform(get("/api/admin/users")
						.param("q", "ada")
						.param("role", "STUDENT")
						.param("status", "ACTIVE")
						.param("page", "1")
						.param("size", "20"))
				.andExpect(status().isOk());
		verify(users).list("ada", "STUDENT", "ACTIVE", 1, 20);
	}

	@Test
	void roleAdminIsRequestInvalid() throws Exception {
		when(users.list(isNull(), org.mockito.ArgumentMatchers.eq("ADMIN"), isNull(), isNull(), isNull()))
				.thenThrow(new AcademicException(
						AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, "role is invalid."));
		mockMvc.perform(get("/api/admin/users").param("role", "ADMIN"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
	}

	@Test
	void patchStatusReturnsSafeUserAndHidesAdminAsNotFound() throws Exception {
		UUID targetId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UserAccount admin = actor();
		org.springframework.security.core.context.SecurityContextHolder.getContext()
				.setAuthentication(SagaAuthentications.authenticated(admin));
		try {
			when(commands.updateStatus(eq(admin.getId()), eq(targetId), eq("INACTIVE"), any(AuditRequest.class)))
					.thenReturn(new AdminUserResponse(
							targetId,
							"ada@fpt.edu.vn",
							"ada",
							"Ada",
							null,
							"STUDENT",
							"INACTIVE",
							"SE123456",
							null,
							LocalDateTime.of(2026, 1, 2, 3, 4, 5)));
			mockMvc.perform(patch("/api/admin/users/" + targetId + "/status")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"status\":\"INACTIVE\"}"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.id").value(targetId.toString()))
					.andExpect(jsonPath("$.role").value("STUDENT"))
					.andExpect(jsonPath("$.accountStatus").value("INACTIVE"))
					.andExpect(jsonPath("$.studentCode").value("SE123456"))
					.andExpect(jsonPath("$.passwordHash").doesNotExist())
					.andExpect(jsonPath("$.googleSubject").doesNotExist());

			when(commands.updateStatus(eq(admin.getId()), eq(targetId), eq("INACTIVE"), any(AuditRequest.class)))
					.thenThrow(new AcademicException(
							AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, HttpStatus.NOT_FOUND, "User was not found."));
			mockMvc.perform(patch("/api/admin/users/" + targetId + "/status")
							.contentType(MediaType.APPLICATION_JSON)
							.content("{\"status\":\"INACTIVE\"}"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("USER_ACCOUNT_NOT_FOUND"));
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
	}

	private static UserAccount actor() {
		UserAccount account = new UserAccount();
		account.setId(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
		account.setEmail("root@saga.local");
		account.setAccountRole(AccountRole.ADMIN);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("hash");
		return account;
	}
}

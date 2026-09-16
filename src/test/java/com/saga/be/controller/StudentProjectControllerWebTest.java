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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.project.PatchProjectRequest;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.dto.project.StudentProjectResponse.CreatedBy;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.student.StudentProjectService;
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
class StudentProjectControllerWebTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private StudentProjectService projects;

	@BeforeEach
	void resetService() {
		reset(projects);
	}

	@Test
	void teamLeaderPatchReturnsStudentProjectResponse() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		UUID teamId = UUID.randomUUID();
		when(projects.patch(eq(userId), eq(projectId), eq(new PatchProjectRequest("New Name", "New body")), any()))
				.thenReturn(new StudentProjectResponse(
						projectId,
						courseId,
						teamId,
						1,
						"SAGA Team",
						"New Name",
						"New body",
						null,
						new CreatedBy(userId, "Alpha Leader"),
						LocalDateTime.of(2026, 9, 1, 10, 0)));

		mockMvc.perform(patch("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"New Name\",\"description\":\"New body\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.projectId").value(projectId.toString()))
				.andExpect(jsonPath("$.courseId").value(courseId.toString()))
				.andExpect(jsonPath("$.teamId").value(teamId.toString()))
				.andExpect(jsonPath("$.name").value("New Name"))
				.andExpect(jsonPath("$.description").value("New body"))
				.andExpect(jsonPath("$.createdBy.userId").value(userId.toString()));
		verify(projects).patch(eq(userId), eq(projectId), eq(new PatchProjectRequest("New Name", "New body")), any());
	}

	@Test
	void emptyBodyIsForwardedAsNoOpRequest() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		when(projects.patch(eq(userId), eq(projectId), eq(new PatchProjectRequest(null, null)), any()))
				.thenReturn(new StudentProjectResponse(
						projectId, null, UUID.randomUUID(), 1, "Team", "Keep", "Keep me", null, null, null));

		mockMvc.perform(patch("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("Keep"));
		verify(projects).patch(eq(userId), eq(projectId), eq(new PatchProjectRequest(null, null)), any(AuditRequest.class));
	}

	@Test
	void oversizedNameIsRejectedBeforeService() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		mockMvc.perform(patch("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"" + "x".repeat(256) + "\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("REQUEST_INVALID"));
		verify(projects, never()).patch(any(), any(), any(), any());
	}

	@Test
	void lecturerDeniedByServiceAuthorization() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		when(projects.patch(eq(userId), eq(projectId), any(), any()))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, "Access denied."));
		mockMvc.perform(patch("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.LECTURER)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Nope\"}"))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
	}

	@Test
	void putIsNotMapped() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		mockMvc.perform(put("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Nope\"}"))
				.andExpect(status().isMethodNotAllowed());
		verify(projects, never()).patch(any(), any(), any(), any());
	}

	@Test
	void patchWithoutCsrfDoesNotReachService() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID projectId = UUID.randomUUID();
		mockMvc.perform(patch("/api/projects/" + projectId)
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"name\":\"Nope\"}"))
				.andExpect(status().isForbidden());
		verify(projects, never()).patch(any(), any(), any(), any());
	}

	private Csrf fetchCsrf() throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
		assertThat(cookie).isNotNull();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		return new Csrf(token, cookie, (MockHttpSession) csrf.getRequest().getSession());
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

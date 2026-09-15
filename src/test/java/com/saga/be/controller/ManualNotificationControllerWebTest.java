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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.saga.be.dto.notification.ManualNotificationRequest;
import com.saga.be.dto.notification.NotificationSendResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.notification.ManualNotificationService;
import jakarta.servlet.http.Cookie;
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
class ManualNotificationControllerWebTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private ManualNotificationService sends;

	@BeforeEach
	void resetService() {
		reset(sends);
	}

	@Test
	void studentCannotCallAdminOrLecturerSend() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		mockMvc.perform(post("/api/admin/notifications/system")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isForbidden());
		mockMvc.perform(post("/api/lecturer/notifications/all-courses")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isForbidden());
		verify(sends, never()).sendSystem(any(UUID.class), any(), any(), any());
		verify(sends, never()).sendAllCourses(any(UUID.class), any(), any(), any());
	}

	@Test
	void lecturerCannotCallAdminSystem() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		mockMvc.perform(post("/api/admin/notifications/system")
						.with(authentication(auth(userId, AccountRole.LECTURER)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isForbidden());
		verify(sends, never()).sendSystem(any(UUID.class), any(), any(), any());
	}

	@Test
	void adminCannotCallLecturerSendEndpoints() throws Exception {
		Csrf csrf = fetchCsrf();
		UUID userId = UUID.randomUUID();
		UUID courseId = UUID.randomUUID();
		mockMvc.perform(post("/api/lecturer/courses/" + courseId + "/notifications")
						.with(authentication(auth(userId, AccountRole.ADMIN)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.header("Idempotency-Key", UUID.randomUUID().toString())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isForbidden());
		verify(sends, never()).sendCourse(any(UUID.class), any(), any(), any(), any());
	}

	@Test
	void adminSystemRequiresCsrfAndReturnsSendResult() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID sendId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
		when(sends.sendSystem(eq(userId), eq("idem-1"), any(ManualNotificationRequest.class), any()))
				.thenReturn(new NotificationSendResponse(sendId, "ALL", 3, 3));
		mockMvc.perform(post("/api/admin/notifications/system")
						.with(authentication(auth(userId, AccountRole.ADMIN)))
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isForbidden());
		Csrf csrf = fetchCsrf();
		mockMvc.perform(post("/api/admin/notifications/system")
						.with(authentication(auth(userId, AccountRole.ADMIN)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token())
						.header("Idempotency-Key", "idem-1")
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"title\":\"Hello\",\"message\":\"Body\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.sendId").value(sendId.toString()))
				.andExpect(jsonPath("$.scope").value("ALL"))
				.andExpect(jsonPath("$.recipientCount").value(3))
				.andExpect(jsonPath("$.createdCount").value(3))
				.andExpect(jsonPath("$.eventKey").doesNotExist())
				.andExpect(jsonPath("$.recipients").doesNotExist());
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

package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
import com.saga.be.dto.notification.NotificationReadAllResponse;
import com.saga.be.dto.notification.UnreadNotificationCountResponse;
import com.saga.be.dto.notification.UserNotificationPageResponse;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.security.SagaAuthentications;
import com.saga.be.service.notification.NotificationService;
import jakarta.servlet.http.Cookie;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UserNotificationControllerWebTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private NotificationService notifications;

	@BeforeEach
	void resetService() {
		reset(notifications);
	}

	@Test
	void anonymousListIsUnauthorized() throws Exception {
		mockMvc.perform(get("/api/users/me/notifications"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
		verify(notifications, never()).listOwn(any(), any(), any(), any());
	}

	@Test
	void studentLecturerAndAdminListOwnInbox() throws Exception {
		for (AccountRole role : List.of(AccountRole.STUDENT, AccountRole.LECTURER, AccountRole.ADMIN)) {
			UUID userId = UUID.randomUUID();
			when(notifications.listOwn(eq(userId), isNull(), isNull(), isNull()))
					.thenReturn(new UserNotificationPageResponse(List.of(), 0, 50, 0));
			mockMvc.perform(get("/api/users/me/notifications").with(authentication(auth(userId, role))))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.items").isArray())
					.andExpect(jsonPath("$.page").value(0))
					.andExpect(jsonPath("$.size").value(50))
					.andExpect(jsonPath("$.total").value(0));
			verify(notifications).listOwn(userId, null, null, null);
			reset(notifications);
		}
	}

	@Test
	void listPassesPagingAndUnreadOnlyAndOmitsSecrets() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID notificationId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		when(notifications.listOwn(userId, 1, 20, true))
				.thenReturn(new UserNotificationPageResponse(
						List.of(new UserNotificationResponse(
								notificationId,
								"TEAM",
								"Added to a team",
								"You were added to Team 1.",
								"/teams/1",
								null,
								LocalDateTime.of(2026, 9, 1, 10, 0))),
						1,
						20,
						1));
		mockMvc.perform(get("/api/users/me/notifications")
						.param("page", "1")
						.param("size", "20")
						.param("unreadOnly", "true")
						.with(authentication(auth(userId, AccountRole.STUDENT))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items[0].id").value(notificationId.toString()))
				.andExpect(jsonPath("$.items[0].notificationType").value("TEAM"))
				.andExpect(jsonPath("$.items[0].title").value("Added to a team"))
				.andExpect(jsonPath("$.items[0].message").value("You were added to Team 1."))
				.andExpect(jsonPath("$.items[0].actionUrl").value("/teams/1"))
				.andExpect(jsonPath("$.items[0].readAt").value(org.hamcrest.Matchers.nullValue()))
				.andExpect(jsonPath("$.items[0].recipientUserId").doesNotExist())
				.andExpect(jsonPath("$.items[0].eventKey").doesNotExist())
				.andExpect(jsonPath("$.items[0].broadcast").doesNotExist())
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(20))
				.andExpect(jsonPath("$.total").value(1));
		verify(notifications).listOwn(userId, 1, 20, true);
	}

	@Test
	void unreadCountUsesAuthenticatedUser() throws Exception {
		UUID userId = UUID.randomUUID();
		when(notifications.unreadCount(userId)).thenReturn(new UnreadNotificationCountResponse(3));
		mockMvc.perform(get("/api/users/me/notifications/unread-count")
						.with(authentication(auth(userId, AccountRole.LECTURER))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.unreadCount").value(3));
		verify(notifications).unreadCount(userId);
	}

	@Test
	void markReadRequiresCsrfAndReturnsSame404ForMissing() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID notificationId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		mockMvc.perform(patch("/api/users/me/notifications/" + notificationId + "/read")
						.with(authentication(auth(userId, AccountRole.STUDENT))))
				.andExpect(status().isForbidden());
		verify(notifications, never()).markRead(any(), any());

		when(notifications.markRead(userId, notificationId))
				.thenThrow(new AcademicException(
						AcademicErrorCode.NOTIFICATION_NOT_FOUND, HttpStatus.NOT_FOUND, "Notification was not found."));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/notifications/" + notificationId + "/read")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("NOTIFICATION_NOT_FOUND"));
	}

	@Test
	void markReadReturnsDto() throws Exception {
		UUID userId = UUID.randomUUID();
		UUID notificationId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		when(notifications.markRead(userId, notificationId))
				.thenReturn(new UserNotificationResponse(
						notificationId,
						"SYSTEM",
						"Hello",
						"Body",
						null,
						LocalDateTime.of(2026, 9, 1, 12, 0),
						LocalDateTime.of(2026, 9, 1, 10, 0)));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/notifications/" + notificationId + "/read")
						.with(authentication(auth(userId, AccountRole.ADMIN)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(notificationId.toString()))
				.andExpect(jsonPath("$.readAt").exists())
				.andExpect(jsonPath("$.eventKey").doesNotExist());
		verify(notifications).markRead(userId, notificationId);
	}

	@Test
	void markAllReadReturnsUpdatedCount() throws Exception {
		UUID userId = UUID.randomUUID();
		when(notifications.markAllRead(userId))
				.thenReturn(new NotificationReadAllResponse(12, LocalDateTime.of(2026, 9, 1, 12, 0)));
		Csrf csrf = fetchCsrf();
		mockMvc.perform(patch("/api/users/me/notifications/read-all")
						.with(authentication(auth(userId, AccountRole.STUDENT)))
						.session(csrf.session())
						.cookie(csrf.cookie())
						.header("X-XSRF-TOKEN", csrf.token()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.updatedCount").value(12))
				.andExpect(jsonPath("$.readAt").exists());
		verify(notifications).markAllRead(userId);
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

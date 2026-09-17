package com.saga.be.controller;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.dto.notification.NotificationReadAllResponse;
import com.saga.be.dto.notification.UnreadNotificationCountResponse;
import com.saga.be.dto.notification.UserNotificationPageResponse;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.exception.AuthException;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Workload(WorkloadClass.INTERACTIVE_NORMAL)
@RequestMapping("/api/users/me/notifications")
@SecurityRequirement(name = com.saga.be.config.OpenApiConfig.SESSION_COOKIE_SCHEME)
@Tag(
		name = "User notifications",
		description = "Authenticated caller's own inbox. STUDENT, LECTURER, and ADMIN read only their rows.")
public class UserNotificationController {

	private final NotificationService notifications;

	public UserNotificationController(NotificationService notifications) {
		this.notifications = notifications;
	}

	@GetMapping
	@Operation(summary = "List my notifications")
	public UserNotificationPageResponse list(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size,
			@RequestParam(required = false) Boolean unreadOnly) {
		return notifications.listOwn(requireUserId(principal), page, size, unreadOnly);
	}

	@GetMapping("/unread-count")
	@Workload(WorkloadClass.INTERACTIVE_LIGHT)
	@Operation(summary = "Count my unread notifications")
	public UnreadNotificationCountResponse unreadCount(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal) {
		return notifications.unreadCount(requireUserId(principal));
	}

	@PatchMapping("/read-all")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Mark all my unread notifications as read")
	public NotificationReadAllResponse markAllRead(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal) {
		return notifications.markAllRead(requireUserId(principal));
	}

	@PatchMapping("/{notificationId}/read")
	@Workload(WorkloadClass.INTERACTIVE_WRITE)
	@Operation(summary = "Mark one of my notifications as read")
	public UserNotificationResponse markRead(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID notificationId) {
		return notifications.markRead(requireUserId(principal), notificationId);
	}

	private static UUID requireUserId(SagaUserPrincipal principal) {
		if (principal == null) {
			throw new AuthException(
					AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
		}
		return principal.getUserId();
	}
}

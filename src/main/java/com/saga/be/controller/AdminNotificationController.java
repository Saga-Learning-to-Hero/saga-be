package com.saga.be.controller;

import com.saga.be.dto.notification.ManualNotificationRequest;
import com.saga.be.dto.notification.NotificationSendResponse;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.notification.ManualNotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/notifications")
@Tag(name = "Admin notifications", description = "ADMIN SYSTEM announcements to ACTIVE STUDENT and LECTURER accounts.")
@SecurityRequirement(name = "SAGA_SESSION")
public class AdminNotificationController {

	private final ManualNotificationService sends;

	public AdminNotificationController(ManualNotificationService sends) {
		this.sends = sends;
	}

	@PostMapping("/system")
	@Operation(summary = "Send a SYSTEM notification to ACTIVE STUDENT and LECTURER accounts")
	public NotificationSendResponse sendSystem(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ManualNotificationRequest request,
			HttpServletRequest http) {
		return sends.sendSystem(principal.getUserId(), idempotencyKey, request, audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}

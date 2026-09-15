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
import java.util.UUID;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/lecturer")
@Tag(
		name = "Lecturer notifications",
		description = "Targeted COURSE/TEAM announcements for courses the authenticated lecturer currently supervises.")
@SecurityRequirement(name = "SAGA_SESSION")
public class LecturerNotificationController {

	private final ManualNotificationService sends;

	public LecturerNotificationController(ManualNotificationService sends) {
		this.sends = sends;
	}

	@PostMapping("/notifications/all-courses")
	@Operation(summary = "Notify eligible students across every course assigned to this lecturer")
	public NotificationSendResponse sendAllCourses(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ManualNotificationRequest request,
			HttpServletRequest http) {
		return sends.sendAllCourses(principal.getUserId(), idempotencyKey, request, audit(http));
	}

	@PostMapping("/courses/{courseId}/notifications")
	@Operation(summary = "Notify eligible students enrolled in one supervised course")
	public NotificationSendResponse sendCourse(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ManualNotificationRequest request,
			HttpServletRequest http) {
		return sends.sendCourse(principal.getUserId(), courseId, idempotencyKey, request, audit(http));
	}

	@PostMapping("/teams/{teamId}/notifications")
	@Operation(summary = "Notify eligible active members of a team under a supervised course")
	public NotificationSendResponse sendTeam(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID teamId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ManualNotificationRequest request,
			HttpServletRequest http) {
		return sends.sendTeam(principal.getUserId(), teamId, idempotencyKey, request, audit(http));
	}

	@PostMapping("/courses/{courseId}/students/{studentId}/notifications")
	@Operation(summary = "Notify one eligible student in a supervised course")
	public NotificationSendResponse sendStudent(
			@AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID courseId,
			@PathVariable UUID studentId,
			@RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
			@Valid @RequestBody ManualNotificationRequest request,
			HttpServletRequest http) {
		return sends.sendStudent(principal.getUserId(), courseId, studentId, idempotencyKey, request, audit(http));
	}

	private static AuditRequest audit(HttpServletRequest http) {
		return new AuditRequest(http.getHeader("X-Request-Id"), http.getRemoteAddr(), http.getHeader("User-Agent"));
	}
}

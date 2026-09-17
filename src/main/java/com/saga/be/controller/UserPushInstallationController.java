package com.saga.be.controller;

import com.saga.be.auth.AuthErrorCode;
import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.dto.notification.RegisterPushInstallationRequest;
import com.saga.be.exception.AuthException;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.notification.PushInstallationService;
import com.saga.be.workload.Workload;
import com.saga.be.workload.WorkloadClass;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Workload(WorkloadClass.INTERACTIVE_WRITE)
@RequestMapping("/api/users/me/push-installations")
@SecurityRequirement(name = com.saga.be.config.OpenApiConfig.SESSION_COOKIE_SCHEME)
@Tag(
		name = "User push installations",
		description =
				"""
				Authenticated caller's own FCM device registration. STUDENT, LECTURER, and ADMIN. \
				WEB logout should DELETE this installation, then POST /api/auth/logout. Revoke is \
				not required for logout to succeed. Shared/public browsers should revoke push first.
				""")
public class UserPushInstallationController {

	private final PushInstallationService installations;

	public UserPushInstallationController(PushInstallationService installations) {
		this.installations = installations;
	}

	@PutMapping
	@Operation(summary = "Register or rotate my FCM installation")
	public PushInstallationResponse register(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@Valid @RequestBody RegisterPushInstallationRequest body) {
		return installations.register(
				requireUserId(principal), body.firebaseInstallationId(), body.fcmToken(), body.platform());
	}

	@DeleteMapping("/{installationId}")
	@Operation(
			summary = "Revoke one of my FCM installations",
			description =
					"""
					Soft-revokes the caller's installation (active=false, revokedAt=now). \
					Recommended WEB logout: DELETE this resource, then POST /api/auth/logout. \
					Logout does not revoke push by itself.
					""")
	public PushInstallationResponse revoke(
			@Parameter(hidden = true) @AuthenticationPrincipal SagaUserPrincipal principal,
			@PathVariable UUID installationId) {
		return installations.revoke(requireUserId(principal), installationId);
	}

	private static UUID requireUserId(SagaUserPrincipal principal) {
		if (principal == null) {
			throw new AuthException(
					AuthErrorCode.INVALID_CREDENTIALS, HttpStatus.UNAUTHORIZED, "Authentication failed.");
		}
		return principal.getUserId();
	}
}

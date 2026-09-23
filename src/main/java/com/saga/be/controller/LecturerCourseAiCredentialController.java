package com.saga.be.controller;

import com.saga.be.dto.ai.*;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.CourseAiCredentialService;
import com.saga.be.service.ai.CourseAiSettingsService;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/**
 * Course AI settings/credentials management -- section XI. Authorization is deliberately the
 * STRICT assigned-lecturer check ({@link LecturerCourseAuthorization#requireAssignedLecturerStrict}),
 * not the ADMIN-inclusive {@code requireCourse} used elsewhere: an admin is not a generic course
 * credential manager here, and a student can never reach this controller at all (no student route
 * exists into it). No response here, on any path including errors, ever includes the API key.
 */
@RestController @Profile("!test") @RequestMapping("/api/lecturer/courses/{courseId}") @SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseAiCredentialController {
	private final LecturerCourseAuthorization authorization;
	private final CourseAiSettingsService settings;
	private final CourseAiCredentialService credentials;
	private final UserAccountRepository users;
	private final AuditService audit;

	public LecturerCourseAiCredentialController(LecturerCourseAuthorization authorization, CourseAiSettingsService settings, CourseAiCredentialService credentials, UserAccountRepository users, AuditService audit) {
		this.authorization = authorization; this.settings = settings; this.credentials = credentials; this.users = users; this.audit = audit;
	}

	@GetMapping("/ai-settings")
	public CourseAiSettingsResponse getSettings(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		var current = settings.get(courseId);
		return new CourseAiSettingsResponse(current.automationEnabled(), current.allowPlatformFallback());
	}

	@PatchMapping("/ai-settings")
	public CourseAiSettingsResponse updateSettings(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @RequestBody CourseAiSettingsUpdateRequest body, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		var before = settings.get(courseId);
		var updated = settings.update(course, body.automationEnabled(), body.allowPlatformFallback());
		audit.record(actor, null, null, "AI_COURSE_SETTINGS_CHANGED", "CourseAiSettings", courseId,
				Map.of("automationEnabled", before.automationEnabled(), "allowPlatformFallback", before.allowPlatformFallback()),
				Map.of("automationEnabled", updated.automationEnabled(), "allowPlatformFallback", updated.allowPlatformFallback()),
				Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return new CourseAiSettingsResponse(updated.automationEnabled(), updated.allowPlatformFallback());
	}

	@GetMapping("/ai-credentials/{role}")
	public CourseAiCredentialResponse getCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role) {
		Course course = authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		var meta = credentials.safeMetadata(course, role);
		return new CourseAiCredentialResponse(meta.configured(), meta.provider(), role.name(), meta.status() == null ? null : meta.status().name(), meta.lastFour(), meta.updatedAt(), meta.lastSuccessfulUseAt());
	}

	@PutMapping("/ai-credentials/{role}")
	public CourseAiCredentialResponse putCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, @RequestBody CourseAiCredentialPutRequest body, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		if (body.provider() == null || body.provider().isBlank()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "provider is required.");
		boolean wasConfigured = credentials.safeMetadata(course, role).configured();
		var meta = credentials.save(course, role, body.provider(), body.apiKey(), actor);
		audit.record(actor, null, null, wasConfigured ? "AI_COURSE_CREDENTIAL_REPLACED" : "AI_COURSE_CREDENTIAL_CONFIGURED", "CourseAiProviderCredential", courseId,
				Map.of(), Map.of("provider", meta.provider(), "role", role.name(), "lastFour", meta.lastFour(), "status", meta.status().name()),
				Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return new CourseAiCredentialResponse(meta.configured(), meta.provider(), role.name(), meta.status().name(), meta.lastFour(), meta.updatedAt(), meta.lastSuccessfulUseAt());
	}

	@DeleteMapping("/ai-credentials/{role}")
	public ResponseEntity<Void> revokeCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		credentials.revoke(course, role);
		audit.record(actor, null, null, "AI_COURSE_CREDENTIAL_REVOKED", "CourseAiProviderCredential", courseId,
				Map.of(), Map.of("role", role.name()), Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return ResponseEntity.noContent().build();
	}

	private UserAccount actor(SagaUserPrincipal principal) { return users.findById(principal.getUserId()).orElseThrow(); }
}

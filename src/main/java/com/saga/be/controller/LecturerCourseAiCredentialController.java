package com.saga.be.controller;

import com.saga.be.ai.AiProviderBinding;
import com.saga.be.dto.ai.*;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.ai.AiModelCatalog;
import com.saga.be.service.ai.CourseAiCredentialService;
import com.saga.be.service.ai.CourseAiSettingsService;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.List;
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
 * No endpoint here contacts an AI provider: saving a key never verifies it live.
 */
@RestController @Profile("!test") @RequestMapping("/api/lecturer/courses/{courseId}") @SecurityRequirement(name = "SAGA_SESSION")
public class LecturerCourseAiCredentialController {
	static final String FREE_TIER_NOTICE = "Free-tier eligibility is informational only. External providers set and may change free-tier availability, limits and data-use terms at any time; free tiers may use submitted content to improve their models and are not suitable for sensitive production data.";

	private final LecturerCourseAuthorization authorization;
	private final CourseAiSettingsService settings;
	private final CourseAiCredentialService credentials;
	private final AiModelCatalog catalog;
	private final UserAccountRepository users;
	private final AuditService audit;

	public LecturerCourseAiCredentialController(LecturerCourseAuthorization authorization, CourseAiSettingsService settings, CourseAiCredentialService credentials, AiModelCatalog catalog, UserAccountRepository users, AuditService audit) {
		this.authorization = authorization; this.settings = settings; this.credentials = credentials; this.catalog = catalog; this.users = users; this.audit = audit;
	}

	@GetMapping("/ai-provider-catalog")
	public AiProviderCatalogResponse getCatalog(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		List<AiProviderCatalogResponse.Provider> providers = Arrays.stream(AiProvider.values()).map(provider -> new AiProviderCatalogResponse.Provider(provider.name(), displayName(provider),
				catalog.models().stream().filter(m -> m.provider() == provider)
						.map(m -> new AiProviderCatalogResponse.Model(m.provider().name(), m.modelId(), m.displayName(), m.freeTierEligible(), m.supportsStructuredOutput(), m.recommendedForAutomation()))
						.toList())).toList();
		return new AiProviderCatalogResponse(providers, FREE_TIER_NOTICE);
	}

	@GetMapping("/ai-settings")
	public CourseAiSettingsResponse getSettings(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		return response(courseId, settings.get(courseId));
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
		return response(courseId, updated);
	}

	/** Full replace of primary/fallback/secondary bindings, validated against the server-side
	 * catalog. Never touches credentials, automation, or the manual platform-fallback flag. */
	@PutMapping("/ai-settings/bindings")
	public CourseAiSettingsResponse updateBindings(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @RequestBody CourseAiBindingsUpdateRequest body, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		if (body == null) throw new IntegrationException(IntegrationErrorCode.AI_BINDING_INVALID, HttpStatus.BAD_REQUEST, "Request body is required.");
		var before = settings.get(courseId);
		List<CourseAiSettingsService.BindingInput> fallbackInputs = body.fallbackBindings() == null ? List.of() : body.fallbackBindings().stream().map(LecturerCourseAiCredentialController::input).toList();
		var updated = settings.updateBindings(course, input(body.primaryBinding()), Boolean.TRUE.equals(body.fallbackEnabled()), fallbackInputs, input(body.secondaryBinding()));
		audit.record(actor, null, null, "AI_COURSE_SETTINGS_CHANGED", "CourseAiSettings", courseId,
				bindingAudit(before), bindingAudit(updated),
				Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return response(courseId, updated);
	}

	/** Every credential of the course, all roles and providers, metadata only. */
	@GetMapping("/ai-credentials")
	public List<CourseAiCredentialResponse> listCredentials(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId) {
		Course course = authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		return credentials.list(course).stream().map(LecturerCourseAiCredentialController::response).toList();
	}

	@GetMapping("/ai-credentials/{role}/{provider}")
	public CourseAiCredentialResponse getProviderCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, @PathVariable String provider) {
		AiProvider parsed = provider(provider);
		Course course = authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		return response(credentials.safeMetadata(course, role, parsed));
	}

	@PutMapping("/ai-credentials/{role}/{provider}")
	public CourseAiCredentialResponse putProviderCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, @PathVariable String provider, @RequestBody CourseAiCredentialPutRequest body, HttpServletRequest request) {
		AiProvider parsed = provider(provider);
		if (body != null && body.provider() != null && !body.provider().isBlank() && AiProvider.parse(body.provider()).orElse(null) != parsed)
			throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Body provider does not match the path provider.");
		return save(principal, courseId, role, parsed, body == null ? null : body.apiKey(), request);
	}

	@DeleteMapping("/ai-credentials/{role}/{provider}")
	public ResponseEntity<Void> revokeProviderCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, @PathVariable String provider, HttpServletRequest request) {
		return revoke(principal, courseId, role, provider(provider), request);
	}

	/** Legacy single-provider route: reads the OPENAI credential of the role. */
	@GetMapping("/ai-credentials/{role}")
	public CourseAiCredentialResponse getCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role) {
		Course course = authorization.requireAssignedLecturerStrict(actor(principal), courseId);
		return response(credentials.safeMetadata(course, role, AiProvider.OPENAI));
	}

	/** Legacy single-provider route: {@code provider} in the body is required and parsed
	 * case-insensitively (the pre multi-provider client sent {@code "openai"}). */
	@PutMapping("/ai-credentials/{role}")
	public CourseAiCredentialResponse putCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, @RequestBody CourseAiCredentialPutRequest body, HttpServletRequest request) {
		if (body == null || body.provider() == null || body.provider().isBlank()) throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "provider is required.");
		return save(principal, courseId, role, provider(body.provider()), body.apiKey(), request);
	}

	/** Legacy single-provider route: revokes the OPENAI credential of the role. */
	@DeleteMapping("/ai-credentials/{role}")
	public ResponseEntity<Void> revokeCredential(@AuthenticationPrincipal SagaUserPrincipal principal, @PathVariable UUID courseId, @PathVariable AiProviderRole role, HttpServletRequest request) {
		return revoke(principal, courseId, role, AiProvider.OPENAI, request);
	}

	private CourseAiCredentialResponse save(SagaUserPrincipal principal, UUID courseId, AiProviderRole role, AiProvider provider, String apiKey, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		boolean wasConfigured = credentials.safeMetadata(course, role, provider).configured();
		var meta = credentials.save(course, role, provider, apiKey, actor);
		audit.record(actor, null, null, wasConfigured ? "AI_COURSE_CREDENTIAL_REPLACED" : "AI_COURSE_CREDENTIAL_CONFIGURED", "CourseAiProviderCredential", courseId,
				Map.of(), Map.of("provider", provider.name(), "role", role.name(), "lastFour", meta.lastFour(), "status", meta.status().name()),
				Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return response(meta);
	}

	private ResponseEntity<Void> revoke(SagaUserPrincipal principal, UUID courseId, AiProviderRole role, AiProvider provider, HttpServletRequest request) {
		UserAccount actor = actor(principal);
		Course course = authorization.requireAssignedLecturerStrict(actor, courseId);
		credentials.revoke(course, role, provider);
		audit.record(actor, null, null, "AI_COURSE_CREDENTIAL_REVOKED", "CourseAiProviderCredential", courseId,
				Map.of(), Map.of("provider", provider.name(), "role", role.name()), Map.of("courseId", courseId), AuditSource.API, null, request.getRemoteAddr(), request.getHeader("User-Agent"));
		return ResponseEntity.noContent().build();
	}

	private CourseAiSettingsResponse response(UUID courseId, CourseAiSettingsService.Settings current) {
		List<AiProviderBindingDto> chain = settings.storedFallbackBindings(courseId).stream().map(LecturerCourseAiCredentialController::dto).toList();
		return new CourseAiSettingsResponse(current.automationEnabled(), current.allowPlatformFallback(), dto(current.primaryBinding()), current.fallbackEnabled(), chain, dto(current.secondaryBinding()));
	}

	private static CourseAiCredentialResponse response(CourseAiCredentialService.SafeMetadata meta) {
		return new CourseAiCredentialResponse(meta.configured(), meta.provider() == null ? null : meta.provider().name(), meta.role().name(), meta.status() == null ? null : meta.status().name(), meta.lastFour(), meta.createdAt(), meta.updatedAt(), meta.lastSuccessfulUseAt());
	}

	private static AiProvider provider(String raw) {
		return AiProvider.parse(raw).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_PROVIDER_NOT_SUPPORTED, HttpStatus.BAD_REQUEST, "Unsupported AI provider."));
	}

	private static CourseAiSettingsService.BindingInput input(AiProviderBindingDto dto) {
		return dto == null ? null : new CourseAiSettingsService.BindingInput(dto.provider(), dto.modelId());
	}

	private static AiProviderBindingDto dto(AiProviderBinding binding) {
		return binding == null ? null : new AiProviderBindingDto(binding.provider().name(), binding.modelId());
	}

	private static Map<String, Object> bindingAudit(CourseAiSettingsService.Settings s) {
		return Map.of("primaryBinding", s.primaryBinding() == null ? "LEGACY" : s.primaryBinding().identity(),
				"fallbackEnabled", s.fallbackEnabled(),
				"fallbackBindings", s.fallbackBindings().stream().map(AiProviderBinding::identity).toList(),
				"secondaryBinding", s.secondaryBinding() == null ? "LEGACY" : s.secondaryBinding().identity());
	}

	private static String displayName(AiProvider provider) {
		return switch (provider) { case OPENAI -> "OpenAI"; case GEMINI -> "Google Gemini"; case OPENROUTER -> "OpenRouter"; };
	}

	private UserAccount actor(SagaUserPrincipal principal) { return users.findById(principal.getUserId()).orElseThrow(); }
}

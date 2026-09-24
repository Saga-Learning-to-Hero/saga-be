package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.CourseAiProviderCredentialRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single authoritative place that decides where an AI credential comes from (section VIII).
 * {@link #resolve} is a bounded, decrypt-free metadata lookup safe to call from a submission path
 * (never a GET/read path); it never returns raw key material. {@link #buildEnvelope} is the only
 * method that ever decrypts a course credential, and it must only be called immediately before
 * dispatching a provider HTTP call (see {@code AiAnalysisExecutionService}) -- never at submission
 * time, since submission and execution are decoupled by an async queue and the raw key must not
 * live any longer than the single HTTP call it serves.
 */
@Service @Profile("!test")
public class AiCredentialResolver {
	private static final List<AiAnalysisType> MANUAL_FALLBACK_ELIGIBLE_TYPES = List.of(AiAnalysisType.ACADEMIC_CLASSIFICATION, AiAnalysisType.PROGRESS_NARRATIVE);
	/** Safe run/decision failure code: a COURSE credential reached a provider that cannot forward it. */
	public static final String COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER = "AI_COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER";

	private final CourseAiProviderCredentialRepository credentials;
	private final CourseAiSettingsService settings;
	private final CourseAiCredentialService credentialService;
	private final AiCredentialTransportCipher transport;
	private final List<AiModelProvider> providers;
	private final AiModelCatalog catalog;

	public AiCredentialResolver(CourseAiProviderCredentialRepository credentials, CourseAiSettingsService settings, CourseAiCredentialService credentialService, AiCredentialTransportCipher transport, List<AiModelProvider> providers, AiModelCatalog catalog) {
		this.credentials = credentials; this.settings = settings; this.credentialService = credentialService; this.transport = transport; this.providers = providers; this.catalog = catalog;
	}

	public enum Outcome { COURSE, PLATFORM, UNAVAILABLE }

	/** {@code binding} is the course's server-validated provider/model for this role, or null for
	 * the legacy (OpenAI credential, platform model) behaviour and for PLATFORM/UNAVAILABLE. */
	public record Resolution(Outcome outcome, UUID courseCredentialId, String credentialFingerprint, AiProviderBinding binding) {
		public static final Resolution UNAVAILABLE = new Resolution(Outcome.UNAVAILABLE, null, null, null);
		public static final Resolution PLATFORM = new Resolution(Outcome.PLATFORM, null, null, null);
		public Resolution(Outcome outcome, UUID courseCredentialId, String credentialFingerprint) { this(outcome, courseCredentialId, credentialFingerprint, null); }

		/** Credential identity for run idempotency. Identical to the bare fingerprint for legacy
		 * (unbound) resolutions, so pre multi-provider idempotency keys are unchanged; a binding
		 * change (other provider/model) yields a new identity and therefore a fresh run. */
		public String identityFingerprint() {
			if (binding == null) return credentialFingerprint;
			return (credentialFingerprint == null ? "" : credentialFingerprint) + "@" + binding.identity();
		}
	}

	/** A usable (not INVALID/REVOKED) course credential, metadata only -- never key material. */
	public record CourseCredentialRef(UUID id, String fingerprint) {}

	@Transactional(readOnly = true)
	public Resolution resolve(UUID courseId, AiAnalysisType analysisType, AiProviderRole providerRole, AiInvocationOrigin origin) {
		CourseAiSettingsService.Settings courseSettings = settings.get(courseId);
		AiProviderBinding storedBinding = providerRole == AiProviderRole.SECONDARY ? courseSettings.secondaryBinding() : courseSettings.primaryBinding();
		AiProviderBinding binding = storedBinding == null ? null : catalog.requireRuntimeCompatible(storedBinding);
		AiProvider provider = binding == null ? AiProvider.OPENAI : binding.provider();
		var course = usableCourseCredential(courseId, providerRole, provider).orElse(null);
		if (course != null) return new Resolution(Outcome.COURSE, course.id(), course.fingerprint(), binding);

		// A missing/INVALID/REVOKED credential for the bound provider is never replaced by another
		// provider's credential here: course fallback only reacts to runtime quota/rate/timeout/
		// transient failures of a usable credential (see AiAnalysisExecutionService).
		if (providerRole == AiProviderRole.SECONDARY) return Resolution.UNAVAILABLE; // never platform, ever (section II/VIII)
		if (origin != AiInvocationOrigin.AUTOMATION && MANUAL_FALLBACK_ELIGIBLE_TYPES.contains(analysisType)) {
			boolean fallbackAllowed = courseSettings.allowPlatformFallback();
			boolean platformConfigured = providers.stream().anyMatch(p -> p.role() == providerRole);
			if (fallbackAllowed && platformConfigured) return Resolution.PLATFORM;
		}
		return Resolution.UNAVAILABLE;
	}

	@Transactional(readOnly = true)
	public java.util.Optional<CourseCredentialRef> usableCourseCredential(UUID courseId, AiProviderRole role, AiProvider provider) {
		return credentials.findByCourse_IdAndProviderRoleAndProvider(courseId, role, provider)
				.filter(c -> c.getStatus() != AiCredentialStatus.INVALID && c.getStatus() != AiCredentialStatus.REVOKED)
				.map(c -> new CourseCredentialRef(c.getId(), c.getFingerprint()));
	}

	/** Course PRIMARY fallback chain as currently configured (empty unless enabled); read at
	 * execution time so a lecturer's change applies to the next run, never mid-run. */
	public List<AiProviderBinding> primaryFallbackChain(UUID courseId) {
		CourseAiSettingsService.Settings courseSettings = settings.get(courseId);
		return courseSettings.fallbackEnabled()
				? courseSettings.fallbackBindings().stream().map(catalog::requireRuntimeCompatible).toList()
				: List.of();
	}

	/** Stamps the course binding onto a new decision row (provider + model actually requested). */
	public static void applyBinding(AiAnalysisProviderDecision decision, Resolution resolution) {
		if (resolution == null || resolution.binding() == null) return;
		decision.setAiProvider(resolution.binding().provider());
		decision.setModelId(resolution.binding().modelId());
	}

	/** Decrypt-and-reseal for one dispatch only. Never call outside the immediate pre-HTTP-call
	 * path; the returned envelope must never be persisted or logged. */
	public AiCredentialEnvelope buildEnvelope(UUID courseCredentialId, AiProviderRole role, UUID courseId) {
		if (courseCredentialId == null || courseId == null) throw new AiCredentialCryptoException("AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING");
		var decrypted = credentialService.decryptActive(courseCredentialId, courseId, role)
				.orElseThrow(() -> new AiCredentialCryptoException("AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING"));
		return transport.seal(decrypted.rawApiKey());
	}

	public void markSuccessful(UUID courseCredentialId) { credentialService.markSuccessful(courseCredentialId); }
	public void markInvalid(UUID courseCredentialId) { credentialService.markInvalid(courseCredentialId); }
	public void markDegraded(UUID courseCredentialId) { credentialService.markDegraded(courseCredentialId); }
}

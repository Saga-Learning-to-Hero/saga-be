package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
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

	public AiCredentialResolver(CourseAiProviderCredentialRepository credentials, CourseAiSettingsService settings, CourseAiCredentialService credentialService, AiCredentialTransportCipher transport, List<AiModelProvider> providers) {
		this.credentials = credentials; this.settings = settings; this.credentialService = credentialService; this.transport = transport; this.providers = providers;
	}

	public enum Outcome { COURSE, PLATFORM, UNAVAILABLE }

	public record Resolution(Outcome outcome, UUID courseCredentialId, String credentialFingerprint) {
		public static final Resolution UNAVAILABLE = new Resolution(Outcome.UNAVAILABLE, null, null);
		public static final Resolution PLATFORM = new Resolution(Outcome.PLATFORM, null, null);
	}

	@Transactional(readOnly = true)
	public Resolution resolve(UUID courseId, AiAnalysisType analysisType, AiProviderRole providerRole, AiInvocationOrigin origin) {
		var course = credentials.findByCourse_IdAndProviderRole(courseId, providerRole)
				.filter(c -> c.getStatus() != AiCredentialStatus.INVALID && c.getStatus() != AiCredentialStatus.REVOKED)
				.orElse(null);
		if (course != null) return new Resolution(Outcome.COURSE, course.getId(), course.getFingerprint());

		if (providerRole == AiProviderRole.SECONDARY) return Resolution.UNAVAILABLE; // never platform, ever (section II/VIII)
		if (origin != AiInvocationOrigin.AUTOMATION && MANUAL_FALLBACK_ELIGIBLE_TYPES.contains(analysisType)) {
			boolean fallbackAllowed = settings.get(courseId).allowPlatformFallback();
			boolean platformConfigured = providers.stream().anyMatch(p -> p.role() == providerRole);
			if (fallbackAllowed && platformConfigured) return Resolution.PLATFORM;
		}
		return Resolution.UNAVAILABLE;
	}

	/** Decrypt-and-reseal for one dispatch only. Never call outside the immediate pre-HTTP-call
	 * path; the returned envelope must never be persisted or logged. */
	public AiCredentialEnvelope buildEnvelope(UUID courseCredentialId, AiProviderRole role, UUID courseId) {
		var decrypted = credentialService.decryptActive(courseId, role)
				.filter(d -> d.credentialId().equals(courseCredentialId))
				.orElseThrow(() -> new AiCredentialCryptoException("AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING"));
		return transport.seal(decrypted.rawApiKey());
	}

	public void markSuccessful(UUID courseCredentialId) { credentialService.markSuccessful(courseCredentialId); }
	public void markInvalid(UUID courseCredentialId) { credentialService.markInvalid(courseCredentialId); }
	public void markDegraded(UUID courseCredentialId) { credentialService.markDegraded(courseCredentialId); }
}

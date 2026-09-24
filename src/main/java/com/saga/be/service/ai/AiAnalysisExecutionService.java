package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.beans.factory.annotation.Autowired;

@Service
@Profile("!test")
public class AiAnalysisExecutionService {
	private static final Logger log = LoggerFactory.getLogger(AiAnalysisExecutionService.class);
	/** Course fallback may continue only after these: capacity/availability, never the credential
	 * itself, the model/capability, the result shape, or SAGA's own configuration. */
	static final Set<String> FALLBACK_ELIGIBLE_CODES = Set.of("AI_PROVIDER_QUOTA_EXHAUSTED", "AI_PROVIDER_RATE_LIMITED", "AI_PROVIDER_TIMEOUT", "AI_PROVIDER_UNAVAILABLE");
	static final Set<String> CREDENTIAL_INVALID_CODES = Set.of("AI_PROVIDER_AUTH_FAILED");
	static final Set<String> CREDENTIAL_DEGRADED_CODES = Set.of("AI_PROVIDER_QUOTA_EXHAUSTED", "AI_PROVIDER_RATE_LIMITED");
	private final AiAnalysisStateService state; private final List<AiModelProvider> providers; private final AiStructuredResultValidator validator; private final ObjectMapper mapper; private final AiAcademicResultValidator academicValidator; private final AiAcademicProposalFinalizationService academicFinalizer; private final AiTaskIntelligenceResultValidator taskValidator; private final AiTaskIntelligenceFinalizationService taskFinalizer; private final AiRiskAnalysisResultValidator riskValidator; private final AiRiskAnalysisFinalizationService riskFinalizer; private final AiProgressNarrativeResultValidator progressValidator; private final AiProgressNarrativeFinalizationService progressFinalizer; private final AiSecondaryBrainService secondaryBrain; private final AiAdjudicationService adjudication; private final AiCredentialResolver credentialResolver;
	@Autowired public AiAnalysisExecutionService(AiAnalysisStateService state, List<AiModelProvider> providers, AiStructuredResultValidator validator, ObjectMapper mapper, AiAcademicResultValidator academicValidator, AiAcademicProposalFinalizationService academicFinalizer, AiTaskIntelligenceResultValidator taskValidator, AiTaskIntelligenceFinalizationService taskFinalizer, AiRiskAnalysisResultValidator riskValidator, AiRiskAnalysisFinalizationService riskFinalizer, AiProgressNarrativeResultValidator progressValidator, AiProgressNarrativeFinalizationService progressFinalizer, AiSecondaryBrainService secondaryBrain, AiAdjudicationService adjudication, AiCredentialResolver credentialResolver) { this.state = state; this.providers = providers; this.validator = validator; this.mapper = mapper; this.academicValidator = academicValidator; this.academicFinalizer = academicFinalizer; this.taskValidator = taskValidator; this.taskFinalizer = taskFinalizer; this.riskValidator = riskValidator; this.riskFinalizer = riskFinalizer; this.progressValidator = progressValidator; this.progressFinalizer = progressFinalizer; this.secondaryBrain = secondaryBrain; this.adjudication = adjudication; this.credentialResolver = credentialResolver; }
	public AiAnalysisExecutionService(AiAnalysisStateService state, List<AiModelProvider> providers, AiStructuredResultValidator validator, ObjectMapper mapper) { this(state, providers, validator, mapper, new AiAcademicResultValidator(mapper), null, new AiTaskIntelligenceResultValidator(), null, new AiRiskAnalysisResultValidator(), null, new AiProgressNarrativeResultValidator(), null, null, null, null); }

	/**
	 * Each state mutation is transactional in AiAnalysisStateService; provider invocation is
	 * deliberately outside any DB transaction. Adjudication runs in a {@code finally} block so it
	 * is attempted on EVERY exit path (provider missing, invalid result, exception, or success) —
	 * not just the PRIMARY-success path — otherwise a PRIMARY failure would silently suppress a
	 * SECONDARY_ONLY outcome even though SECONDARY already completed.
	 */
	public void execute(UUID runId) {
		if (!state.claim(runId)) return;
		long started = System.nanoTime();
		try {
			AiAnalysisStateService.ExecutionInput input = state.loadExecution(runId);
			// Independent of whatever happens to PRIMARY below: at most once, never retried, never
			// a fallback to PRIMARY's provider. A no-op when the secondary brain flag is off.
			if (secondaryBrain != null) secondaryBrain.maybeRun(input);
			AiModelProvider provider = providers.stream().filter(p -> p.role() == input.decision().getProviderRole() && p.providerKey().equals(input.decision().getProviderKey()) && p.providerConfigHash().equals(input.decision().getProviderConfigHash())).findFirst().orElse(null);
			if (provider == null) { state.fail(runId, "AI_ANALYSIS_PROVIDER_FAILED", false); log.warn("ai analysis provider unavailable runId={}", runId); return; }
			List<AiAnalysisRequest.AiEvidenceInput> evidence = input.evidence().stream().map(e -> new AiAnalysisRequest.AiEvidenceInput(e.getId(), e.getEvidenceType().name(), e.getSourceRef(), e.getPayloadJson(), e.getMetadataJson())).toList();
			AiAnalysisType type = input.run().getAnalysisType() == null ? AiAnalysisType.COMMIT_INTELLIGENCE : input.run().getAnalysisType();
			String contract = switch (type) {
				case ACADEMIC_CLASSIFICATION -> AiSystemContract.ACADEMIC_CLASSIFICATION_UNTRUSTED_DATA;
				case TASK_INTELLIGENCE -> AiSystemContract.TASK_INTELLIGENCE_UNTRUSTED_DATA;
				case RISK_ANALYSIS -> AiSystemContract.RISK_ANALYSIS_UNTRUSTED_DATA;
				case PROGRESS_NARRATIVE -> AiSystemContract.PROGRESS_NARRATIVE_UNTRUSTED_DATA;
				default -> AiSystemContract.UNTRUSTED_ARTIFACT_DATA;
			};
			AiCredentialSource credentialSource = input.decision().getCredentialSource() == null ? AiCredentialSource.PLATFORM : input.decision().getCredentialSource();
			AiProviderResponse response;
			if (credentialSource == AiCredentialSource.COURSE) {
				if (credentialResolver == null) throw new AiProviderException("AI_CREDENTIAL_ENVELOPE_UNAVAILABLE");
				// Checked before any decrypt or dispatch: a provider that ignores the envelope would
				// answer with the platform key and the course credential would be wrongly marked ACTIVE.
				if (!provider.supportsCourseCredential()) throw new AiProviderException(AiCredentialResolver.COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER);
				response = executeCourse(runId, input, provider, type, contract, evidence);
			} else {
				response = provider.analyze(new AiAnalysisRequest(runId, input.decision().getProviderRole(), type, input.run().getPromptVersion(), input.run().getTaxonomyVersion(), contract, evidence, credentialSource, null));
			}
			Set<UUID> evidenceIds = input.evidence().stream().map(e -> e.getId()).collect(java.util.stream.Collectors.toSet());
			Set<UUID> taskIds = taskIds(input.evidence()); boolean completeCoverage = completeCoverage(input.evidence());
			Optional<String> invalidReason = switch (type) {
				case ACADEMIC_CLASSIFICATION -> (response == null || !(response.result() instanceof AiAcademicClassificationResult academic)) ? Optional.of("MISSING_RESULT") : academicValidator.invalidReason(academic, evidence);
				case TASK_INTELLIGENCE -> (response == null || !(response.result() instanceof AiTaskIntelligenceResult task)) ? Optional.of("MISSING_RESULT") : taskValidator.invalidReason(task, evidenceIds);
				case RISK_ANALYSIS -> (response == null || !(response.result() instanceof AiRiskAnalysisResult risk)) ? Optional.of("MISSING_RESULT") : riskValidator.invalidReason(risk, evidenceIds);
				case PROGRESS_NARRATIVE -> (response == null || !(response.result() instanceof AiProgressNarrativeResult progress)) ? Optional.of("MISSING_RESULT") : progressValidator.invalidReason(progress, evidenceIds);
				default -> (response == null || !(response.result() instanceof AiStructuredResult structured)) ? Optional.of("MISSING_RESULT") : validator.invalidReason(structured, evidenceIds, taskIds, completeCoverage);
			};
			if (invalidReason.isPresent()) { state.fail(runId, "AI_ANALYSIS_RESULT_INVALID", false); log.warn("ai analysis invalid result runId={} type={} reason={}", runId, type, invalidReason.get()); return; }
			String resultJson = mapper.writeValueAsString(response.result());
			boolean finalized;
			switch (type) {
				case ACADEMIC_CLASSIFICATION -> {
					if (academicFinalizer == null) throw new IllegalStateException("Academic finalizer is required");
					finalized = academicFinalizer.finalize(input, response, resultJson);
				}
				case TASK_INTELLIGENCE -> {
					if (taskFinalizer == null) throw new IllegalStateException("Task intelligence finalizer is required");
					finalized = taskFinalizer.finalize(input, response, resultJson);
				}
				case RISK_ANALYSIS -> {
					if (riskFinalizer == null) throw new IllegalStateException("Risk analysis finalizer is required");
					finalized = riskFinalizer.finalize(input, response, resultJson);
				}
				case PROGRESS_NARRATIVE -> {
					if (progressFinalizer == null) throw new IllegalStateException("Progress narrative finalizer is required");
					finalized = progressFinalizer.finalize(input, response, resultJson, factsJson(input.evidence()));
				}
				default -> finalized = state.complete(runId, resultJson, true, response.latencyMs() == null ? (System.nanoTime() - started) / 1_000_000L : response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson());
			}
			if (finalized) log.info("ai analysis completed runId={} type={} provider={} model={} durationMs={}", runId, type, provider.providerKey(), provider.modelId(), (System.nanoTime() - started) / 1_000_000L);
			else log.info("ai analysis late result discarded runId={} type={} provider={}", runId, type, provider.providerKey());
		} catch (Exception ex) {
			String code = ex instanceof AiProviderException provider ? provider.safeCode()
					: ex instanceof AiCredentialCryptoException crypto ? crypto.safeCode()
					: "AI_ANALYSIS_PROVIDER_FAILED";
			// Course credential status was already updated per attempt inside executeCourse.
			state.fail(runId, code, false);
			log.warn("ai analysis failed runId={} type={} code={}", runId, ex.getClass().getSimpleName(), code);
		}
		finally { if (adjudication != null) adjudication.adjudicate(runId); }
	}
	/**
	 * Runs a COURSE-credential PRIMARY inference. A legacy decision (no provider binding) is exactly
	 * one attempt, as before. A bound decision may continue down the course's own fallback chain,
	 * but only after a quota/rate-limit/timeout/transient-unavailability failure, at most once per
	 * binding, and only with the course's own PRIMARY credential for that provider -- never a
	 * platform credential, never the SECONDARY role. Each credential's status reflects only its
	 * own attempt: auth failure -> INVALID, quota/rate -> DEGRADED, success -> ACTIVE, anything
	 * else (transient, SAGA crypto/transport, invalid result) -> unchanged.
	 */
	private AiProviderResponse executeCourse(UUID runId, AiAnalysisStateService.ExecutionInput input, AiModelProvider provider, AiAnalysisType type, String contract, List<AiAnalysisRequest.AiEvidenceInput> evidence) throws Exception {
		var decision = input.decision();
		AiProviderRole role = decision.getProviderRole();
		UUID courseId = input.run().resolveCourseId();
		AiProviderBinding primary = decision.getAiProvider() == null ? null : new AiProviderBinding(decision.getAiProvider(), decision.getModelId());
		List<AiProviderBinding> chain = primary == null ? List.of() : credentialResolver.primaryFallbackChain(courseId);
		List<Map<String, String>> attempts = new ArrayList<>();
		Set<AiProviderBinding> tried = new HashSet<>();
		Exception lastFailure = null; AiProviderBinding lastBinding = primary; AiCredentialResolver.CourseCredentialRef lastCredential = null;
		for (int index = 0; index <= chain.size(); index++) {
			AiProviderBinding binding = index == 0 ? primary : chain.get(index - 1);
			if (index > 0 && !tried.add(binding)) continue;
			if (index == 0 && primary != null) tried.add(primary);
			AiCredentialResolver.CourseCredentialRef credential = index == 0
					? new AiCredentialResolver.CourseCredentialRef(decision.getCourseCredentialId(), decision.getCredentialFingerprint())
					: credentialResolver.usableCourseCredential(courseId, AiProviderRole.PRIMARY, binding.provider()).orElse(null);
			if (credential == null) { attempts.add(attempt(binding, "SKIPPED_NO_CREDENTIAL")); continue; }
			lastBinding = binding; lastCredential = credential;
			try {
				// Decrypt-and-reseal happens exactly here, right before dispatch -- never earlier,
				// never cached -- so the raw key exists in memory only for this one HTTP call.
				AiCredentialEnvelope envelope = credentialResolver.buildEnvelope(credential.id(), role, courseId);
				AiProviderResponse response = provider.analyze(new AiAnalysisRequest(runId, role, type, input.run().getPromptVersion(), input.run().getTaxonomyVersion(), contract, evidence, AiCredentialSource.COURSE, envelope, binding));
				credentialResolver.markSuccessful(credential.id());
				attempts.add(attempt(binding, "SUCCEEDED"));
				recordProvenance(runId, primary, binding, credential, attempts);
				return response;
			} catch (AiProviderException ex) {
				String code = ex.safeCode();
				if (CREDENTIAL_INVALID_CODES.contains(code)) credentialResolver.markInvalid(credential.id());
				else if (CREDENTIAL_DEGRADED_CODES.contains(code)) credentialResolver.markDegraded(credential.id());
				attempts.add(attempt(binding, code));
				lastFailure = ex;
				if (primary == null || !FALLBACK_ELIGIBLE_CODES.contains(code)) break;
				if (index < chain.size()) log.info("ai analysis course fallback runId={} fromProvider={} fromModel={} code={}", runId, binding.provider(), binding.modelId(), code);
			} catch (AiCredentialCryptoException ex) {
				// A SAGA-side key/transport problem: never the credential's fault, never a fallback.
				attempts.add(attempt(binding, ex.safeCode()));
				lastFailure = ex;
				break;
			}
		}
		recordProvenance(runId, primary, lastBinding, lastCredential, attempts);
		throw lastFailure != null ? lastFailure : new AiProviderException("AI_CREDENTIAL_UNAVAILABLE");
	}

	private void recordProvenance(UUID runId, AiProviderBinding primary, AiProviderBinding used, AiCredentialResolver.CourseCredentialRef credential, List<Map<String, String>> attempts) throws Exception {
		if (primary == null || used == null || credential == null) return; // legacy decision: shape unchanged
		state.recordPrimaryProvenance(runId, used.provider(), used.modelId(), credential.id(), credential.fingerprint(), mapper.writeValueAsString(attempts));
	}

	private static Map<String, String> attempt(AiProviderBinding binding, String outcome) {
		Map<String, String> row = new LinkedHashMap<>();
		row.put("provider", binding == null ? AiProvider.OPENAI.name() : binding.provider().name());
		row.put("modelId", binding == null ? null : binding.modelId());
		row.put("outcome", outcome);
		return row;
	}

	private Set<UUID> taskIds(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { Set<UUID> ids = new HashSet<>(); for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.TASK_FIELD) try { String id = mapper.readTree(row.getPayloadJson()).path("taskId").asText(null); if (id != null) ids.add(UUID.fromString(id)); } catch (Exception ignored) {} return ids; }
	private boolean completeCoverage(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.CHANGED_FILE_MANIFEST) try { return "COMPLETE".equals(mapper.readTree(row.getPayloadJson()).path("coverage").asText()); } catch (Exception ignored) { return false; } return false; }
	private static String factsJson(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { for (var row : rows) if (row.getSourceRef() != null && row.getSourceRef().startsWith("progress-facts:")) return row.getPayloadJson(); throw new IllegalStateException("Progress narrative run is missing its progress-facts evidence row"); }
}

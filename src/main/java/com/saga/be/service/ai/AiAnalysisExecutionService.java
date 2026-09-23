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
		UUID courseCredentialIdForFailure = null;
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
			AiCredentialEnvelope envelope = null;
			if (credentialSource == AiCredentialSource.COURSE) {
				if (credentialResolver == null) throw new AiProviderException("AI_CREDENTIAL_ENVELOPE_UNAVAILABLE");
				UUID courseCredentialId = input.decision().getCourseCredentialId();
				courseCredentialIdForFailure = courseCredentialId;
				// Decrypt-and-reseal happens exactly here, right before dispatch -- never earlier,
				// never cached -- so the raw key exists in memory only for this one HTTP call.
				envelope = credentialResolver.buildEnvelope(courseCredentialId, input.decision().getProviderRole(), input.run().resolveCourseId());
			}
			AiProviderResponse response = provider.analyze(new AiAnalysisRequest(runId, input.decision().getProviderRole(), type, input.run().getPromptVersion(), input.run().getTaxonomyVersion(), contract, evidence, credentialSource, envelope));
			if (credentialSource == AiCredentialSource.COURSE && credentialResolver != null) credentialResolver.markSuccessful(courseCredentialIdForFailure);
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
			String code = ex instanceof AiProviderException provider ? provider.safeCode() : "AI_ANALYSIS_PROVIDER_FAILED";
			// A quota/rate-limit or transient failure never proves the key itself is wrong, so it
			// must not invalidate/revoke it (section X) -- only a genuine auth failure does.
			if (courseCredentialIdForFailure != null && credentialResolver != null) {
				if ("AI_PROVIDER_AUTH_FAILED".equals(code)) credentialResolver.markInvalid(courseCredentialIdForFailure);
				else if ("AI_PROVIDER_RATE_LIMITED".equals(code)) credentialResolver.markDegraded(courseCredentialIdForFailure);
			}
			state.fail(runId, code, false);
			log.warn("ai analysis failed runId={} type={} code={}", runId, ex.getClass().getSimpleName(), code);
		}
		finally { if (adjudication != null) adjudication.adjudicate(runId); }
	}
	private Set<UUID> taskIds(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { Set<UUID> ids = new HashSet<>(); for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.TASK_FIELD) try { String id = mapper.readTree(row.getPayloadJson()).path("taskId").asText(null); if (id != null) ids.add(UUID.fromString(id)); } catch (Exception ignored) {} return ids; }
	private boolean completeCoverage(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.CHANGED_FILE_MANIFEST) try { return "COMPLETE".equals(mapper.readTree(row.getPayloadJson()).path("coverage").asText()); } catch (Exception ignored) { return false; } return false; }
	private static String factsJson(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { for (var row : rows) if (row.getSourceRef() != null && row.getSourceRef().startsWith("progress-facts:")) return row.getPayloadJson(); throw new IllegalStateException("Progress narrative run is missing its progress-facts evidence row"); }
}

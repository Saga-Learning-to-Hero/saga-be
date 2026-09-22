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
	private final AiAnalysisStateService state; private final List<AiModelProvider> providers; private final AiStructuredResultValidator validator; private final ObjectMapper mapper; private final AiAcademicResultValidator academicValidator; private final AiAcademicProposalFinalizationService academicFinalizer;
	@Autowired public AiAnalysisExecutionService(AiAnalysisStateService state, List<AiModelProvider> providers, AiStructuredResultValidator validator, ObjectMapper mapper, AiAcademicResultValidator academicValidator, AiAcademicProposalFinalizationService academicFinalizer) { this.state = state; this.providers = providers; this.validator = validator; this.mapper = mapper; this.academicValidator=academicValidator;this.academicFinalizer=academicFinalizer; }
	public AiAnalysisExecutionService(AiAnalysisStateService state, List<AiModelProvider> providers, AiStructuredResultValidator validator, ObjectMapper mapper) { this(state,providers,validator,mapper,new AiAcademicResultValidator(mapper),null); }
	/** Each state mutation is transactional in AiAnalysisStateService; provider invocation is deliberately outside any DB transaction. */
	public void execute(UUID runId) {
		if (!state.claim(runId)) return;
		long started = System.nanoTime();
		try {
			AiAnalysisStateService.ExecutionInput input = state.loadExecution(runId);
			AiModelProvider provider = providers.stream().filter(p -> p.role() == input.decision().getProviderRole() && p.providerKey().equals(input.decision().getProviderKey()) && p.providerConfigHash().equals(input.decision().getProviderConfigHash())).findFirst().orElse(null);
			if (provider == null) { state.fail(runId, "AI_ANALYSIS_PROVIDER_FAILED", false); log.warn("ai analysis provider unavailable runId={}", runId); return; }
			List<AiAnalysisRequest.AiEvidenceInput> evidence = input.evidence().stream().map(e -> new AiAnalysisRequest.AiEvidenceInput(e.getId(), e.getEvidenceType().name(), e.getSourceRef(), e.getPayloadJson(), e.getMetadataJson())).toList();
			AiAnalysisType type=input.run().getAnalysisType()==null?AiAnalysisType.COMMIT_INTELLIGENCE:input.run().getAnalysisType(); String contract=type==AiAnalysisType.ACADEMIC_CLASSIFICATION?AiSystemContract.ACADEMIC_CLASSIFICATION_UNTRUSTED_DATA:AiSystemContract.UNTRUSTED_ARTIFACT_DATA;
			AiProviderResponse response = provider.analyze(new AiAnalysisRequest(runId, input.decision().getProviderRole(), type, input.run().getPromptVersion(), input.run().getTaxonomyVersion(), contract, evidence));
			Set<UUID> evidenceIds = input.evidence().stream().map(e -> e.getId()).collect(java.util.stream.Collectors.toSet());
			Set<UUID> taskIds = taskIds(input.evidence()); boolean completeCoverage = completeCoverage(input.evidence());
			boolean invalid=response==null || (type==AiAnalysisType.ACADEMIC_CLASSIFICATION ? !(response.result() instanceof AiAcademicClassificationResult academic) || academicValidator.invalidReason(academic,evidence).isPresent() : !(response.result() instanceof AiStructuredResult structured) || validator.invalidReason(structured, evidenceIds, taskIds, completeCoverage).isPresent());
			if (invalid) { state.fail(runId, "AI_ANALYSIS_RESULT_INVALID", false); log.warn("ai analysis invalid result runId={}", runId); return; }
			if(type==AiAnalysisType.ACADEMIC_CLASSIFICATION){if(academicFinalizer==null)throw new IllegalStateException("Academic finalizer is required");boolean finalized=academicFinalizer.finalize(input,response,mapper.writeValueAsString(response.result()));if(finalized)log.info("academic analysis completed runId={} provider={}",runId,provider.providerKey());else log.info("late academic result discarded runId={} provider={}",runId,provider.providerKey());return;}
			boolean completed = state.complete(runId, mapper.writeValueAsString(response.result()), true, response.latencyMs() == null ? (System.nanoTime() - started) / 1_000_000L : response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson());
			if (completed) log.info("ai analysis completed runId={} provider={} model={} durationMs={}", runId, provider.providerKey(), provider.modelId(), (System.nanoTime() - started) / 1_000_000L);
			else log.info("ai analysis late result discarded runId={} provider={}", runId, provider.providerKey());
		} catch (Exception ex) { String code = ex instanceof AiProviderException provider ? provider.safeCode() : "AI_ANALYSIS_PROVIDER_FAILED"; state.fail(runId, code, false); log.warn("ai analysis failed runId={} type={} code={}", runId, ex.getClass().getSimpleName(), code); }
	}
	private Set<UUID> taskIds(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { Set<UUID> ids = new HashSet<>(); for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.TASK_FIELD) try { String id = mapper.readTree(row.getPayloadJson()).path("taskId").asText(null); if (id != null) ids.add(UUID.fromString(id)); } catch (Exception ignored) {} return ids; }
	private boolean completeCoverage(List<com.saga.be.entity.ai.AiAnalysisEvidence> rows) { for (var row : rows) if (row.getEvidenceType() == AiEvidenceType.CHANGED_FILE_MANIFEST) try { return "COMPLETE".equals(mapper.readTree(row.getPayloadJson()).path("coverage").asText()); } catch (Exception ignored) { return false; } return false; }
}

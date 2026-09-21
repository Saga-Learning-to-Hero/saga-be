package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;

@Service
@Profile("!test")
public class AiAnalysisExecutionService {
	private static final Logger log = LoggerFactory.getLogger(AiAnalysisExecutionService.class);
	private final AiAnalysisStateService state; private final List<AiModelProvider> providers; private final AiStructuredResultValidator validator; private final ObjectMapper mapper;
	public AiAnalysisExecutionService(AiAnalysisStateService state, List<AiModelProvider> providers, AiStructuredResultValidator validator, ObjectMapper mapper) { this.state = state; this.providers = providers; this.validator = validator; this.mapper = mapper; }
	/** Each state mutation is transactional in AiAnalysisStateService; provider invocation is deliberately outside any DB transaction. */
	public void execute(UUID runId) {
		if (!state.claim(runId)) return;
		long started = System.nanoTime();
		try {
			AiAnalysisStateService.ExecutionInput input = state.loadExecution(runId);
			AiModelProvider provider = providers.stream().filter(p -> p.role() == input.decision().getProviderRole() && p.providerKey().equals(input.decision().getProviderKey()) && p.providerConfigHash().equals(input.decision().getProviderConfigHash())).findFirst().orElse(null);
			if (provider == null) { state.fail(runId, "AI_ANALYSIS_PROVIDER_FAILED", false); log.warn("ai analysis provider unavailable runId={}", runId); return; }
			List<AiAnalysisRequest.AiEvidenceInput> evidence = input.evidence().stream().map(e -> new AiAnalysisRequest.AiEvidenceInput(e.getId(), e.getEvidenceType().name(), e.getSourceRef(), e.getPayloadJson(), e.getMetadataJson())).toList();
			AiProviderResponse response = provider.analyze(new AiAnalysisRequest(runId, input.decision().getProviderRole(), AiSystemContract.UNTRUSTED_ARTIFACT_DATA, evidence));
			if (response == null || validator.invalidReason(response.result(), input.evidence().stream().map(e -> e.getId()).collect(java.util.stream.Collectors.toSet())).isPresent()) { state.fail(runId, "AI_ANALYSIS_RESULT_INVALID", false); log.warn("ai analysis invalid result runId={}", runId); return; }
			boolean completed = state.complete(runId, mapper.writeValueAsString(response.result()), true, response.latencyMs() == null ? (System.nanoTime() - started) / 1_000_000L : response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson());
			if (completed) log.info("ai analysis completed runId={} provider={} model={} durationMs={}", runId, provider.providerKey(), provider.modelId(), (System.nanoTime() - started) / 1_000_000L);
			else log.info("ai analysis late result discarded runId={} provider={}", runId, provider.providerKey());
		} catch (Exception ex) { state.fail(runId, "AI_ANALYSIS_PROVIDER_FAILED", false); log.warn("ai analysis failed runId={} type={}", runId, ex.getClass().getSimpleName()); }
	}
}

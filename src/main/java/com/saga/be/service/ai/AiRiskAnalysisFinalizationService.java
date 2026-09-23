package com.saga.be.service.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.ai.*;
import com.saga.be.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The run CAS, provider decision, and the Risk Analysis result commit or roll back together. */
@Service @Profile("!test")
public class AiRiskAnalysisFinalizationService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisProviderDecisionRepository decisions; private final AiRiskAnalysisRepository results; private final ObjectMapper mapper;
	public AiRiskAnalysisFinalizationService(AiAnalysisRunRepository runs, AiAnalysisProviderDecisionRepository decisions, AiRiskAnalysisRepository results, ObjectMapper mapper) { this.runs = runs; this.decisions = decisions; this.results = results; this.mapper = mapper; }

	@Transactional
	public boolean finalize(AiAnalysisStateService.ExecutionInput input, AiProviderResponse response, String resultJson) {
		UUID runId = input.run().getId();
		LocalDateTime now = LocalDateTime.now();
		// DB CAS first: a late response returns false before any result row can be written.
		if (runs.completeRunning(runId, now) != 1) return false;
		if (decisions.completeRunning(runId, resultJson, true, response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson(), now) != 1) {
			throw new IllegalStateException("AI risk analysis completion is missing a running provider decision");
		}
		AiRiskAnalysisResult result = (AiRiskAnalysisResult) response.result();
		try {
			AiRiskAnalysis row = new AiRiskAnalysis();
			row.setProject(input.run().getProject());
			row.setAnalysisRun(input.run());
			row.setRiskLevel(result.riskLevel());
			row.setReasonsJson(mapper.writeValueAsString(result.riskReasons()));
			row.setRecommendedActionsJson(mapper.writeValueAsString(result.recommendedActions()));
			row.setConfidence(result.confidence());
			row.setHumanReviewRecommended(result.humanReviewRecommended());
			results.saveAndFlush(row);
			return true;
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize risk analysis result", ex);
		}
	}
}

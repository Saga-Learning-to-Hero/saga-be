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

/** The run CAS, provider decision, and the Progress Narrative result commit or roll back together. */
@Service @Profile("!test")
public class AiProgressNarrativeFinalizationService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisProviderDecisionRepository decisions; private final AiProgressNarrativeRepository results; private final ObjectMapper mapper;
	public AiProgressNarrativeFinalizationService(AiAnalysisRunRepository runs, AiAnalysisProviderDecisionRepository decisions, AiProgressNarrativeRepository results, ObjectMapper mapper) { this.runs = runs; this.decisions = decisions; this.results = results; this.mapper = mapper; }

	@Transactional
	public boolean finalize(AiAnalysisStateService.ExecutionInput input, AiProviderResponse response, String resultJson, String factsJson) {
		UUID runId = input.run().getId();
		LocalDateTime now = LocalDateTime.now();
		if (runs.completeRunning(runId, now) != 1) return false;
		if (decisions.completeRunning(runId, resultJson, true, response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson(), now) != 1) {
			throw new IllegalStateException("AI progress narrative completion is missing a running provider decision");
		}
		AiProgressNarrativeResult result = (AiProgressNarrativeResult) response.result();
		try {
			AiProgressNarrative row = new AiProgressNarrative();
			row.setAnalysisRun(input.run());
			row.setFactsJson(factsJson);
			row.setOverview(result.overview());
			row.setHighlightsJson(mapper.writeValueAsString(result.highlights()));
			row.setConcernsJson(mapper.writeValueAsString(result.concerns()));
			row.setRecommendationsJson(mapper.writeValueAsString(result.recommendations()));
			row.setBlockersJson(mapper.writeValueAsString(result.blockers()));
			row.setDueSoonOverdueNote(result.dueSoonOverdueNote());
			row.setHumanReviewRecommended(result.humanReviewRecommended());
			results.saveAndFlush(row);
			return true;
		} catch (JsonProcessingException ex) {
			throw new IllegalStateException("Failed to serialize progress narrative result", ex);
		}
	}
}

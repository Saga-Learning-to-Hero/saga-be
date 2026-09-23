package com.saga.be.service.ai;

import com.saga.be.ai.*;
import com.saga.be.entity.ai.*;
import com.saga.be.repository.*;
import java.time.LocalDateTime;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The run CAS, provider decision, and the Task Intelligence result commit or roll back together. */
@Service @Profile("!test")
public class AiTaskIntelligenceFinalizationService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisProviderDecisionRepository decisions; private final AiTaskIntelligenceRepository results; private final TaskRepository tasks; private final ApplicationEventPublisher events;
	public AiTaskIntelligenceFinalizationService(AiAnalysisRunRepository runs, AiAnalysisProviderDecisionRepository decisions, AiTaskIntelligenceRepository results, TaskRepository tasks, ApplicationEventPublisher events) { this.runs = runs; this.decisions = decisions; this.results = results; this.tasks = tasks; this.events = events; }

	@Transactional
	public boolean finalize(AiAnalysisStateService.ExecutionInput input, AiProviderResponse response, String resultJson) {
		UUID runId = input.run().getId();
		LocalDateTime now = LocalDateTime.now();
		// DB CAS first: a late response returns false before any result row can be written.
		if (runs.completeRunning(runId, now) != 1) return false;
		if (decisions.completeRunning(runId, resultJson, true, response.latencyMs(), response.inputUnits(), response.outputUnits(), response.modelRevision(), response.costMetadataJson(), now) != 1) {
			throw new IllegalStateException("AI task intelligence completion is missing a running provider decision");
		}
		AiTaskIntelligenceResult result = (AiTaskIntelligenceResult) response.result();
		AiTaskIntelligence row = new AiTaskIntelligence();
		row.setProject(input.run().getProject());
		row.setAnalysisRun(input.run());
		row.setTask(tasks.getReferenceById(input.run().getArtifactId()));
		row.setTaskRevision(input.run().getArtifactRevision());
		row.setEvidenceStrength(result.evidenceStrength());
		row.setSummary(result.summary());
		row.setDeviationDetected(result.deviationDetected());
		row.setDeviationSummary(result.deviationSummary());
		row.setHumanReviewRequired(result.humanReviewRequired());
		results.saveAndFlush(row);
		events.publishEvent(new TaskIntelligenceCompletedEvent(
				input.run().getProject() == null ? null : input.run().getProject().getId(), input.run().getArtifactId()));
		return true;
	}
}

package com.saga.be.service.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Regression guard: adjudication must be attempted on EVERY exit path of execute() — not only
 * when PRIMARY succeeds. A PRIMARY failure (missing provider, invalid result, or a thrown
 * exception) must not silently suppress a SECONDARY_ONLY/FAILED adjudication outcome.
 */
class AiAnalysisExecutionServiceAdjudicationTest {
	private final UUID runId = UUID.randomUUID();

	private AiAnalysisStateService.ExecutionInput input(AiAnalysisRun run, AiAnalysisProviderDecision decision) {
		return new AiAnalysisStateService.ExecutionInput(run, List.of(), decision);
	}

	private AiAnalysisRun run(AiAnalysisType type) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId); run.setStatus(AiAnalysisStatus.RUNNING); run.setAnalysisType(type);
		run.setPromptVersion("task-intelligence-v1");
		return run;
	}

	private AiAnalysisProviderDecision decision() {
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setProviderRole(AiProviderRole.PRIMARY); decision.setProviderKey("missing-provider-key"); decision.setProviderConfigHash("cfg");
		return decision;
	}

	@Test
	void adjudicationRunsEvenWhenNoMatchingPrimaryProviderIsRegistered() {
		AiAnalysisStateService state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		when(state.loadExecution(runId)).thenReturn(input(run(AiAnalysisType.TASK_INTELLIGENCE), decision()));
		AiSecondaryBrainService secondaryBrain = mock(AiSecondaryBrainService.class);
		AiAdjudicationService adjudication = mock(AiAdjudicationService.class);

		// No providers registered at all -> "provider == null" early-return path.
		new AiAnalysisExecutionService(state, List.of(), new AiStructuredResultValidator(), new ObjectMapper(),
				new AiAcademicResultValidator(new ObjectMapper()), null,
				new AiTaskIntelligenceResultValidator(), null,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				secondaryBrain, adjudication)
				.execute(runId);

		verify(state).fail(eq(runId), eq("AI_ANALYSIS_PROVIDER_FAILED"), eq(false));
		verify(secondaryBrain).maybeRun(any());
		verify(adjudication).adjudicate(runId);
	}

	@Test
	void adjudicationRunsEvenWhenPrimaryResultFailsValidation() throws Exception {
		AiAnalysisStateService state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		AiAnalysisProviderDecision decision = decision();
		when(state.loadExecution(runId)).thenReturn(input(run(AiAnalysisType.TASK_INTELLIGENCE), decision));
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("missing-provider-key");
		when(provider.providerConfigHash()).thenReturn("cfg");
		// Missing evidenceStrength/summary -> fails AiTaskIntelligenceResultValidator.
		when(provider.analyze(any())).thenReturn(new AiProviderResponse(new AiTaskIntelligenceResult(null, null, false, null, List.of(), false), null, null, null, null, null));
		AiSecondaryBrainService secondaryBrain = mock(AiSecondaryBrainService.class);
		AiAdjudicationService adjudication = mock(AiAdjudicationService.class);

		new AiAnalysisExecutionService(state, List.of(provider), new AiStructuredResultValidator(), new ObjectMapper(),
				new AiAcademicResultValidator(new ObjectMapper()), null,
				new AiTaskIntelligenceResultValidator(), null,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				secondaryBrain, adjudication)
				.execute(runId);

		verify(state).fail(eq(runId), eq("AI_ANALYSIS_RESULT_INVALID"), eq(false));
		verify(adjudication).adjudicate(runId);
	}

	@Test
	void adjudicationRunsEvenWhenTheProviderThrows() {
		AiAnalysisStateService state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		AiAnalysisProviderDecision decision = decision();
		when(state.loadExecution(runId)).thenReturn(input(run(AiAnalysisType.TASK_INTELLIGENCE), decision));
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("missing-provider-key");
		when(provider.providerConfigHash()).thenReturn("cfg");
		when(provider.analyze(any())).thenThrow(new AiProviderException("AI_PROVIDER_TIMEOUT"));
		AiSecondaryBrainService secondaryBrain = mock(AiSecondaryBrainService.class);
		AiAdjudicationService adjudication = mock(AiAdjudicationService.class);

		new AiAnalysisExecutionService(state, List.of(provider), new AiStructuredResultValidator(), new ObjectMapper(),
				new AiAcademicResultValidator(new ObjectMapper()), null,
				new AiTaskIntelligenceResultValidator(), null,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				secondaryBrain, adjudication)
				.execute(runId);

		verify(state).fail(eq(runId), eq("AI_PROVIDER_TIMEOUT"), eq(false));
		verify(adjudication).adjudicate(runId);
	}
}

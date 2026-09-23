package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiSecondaryBrainServiceTest {
	private AiAnalysisProperties properties;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiResultValidation validation;
	private final ObjectMapper mapper = new ObjectMapper();
	private final UUID runId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		properties = new AiAnalysisProperties();
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		validation = mock(AiResultValidation.class);
	}

	private AiSecondaryBrainService service(List<AiModelProvider> providers) {
		return new AiSecondaryBrainService(properties, providers, decisions, validation, mapper);
	}

	private AiAnalysisStateService.ExecutionInput input(AiAnalysisType type) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId); run.setAnalysisType(type); run.setPromptVersion("task-intelligence-v1");
		return new AiAnalysisStateService.ExecutionInput(run, List.of(), new AiAnalysisProviderDecision());
	}

	private AiModelProvider secondaryProvider() {
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.SECONDARY);
		when(provider.providerKey()).thenReturn("saga-ai-secondary");
		when(provider.providerConfigHash()).thenReturn("secondary-cfg");
		when(provider.modelId()).thenReturn("secondary-model");
		return provider;
	}

	private AiTaskIntelligenceResult validResult() {
		return new AiTaskIntelligenceResult(com.saga.be.entity.enums.AiTaskEvidenceStrength.ACTIVE_PROGRESS, "ok", false, null, List.of(), false);
	}

	@Test
	void offByDefaultIsANoOpWithZeroInteractions() {
		assertThat(properties.isSecondaryEnabled()).isFalse();
		service(List.of(secondaryProvider())).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE));
		verifyNoInteractions(decisions);
	}

	@Test
	void enabledButNoSecondaryProviderBeanIsASafeNoOp() {
		properties.setSecondaryEnabled(true);
		service(List.of()).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE)); // only PRIMARY-role providers would be here normally
		verifyNoInteractions(decisions);
	}

	@Test
	void enabledAndReadyCallsSecondaryExactlyOnceAndCompletes() throws Exception {
		properties.setSecondaryEnabled(true);
		AiModelProvider secondary = secondaryProvider();
		AiProviderResponse response = new AiProviderResponse(validResult(), 42L, 10L, 5L, null, null);
		when(secondary.analyze(any())).thenReturn(response);
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.empty());
		when(decisions.claimSecondaryPending(eq(runId), any())).thenReturn(1);
		when(validation.invalidReason(eq(AiAnalysisType.TASK_INTELLIGENCE), any(), any())).thenReturn(Optional.empty());

		service(List.of(secondary)).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE));

		verify(secondary, times(1)).analyze(any());
		ArgumentCaptor<AiAnalysisProviderDecision> saved = ArgumentCaptor.forClass(AiAnalysisProviderDecision.class);
		verify(decisions).saveAndFlush(saved.capture());
		assertThat(saved.getValue().getProviderRole()).isEqualTo(AiProviderRole.SECONDARY);
		verify(decisions).completeSecondaryRunning(eq(runId), anyString(), eq(true), eq(42L), eq(10L), eq(5L), any(), any(), any());
		verify(decisions, never()).failSecondaryActive(any(), any(), anyBoolean(), any());
	}

	@Test
	void providerExceptionFailsTheSecondaryDecisionNotTheWholeRun() throws Exception {
		properties.setSecondaryEnabled(true);
		AiModelProvider secondary = secondaryProvider();
		when(secondary.analyze(any())).thenThrow(new AiProviderException("AI_PROVIDER_TIMEOUT"));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.empty());
		when(decisions.claimSecondaryPending(eq(runId), any())).thenReturn(1);

		service(List.of(secondary)).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE));

		verify(secondary, times(1)).analyze(any()); // exactly one attempt, no retry
		verify(decisions).failSecondaryActive(eq(runId), eq("AI_PROVIDER_TIMEOUT"), eq(false), any());
	}

	@Test
	void invalidSecondaryResultFailsWithoutCompleting() throws Exception {
		properties.setSecondaryEnabled(true);
		AiModelProvider secondary = secondaryProvider();
		when(secondary.analyze(any())).thenReturn(new AiProviderResponse(validResult(), null, null, null, null, null));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.empty());
		when(decisions.claimSecondaryPending(eq(runId), any())).thenReturn(1);
		when(validation.invalidReason(eq(AiAnalysisType.TASK_INTELLIGENCE), any(), any())).thenReturn(Optional.of("MISSING_REQUIRED_SECTION"));

		service(List.of(secondary)).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE));

		verify(decisions).failSecondaryActive(eq(runId), eq("AI_ANALYSIS_RESULT_INVALID"), eq(false), any());
		verify(decisions, never()).completeSecondaryRunning(any(), any(), anyBoolean(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void alreadyAttemptedRunIsNotAttemptedAgain() {
		properties.setSecondaryEnabled(true);
		AiModelProvider secondary = secondaryProvider();
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(new AiAnalysisProviderDecision()));

		service(List.of(secondary)).maybeRun(input(AiAnalysisType.TASK_INTELLIGENCE));

		verify(secondary, never()).analyze(any());
		verify(decisions, never()).saveAndFlush(any());
	}
}

package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AiAdjudicationServiceTest {
	private final ObjectMapper mapper = new ObjectMapper();
	private AiAnalysisRunRepository runs;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiAnalysisAdjudicationRepository adjudications;
	private final UUID runId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		runs = mock(AiAnalysisRunRepository.class);
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		adjudications = mock(AiAnalysisAdjudicationRepository.class);
		when(adjudications.findByAnalysisRun_Id(runId)).thenReturn(Optional.empty());
	}

	private AiAdjudicationService service() { return new AiAdjudicationService(runs, decisions, adjudications, mapper); }

	private AiAnalysisRun run(AiAnalysisType type) { AiAnalysisRun run = new AiAnalysisRun(); run.setId(runId); run.setAnalysisType(type); return run; }

	private AiAnalysisProviderDecision decision(AiProviderRole role, AiProviderDecisionStatus status, String json) {
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setProviderRole(role); decision.setStatus(status); decision.setStructuredResultJson(json);
		return decision;
	}

	@Test
	void secondaryAbsentMeansNoAdjudicationRowAtAll() {
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.empty());
		service().adjudicate(runId);
		verify(adjudications, never()).saveAndFlush(any());
		verifyNoInteractions(runs);
	}

	@Test
	void identicalRiskLevelsAreAgreed() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.RISK_ANALYSIS)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"MEDIUM\"}")));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"MEDIUM\"}")));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.AGREED);
		assertThat(captor.getValue().isHumanReviewRequired()).isFalse();
	}

	@Test
	void riskLevelOneStepApartIsMinorDisagreement() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.RISK_ANALYSIS)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"LOW\"}")));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"MEDIUM\"}")));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.MINOR_DISAGREEMENT);
	}

	@Test
	void riskLevelTwoStepsApartIsMajorDisagreementAndRequiresHumanReview() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.RISK_ANALYSIS)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"LOW\"}")));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, "{\"riskLevel\":\"HIGH\"}")));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.MAJOR_DISAGREEMENT);
		assertThat(captor.getValue().isHumanReviewRequired()).isTrue();
	}

	@Test
	void secondaryFailedYieldsPrimaryOnlyAndRequiresHumanReview() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.TASK_INTELLIGENCE)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, "{\"evidenceStrength\":\"ACTIVE_PROGRESS\"}")));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.FAILED, null)));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.PRIMARY_ONLY);
		assertThat(captor.getValue().isHumanReviewRequired()).isTrue();
	}

	@Test
	void primaryFailedYieldsSecondaryOnly() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.TASK_INTELLIGENCE)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.FAILED, null)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, "{\"evidenceStrength\":\"ACTIVE_PROGRESS\"}")));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.SECONDARY_ONLY);
	}

	@Test
	void bothFailedYieldsFailed() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.TASK_INTELLIGENCE)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.FAILED, null)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.FAILED, null)));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.FAILED);
	}

	@Test
	void academicDifferentTargetSetsIsMajorDisagreement() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.ACADEMIC_CLASSIFICATION)));
		String primaryJson = "{\"classificationDecision\":\"PROPOSED\",\"classifications\":[{\"targetType\":\"PHASE\",\"targetId\":\"11111111-1111-1111-1111-111111111111\"}]}";
		String secondaryJson = "{\"classificationDecision\":\"PROPOSED\",\"classifications\":[{\"targetType\":\"PHASE\",\"targetId\":\"22222222-2222-2222-2222-222222222222\"}]}";
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, primaryJson)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, secondaryJson)));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.MAJOR_DISAGREEMENT);
	}

	@Test
	void academicIdenticalTargetSetsIsAgreed() {
		when(runs.findById(runId)).thenReturn(Optional.of(run(AiAnalysisType.ACADEMIC_CLASSIFICATION)));
		String json = "{\"classificationDecision\":\"PROPOSED\",\"classifications\":[{\"targetType\":\"PHASE\",\"targetId\":\"11111111-1111-1111-1111-111111111111\"}]}";
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.PRIMARY)).thenReturn(Optional.of(decision(AiProviderRole.PRIMARY, AiProviderDecisionStatus.COMPLETED, json)));
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.of(decision(AiProviderRole.SECONDARY, AiProviderDecisionStatus.COMPLETED, json)));

		service().adjudicate(runId);

		ArgumentCaptor<AiAnalysisAdjudication> captor = ArgumentCaptor.forClass(AiAnalysisAdjudication.class);
		verify(adjudications).saveAndFlush(captor.capture());
		assertThat(captor.getValue().getOutcome()).isEqualTo(AiAdjudicationOutcome.AGREED);
	}

	@Test
	void alreadyAdjudicatedRunIsIdempotentAndSkipped() {
		when(adjudications.findByAnalysisRun_Id(runId)).thenReturn(Optional.of(new AiAnalysisAdjudication()));
		service().adjudicate(runId);
		verifyNoInteractions(runs);
		verify(adjudications, never()).saveAndFlush(any());
	}
}

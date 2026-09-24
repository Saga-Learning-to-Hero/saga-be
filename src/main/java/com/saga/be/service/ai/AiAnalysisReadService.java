package com.saga.be.service.ai;

import com.saga.be.dto.ai.*;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.repository.*;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AiAnalysisReadService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisAdjudicationRepository adjudications; private final ProjectDataAuthorization authorization;
	public AiAnalysisReadService(AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiAnalysisAdjudicationRepository adjudications, ProjectDataAuthorization authorization) { this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.adjudications = adjudications; this.authorization = authorization; }
	@Transactional(readOnly = true)
	public AiAdjudicationResponse adjudication(UUID userId, UUID projectId, UUID analysisId) {
		authorization.requireReader(userId, projectId);
		runs.findFetchedByIdAndProjectId(analysisId, projectId).orElseThrow(() -> new com.saga.be.exception.IntegrationException(com.saga.be.integration.IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, org.springframework.http.HttpStatus.NOT_FOUND, "AI analysis was not found."));
		return adjudications.findByAnalysisRun_Id(analysisId).map(a -> new AiAdjudicationResponse(a.getOutcome().name(), a.getDisagreementDetailsJson(), a.isHumanReviewRequired())).orElseGet(AiAdjudicationResponse::notApplicable);
	}
	@Transactional(readOnly = true)
	public AiAnalysisResponse get(UUID userId, UUID projectId, UUID analysisId) {
		authorization.requireReader(userId, projectId);
		AiAnalysisRun run = runs.findFetchedByIdAndProjectId(analysisId, projectId).orElseThrow(() -> new com.saga.be.exception.IntegrationException(com.saga.be.integration.IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, org.springframework.http.HttpStatus.NOT_FOUND, "AI analysis was not found."));
		return toResponse(run, evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId()), decisions.findByAnalysisRun_Id(run.getId()).orElse(null));
	}
	@Transactional(readOnly = true)
	public AiAnalysisPageResponse history(UUID userId, UUID projectId, UUID commitId, Integer page, Integer size) {
		authorization.requireReader(userId, projectId); int safePage = page == null ? 0 : Math.max(0, page); int safeSize = size == null ? 20 : Math.min(100, Math.max(1, size));
		var result = runs.findPageByProjectAndArtifact(projectId, commitId, PageRequest.of(safePage, safeSize)); List<UUID> ids = result.getContent().stream().map(AiAnalysisRun::getId).toList();
		Map<UUID, List<AiAnalysisEvidence>> evidenceByRun = new HashMap<>(); for (AiAnalysisEvidence row : evidence.findByAnalysisRun_IdInOrderByAnalysisRun_IdAscOrdinalIndexAsc(ids)) evidenceByRun.computeIfAbsent(row.getAnalysisRun().getId(), ignored -> new ArrayList<>()).add(row);
		Map<UUID, AiAnalysisProviderDecision> decisionsByRun = new HashMap<>(); for (AiAnalysisProviderDecision decision : decisions.findByAnalysisRun_IdIn(ids)) decisionsByRun.put(decision.getAnalysisRun().getId(), decision);
		return new AiAnalysisPageResponse(result.getContent().stream().map(run -> toResponse(run, evidenceByRun.getOrDefault(run.getId(), List.of()), decisionsByRun.get(run.getId()))).toList(), safePage, safeSize, result.getTotalElements());
	}
	/** Drill-down: latest run for this artifact, or a clear NOT_ANALYZED marker. Never submits. */
	@Transactional(readOnly = true)
	public AiLatestAnalysisResponse latest(UUID userId, UUID projectId, AiArtifactType artifactType, UUID artifactId, AiAnalysisType analysisType) {
		authorization.requireReader(userId, projectId);
		AiAnalysisRun run = runs.findTopByProject_IdAndArtifactTypeAndArtifactIdAndAnalysisTypeOrderByCreatedAtDesc(projectId, artifactType, artifactId, analysisType).orElse(null);
		if (run == null) return AiLatestAnalysisResponse.notAnalyzed();
		return AiLatestAnalysisResponse.found(toResponse(run, evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId()), decisions.findByAnalysisRun_Id(run.getId()).orElse(null)));
	}
	static AiAnalysisResponse toResponse(AiAnalysisRun run, List<AiAnalysisEvidence> rows, AiAnalysisProviderDecision decision) {
		List<AiAnalysisEvidenceSummaryResponse> summaries = rows.stream().map(e -> new AiAnalysisEvidenceSummaryResponse(e.getId(), e.getEvidenceType().name(), e.getSourceRef(), e.getContentHash(), e.getOrdinalIndex())).toList();
		AiAnalysisDecisionResponse output = decision == null ? null : new AiAnalysisDecisionResponse(decision.getId(), decision.getProviderRole().name(), decision.getProviderKey(), decision.getModelId(), decision.getModelRevision(), decision.getRoute().name(), decision.getStatus().name(), decision.getSchemaValid(), decision.getLatencyMs(), decision.getInputUnits(), decision.getOutputUnits(), decision.getSafeErrorCode(), decision.getStructuredResultJson(), decision.getCompletedAt(), decision.getAiProvider() == null ? null : decision.getAiProvider().name(), decision.getFallbackAttemptsJson());
		return new AiAnalysisResponse(run.getId(), run.getProject() == null ? null : run.getProject().getId(), run.getCourse() == null ? null : run.getCourse().getId(), run.getArtifactType().name(), run.getArtifactId(), run.getArtifactRevision(), run.getAnalysisType().name(), run.getStatus().name(), run.getEvidenceHash(), run.getPolicyVersion(), run.getPromptVersion(), run.getSchemaVersion(), run.getTaxonomyVersion(), run.getProviderConfigHash(), run.getStartedAt(), run.getCompletedAt(), run.getFailureCode(), run.getCreatedAt(), summaries, output);
	}
}

package com.saga.be.service.ai;

import com.saga.be.dto.ai.AiAnalysisResponse;
import com.saga.be.dto.ai.AiLatestAnalysisResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DB-only read for COURSE-scope analysis runs, authorized by course instead of by project. */
@Service
@Profile("!test")
public class AiCourseAnalysisReadService {
	private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final LecturerCourseAuthorization authorization;

	public AiCourseAnalysisReadService(AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, LecturerCourseAuthorization authorization) {
		this.runs = runs; this.evidence = evidence; this.decisions = decisions; this.authorization = authorization;
	}

	@Transactional(readOnly = true)
	public AiAnalysisResponse get(UserAccount actor, UUID courseId, UUID analysisId) {
		authorization.requireCourse(actor, courseId);
		AiAnalysisRun run = runs.findFetchedByIdAndCourseId(analysisId, courseId).orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, HttpStatus.NOT_FOUND, "AI analysis was not found."));
		return AiAnalysisReadService.toResponse(run, evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId()), decisions.findByAnalysisRun_Id(run.getId()).orElse(null));
	}

	@Transactional(readOnly = true)
	public AiLatestAnalysisResponse latest(UserAccount actor, UUID courseId, AiArtifactType artifactType, UUID artifactId, AiAnalysisType analysisType) {
		authorization.requireCourse(actor, courseId);
		AiAnalysisRun run = runs.findTopByCourse_IdAndArtifactTypeAndArtifactIdAndAnalysisTypeOrderByCreatedAtDesc(courseId, artifactType, artifactId, analysisType).orElse(null);
		if (run == null) return AiLatestAnalysisResponse.notAnalyzed();
		return AiLatestAnalysisResponse.found(AiAnalysisReadService.toResponse(run, evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId()), decisions.findByAnalysisRun_Id(run.getId()).orElse(null)));
	}
}

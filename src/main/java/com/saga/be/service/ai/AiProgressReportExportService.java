package com.saga.be.service.ai;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiProgressNarrative;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.AiProgressNarrativeRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** DOCX export lives entirely in saga-be; saga-ai never sees or produces the file. */
@Service @Profile("!test")
public class AiProgressReportExportService {
	private final AiAnalysisRunRepository runs; private final AiProgressNarrativeRepository narratives; private final AiProgressReportDocxRenderer renderer; private final ProjectDataAuthorization projectAuth; private final LecturerCourseAuthorization courseAuth;

	public AiProgressReportExportService(AiAnalysisRunRepository runs, AiProgressNarrativeRepository narratives, AiProgressReportDocxRenderer renderer, ProjectDataAuthorization projectAuth, LecturerCourseAuthorization courseAuth) {
		this.runs = runs; this.narratives = narratives; this.renderer = renderer; this.projectAuth = projectAuth; this.courseAuth = courseAuth;
	}

	public record Export(byte[] bytes, String filename) {}

	@Transactional(readOnly = true)
	public Export exportForProject(UUID userId, UUID projectId, UUID analysisId) {
		projectAuth.requireReader(userId, projectId);
		AiAnalysisRun run = runs.findFetchedByIdAndProjectId(analysisId, projectId).orElseThrow(this::notFound);
		return render(run);
	}

	@Transactional(readOnly = true)
	public Export exportForCourse(UserAccount actor, UUID courseId, UUID analysisId) {
		courseAuth.requireCourse(actor, courseId);
		AiAnalysisRun run = runs.findFetchedByIdAndCourseId(analysisId, courseId).orElseThrow(this::notFound);
		return render(run);
	}

	private Export render(AiAnalysisRun run) {
		if (run.getAnalysisType() != AiAnalysisType.PROGRESS_NARRATIVE) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, HttpStatus.NOT_FOUND, "This analysis is not a progress report.");
		if (run.getStatus() != AiAnalysisStatus.COMPLETED) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, HttpStatus.CONFLICT, "The progress report has not completed yet.");
		AiProgressNarrative narrative = narratives.findByAnalysisRun_Id(run.getId()).orElseThrow(this::notFound);
		return new Export(renderer.render(run, narrative), renderer.filename(run));
	}

	private IntegrationException notFound() { return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_NOT_FOUND, HttpStatus.NOT_FOUND, "AI analysis was not found."); }
}

package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.ai.AiProgressNarrative;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.exception.IntegrationException;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.AiProgressNarrativeRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The DOCX download must never bypass the same reader/lecturer authorization the JSON read APIs
 * already enforce, and must never let a caller who IS an authorized reader of project/course A
 * download a run that actually belongs to a different project/course B by guessing its analysisId
 * -- both are proven here since {@code AiAnalysisRunRepository.findFetchedByIdAndProjectId}/
 * {@code findFetchedByIdAndCourseId} scope the lookup itself, not just the authorization check.
 */
class AiProgressReportExportServiceTest {
	private AiAnalysisRunRepository runs;
	private AiProgressNarrativeRepository narratives;
	private AiProgressReportDocxRenderer renderer;
	private ProjectDataAuthorization projectAuth;
	private LecturerCourseAuthorization courseAuth;
	private AiProgressReportExportService service;

	private final UUID userId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();
	private final UUID analysisId = UUID.randomUUID();

	@BeforeEach
	void setUp() {
		runs = mock(AiAnalysisRunRepository.class);
		narratives = mock(AiProgressNarrativeRepository.class);
		renderer = mock(AiProgressReportDocxRenderer.class);
		projectAuth = mock(ProjectDataAuthorization.class);
		courseAuth = mock(LecturerCourseAuthorization.class);
		service = new AiProgressReportExportService(runs, narratives, renderer, projectAuth, courseAuth);
	}

	private AiAnalysisRun completedProgressRun() {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(analysisId);
		run.setAnalysisType(AiAnalysisType.PROGRESS_NARRATIVE);
		run.setStatus(AiAnalysisStatus.COMPLETED);
		return run;
	}

	@Test
	void projectExportChecksReaderAuthorizationBeforeTouchingTheRun() {
		doThrow(new RuntimeException("denied")).when(projectAuth).requireReader(userId, projectId);

		assertThatThrownBy(() -> service.exportForProject(userId, projectId, analysisId))
				.isInstanceOf(RuntimeException.class);
		verifyNoInteractions(runs, narratives, renderer);
	}

	@Test
	void projectExportFailsWhenTheRunBelongsToADifferentProjectEvenIfCallerIsAnAuthorizedReaderOfThisOne() {
		// requireReader passes for `projectId`, but the run itself does not belong to it --
		// findFetchedByIdAndProjectId's own WHERE clause must be what blocks cross-project access.
		when(runs.findFetchedByIdAndProjectId(analysisId, projectId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.exportForProject(userId, projectId, analysisId))
				.isInstanceOf(IntegrationException.class);
		verify(projectAuth).requireReader(userId, projectId);
		verifyNoInteractions(narratives, renderer);
	}

	@Test
	void projectExportRendersOnlyWhenAuthorizedAndScopedRunIsACompletedProgressNarrative() {
		AiAnalysisRun run = completedProgressRun();
		AiProgressNarrative narrative = new AiProgressNarrative();
		when(runs.findFetchedByIdAndProjectId(analysisId, projectId)).thenReturn(Optional.of(run));
		when(narratives.findByAnalysisRun_Id(analysisId)).thenReturn(Optional.of(narrative));
		when(renderer.render(run, narrative)).thenReturn(new byte[] {1, 2, 3});
		when(renderer.filename(run)).thenReturn("saga-progress-report.docx");

		AiProgressReportExportService.Export export = service.exportForProject(userId, projectId, analysisId);

		verify(projectAuth).requireReader(userId, projectId);
		assertThat(export.bytes()).containsExactly(1, 2, 3);
		assertThat(export.filename()).isEqualTo("saga-progress-report.docx");
	}

	@Test
	void projectExportRejectsARunThatIsNotYetCompleted() {
		AiAnalysisRun run = completedProgressRun();
		run.setStatus(AiAnalysisStatus.RUNNING);
		when(runs.findFetchedByIdAndProjectId(analysisId, projectId)).thenReturn(Optional.of(run));

		assertThatThrownBy(() -> service.exportForProject(userId, projectId, analysisId))
				.isInstanceOf(IntegrationException.class);
		verifyNoInteractions(narratives, renderer);
	}

	@Test
	void projectExportRejectsARunThatIsNotAProgressNarrative() {
		AiAnalysisRun run = completedProgressRun();
		run.setAnalysisType(AiAnalysisType.COMMIT_INTELLIGENCE);
		when(runs.findFetchedByIdAndProjectId(analysisId, projectId)).thenReturn(Optional.of(run));

		assertThatThrownBy(() -> service.exportForProject(userId, projectId, analysisId))
				.isInstanceOf(IntegrationException.class);
		verifyNoInteractions(narratives, renderer);
	}

	@Test
	void courseExportChecksLecturerAuthorizationBeforeTouchingTheRun() {
		UserAccount actor = new UserAccount();
		doThrow(new RuntimeException("denied")).when(courseAuth).requireCourse(actor, courseId);

		assertThatThrownBy(() -> service.exportForCourse(actor, courseId, analysisId))
				.isInstanceOf(RuntimeException.class);
		verifyNoInteractions(runs, narratives, renderer);
	}

	@Test
	void courseExportFailsWhenTheRunBelongsToADifferentCourseEvenIfCallerIsAnAuthorizedLecturerOfThisOne() {
		UserAccount actor = new UserAccount();
		when(runs.findFetchedByIdAndCourseId(analysisId, courseId)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.exportForCourse(actor, courseId, analysisId))
				.isInstanceOf(IntegrationException.class);
		verify(courseAuth).requireCourse(actor, courseId);
		verifyNoInteractions(narratives, renderer);
	}

	@Test
	void courseExportRendersOnlyWhenAuthorizedAndScopedRunIsACompletedProgressNarrative() {
		UserAccount actor = new UserAccount();
		AiAnalysisRun run = completedProgressRun();
		AiProgressNarrative narrative = new AiProgressNarrative();
		when(runs.findFetchedByIdAndCourseId(analysisId, courseId)).thenReturn(Optional.of(run));
		when(narratives.findByAnalysisRun_Id(analysisId)).thenReturn(Optional.of(narrative));
		when(renderer.render(run, narrative)).thenReturn(new byte[] {9});
		when(renderer.filename(run)).thenReturn("saga-progress-report-course.docx");

		AiProgressReportExportService.Export export = service.exportForCourse(actor, courseId, analysisId);

		verify(courseAuth).requireCourse(actor, courseId);
		assertThat(export.bytes()).containsExactly(9);
	}
}

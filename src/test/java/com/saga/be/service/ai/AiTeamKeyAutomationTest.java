package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Team AI with the team's own key, for an analysis other than commit review (Task Intelligence). */
class AiTeamKeyAutomationTest {

	private final UUID projectId = UUID.randomUUID(), taskId = UUID.randomUUID(), courseId = UUID.randomUUID(), teamCredentialId = UUID.randomUUID();
	private AiCredentialResolver resolver;
	private CourseAiSettingsService settings;
	private AiAnalysisRunRepository runs;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiTaskIntelligenceSubmissionService service;

	@BeforeEach
	void setUp() {
		TaskRepository tasks = mock(TaskRepository.class);
		Course course = new Course();
		course.setId(courseId);
		Project project = new Project();
		project.setId(projectId);
		project.setCourse(course);
		Task task = new Task();
		task.setId(taskId);
		task.setProject(project);
		when(tasks.findActiveFetchedByIdAndProject_Id(taskId, projectId)).thenReturn(Optional.of(task));
		AiTaskIntelligenceSnapshotBuilder snapshots = mock(AiTaskIntelligenceSnapshotBuilder.class);
		when(snapshots.build(any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.TASK_FIELD, "task:" + taskId, "{}", null)));
		runs = mock(AiAnalysisRunRepository.class);
		when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString())).thenReturn(Optional.empty());
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(inv -> { AiAnalysisRun run = inv.getArgument(0); run.setId(UUID.randomUUID()); return run; });
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("remote");
		when(provider.providerConfigHash()).thenReturn("cfg");
		when(provider.modelId()).thenReturn("platform-model");
		resolver = mock(AiCredentialResolver.class);
		settings = mock(CourseAiSettingsService.class);
		when(settings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(false, false)); // course automation OFF
		service = new AiTaskIntelligenceSubmissionService(mock(ProjectDataAuthorization.class), tasks, snapshots, runs,
				mock(AiAnalysisEvidenceRepository.class), decisions, mock(AiAnalysisExecutor.class), List.of(provider),
				new TrackingPlatformTransactionManager(), resolver, settings);
	}

	private void routesTo(AiCredentialResolver.Resolution resolution) {
		when(resolver.resolveForProject(eq(projectId), eq(courseId), eq(AiAnalysisType.TASK_INTELLIGENCE), eq(AiProviderRole.PRIMARY), any()))
				.thenReturn(resolution);
	}

	private AiCredentialResolver.Resolution team() {
		return new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, null, "team-fp",
				new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash"), teamCredentialId);
	}

	@Test
	void aTeamKeyRunsAutomaticallyEvenWithCourseAutomationOff_andIsRecordedOnTheDecision() {
		routesTo(team());

		assertThat(service.submitAutomatic(projectId, taskId)).isPresent();

		ArgumentCaptor<AiAnalysisProviderDecision> decision = ArgumentCaptor.forClass(AiAnalysisProviderDecision.class);
		verify(decisions).save(decision.capture());
		assertThat(decision.getValue().getTeamCredentialId()).isEqualTo(teamCredentialId);
		assertThat(decision.getValue().getCourseCredentialId()).isNull();
		assertThat(decision.getValue().getAiProvider()).isEqualTo(AiProvider.GEMINI);
	}

	@Test
	void theCourseKeyForAPickedTeamStillNeedsCourseAutomationOn() {
		routesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "course-fp"));
		assertThat(service.submitAutomatic(projectId, taskId)).isEmpty();
		verify(runs, never()).saveAndFlush(any());
	}

	@Test
	void aTeamWithoutKeyNotPickedByTheLecturer_cannotRunTaskAiEvenByHand() {
		routesTo(AiCredentialResolver.Resolution.UNAVAILABLE);
		assertThat(service.submitAutomatic(projectId, taskId)).isEmpty();
		assertThatThrownBy(() -> service.submit(UUID.randomUUID(), projectId, taskId))
				.isInstanceOf(IntegrationException.class)
				.hasMessageContaining("chưa được giảng viên cho dùng key của lớp");
		verify(resolver).resolveForProject(projectId, courseId, AiAnalysisType.TASK_INTELLIGENCE, AiProviderRole.PRIMARY, AiInvocationOrigin.USER_REQUEST);
	}
}

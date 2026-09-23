package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.repository.*;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Section XVIII: "no course credential => zero provider call", "automation disabled => zero
 * provider call", "new eligible commit triggers exactly one submission". These exercise {@code
 * AiAnalysisSubmissionService.submitAutomatic} directly -- the exact entry point {@link
 * AiCommitAutomationTrigger} calls -- proving the gate itself, not just the trigger's fan-out.
 */
class AiAnalysisSubmissionServiceAutomationGateTest {
	private final UUID projectId = UUID.randomUUID(), commitId = UUID.randomUUID(), courseId = UUID.randomUUID();
	private GitCommitRepository commits;
	private AiCredentialResolver credentialResolver;
	private CourseAiSettingsService courseSettings;
	private AiModelProvider provider;
	private AiAnalysisRunRepository runs;
	private GitCommit commit;
	private Course course;

	@BeforeEach
	void setUp() {
		commits = mock(GitCommitRepository.class);
		credentialResolver = mock(AiCredentialResolver.class);
		courseSettings = mock(CourseAiSettingsService.class);
		provider = mock(AiModelProvider.class);
		runs = mock(AiAnalysisRunRepository.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("primary");
		when(provider.providerConfigHash()).thenReturn("cfg");
		when(provider.modelId()).thenReturn("model");

		course = new Course();
		course.setId(courseId);
		Project project = new Project();
		project.setId(projectId);
		project.setCourse(course);
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		commit = new GitCommit();
		commit.setId(commitId);
		commit.setRepo(repo);
		commit.setShaHash("abc123");
		when(commits.findAnalysisTargetById(commitId)).thenReturn(Optional.of(commit));
		when(runs.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(inv -> { AiAnalysisRun run = inv.getArgument(0); run.setId(UUID.randomUUID()); return run; });
	}

	private AiAnalysisSubmissionService service() {
		AiCommitEvidenceSnapshotBuilder snapshots = mock(AiCommitEvidenceSnapshotBuilder.class);
		when(snapshots.build(any(), any(), any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.COMMIT_MESSAGE, "commit:" + commitId, "{}", null)));
		AiGitHubCommitEvidenceAcquirer github = mock(AiGitHubCommitEvidenceAcquirer.class);
		when(github.acquire(any())).thenReturn(List.of());
		return new AiAnalysisSubmissionService(
				mock(ProjectDataAuthorization.class), commits, mock(TaskGitCommitLinkRepository.class), mock(JiraTaskFailoverItemRepository.class),
				runs, mock(AiAnalysisEvidenceRepository.class), mock(AiAnalysisProviderDecisionRepository.class), snapshots, github,
				mock(AiAnalysisExecutor.class), List.of(provider), new TrackingPlatformTransactionManager(), credentialResolver, courseSettings);
	}

	@Test
	void automationDisabledMeansZeroProviderCallsAndNoRunCreated() {
		when(courseSettings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(false, false));

		Optional<AiAnalysisSubmissionService.Submission> result = service().submitAutomatic(projectId, commitId);

		assertThat(result).isEmpty();
		verifyNoInteractions(credentialResolver);
		verify(runs, never()).saveAndFlush(any());
	}

	@Test
	void automationEnabledButNoCourseCredentialMeansZeroProviderCallsAndNoRunCreated() {
		when(courseSettings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		when(credentialResolver.resolve(eq(courseId), eq(AiAnalysisType.COMMIT_INTELLIGENCE), eq(AiProviderRole.PRIMARY), eq(AiInvocationOrigin.AUTOMATION)))
				.thenReturn(AiCredentialResolver.Resolution.UNAVAILABLE);

		Optional<AiAnalysisSubmissionService.Submission> result = service().submitAutomatic(projectId, commitId);

		assertThat(result).isEmpty();
		verify(runs, never()).saveAndFlush(any());
	}

	@Test
	void automationEnabledWithCourseCredentialSubmitsExactlyOnce() {
		when(courseSettings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		when(credentialResolver.resolve(eq(courseId), eq(AiAnalysisType.COMMIT_INTELLIGENCE), eq(AiProviderRole.PRIMARY), eq(AiInvocationOrigin.AUTOMATION)))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "fp"));

		Optional<AiAnalysisSubmissionService.Submission> result = service().submitAutomatic(projectId, commitId);

		assertThat(result).isPresent();
		verify(runs, times(1)).saveAndFlush(any());
	}

	@Test
	void resubmittingTheSameUnchangedCommitNeverCreatesADuplicateRun() {
		when(courseSettings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		when(credentialResolver.resolve(any(), any(), any(), any()))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "fp"));
		AiAnalysisSubmissionService service = service();
		var canonical = new java.util.HashMap<String, AiAnalysisRun>();
		when(runs.findByIdempotencyKey(anyString())).thenAnswer(inv -> Optional.ofNullable(canonical.get(inv.getArgument(0))));
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(inv -> { AiAnalysisRun run = inv.getArgument(0); run.setId(UUID.randomUUID()); canonical.put(run.getIdempotencyKey(), run); return run; });

		service.submitAutomatic(projectId, commitId);
		service.submitAutomatic(projectId, commitId); // simulates a duplicate webhook/sync delivery

		verify(runs, times(1)).saveAndFlush(any());
	}
}

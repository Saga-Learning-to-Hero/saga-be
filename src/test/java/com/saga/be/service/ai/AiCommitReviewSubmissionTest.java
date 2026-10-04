package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Who pays for a commit review (team key first), merge commits never reviewed, and what the AI is given. */
class AiCommitReviewSubmissionTest {

	private final UUID projectId = UUID.randomUUID(), commitId = UUID.randomUUID(), courseId = UUID.randomUUID(), userId = UUID.randomUUID();
	private GitCommitRepository commits;
	private AiCredentialResolver credentialResolver;
	private CourseAiSettingsService courseSettings;
	private AiAnalysisRunRepository runs;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiAnalysisEvidenceRepository evidence;
	private TaskGitCommitLinkRepository links;
	private TaskCommitManualLinkRepository manualLinks;
	private TeamAiCredentialService teamKeys;
	private AiCommitReviewContextBuilder reviewContext;
	private AiCommitEvidenceSnapshotBuilder snapshots;
	private GitCommit commit;
	private Project project;

	@BeforeEach
	void setUp() {
		commits = mock(GitCommitRepository.class);
		credentialResolver = mock(AiCredentialResolver.class); org.mockito.Mockito.lenient().when(credentialResolver.resolveForProject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> credentialResolver.resolve(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4))); org.mockito.Mockito.lenient().when(credentialResolver.resolveCourseForProject(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> credentialResolver.resolve(inv.getArgument(1), inv.getArgument(2), inv.getArgument(3), inv.getArgument(4)));
		courseSettings = mock(CourseAiSettingsService.class);
		runs = mock(AiAnalysisRunRepository.class);
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		evidence = mock(AiAnalysisEvidenceRepository.class);
		links = mock(TaskGitCommitLinkRepository.class);
		manualLinks = mock(TaskCommitManualLinkRepository.class);
		teamKeys = mock(TeamAiCredentialService.class);
		reviewContext = mock(AiCommitReviewContextBuilder.class);
		snapshots = mock(AiCommitEvidenceSnapshotBuilder.class);
		Course course = new Course();
		course.setId(courseId);
		project = new Project();
		project.setId(projectId);
		project.setCourse(course);
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		commit = new GitCommit();
		commit.setId(commitId);
		commit.setRepo(repo);
		commit.setShaHash("abc123");
		commit.setParentCount(1);
		when(commits.findAnalysisTargetById(commitId)).thenReturn(Optional.of(commit));
		when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString())).thenReturn(Optional.empty());
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(inv -> { AiAnalysisRun run = inv.getArgument(0); run.setId(UUID.randomUUID()); return run; });
		when(links.findAnalysisEvidenceByGitCommitId(commitId, projectId)).thenReturn(List.of());
		when(manualLinks.findFetchedByProjectAndCommitIds(eq(projectId), any())).thenReturn(List.of());
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.empty());
		when(reviewContext.build(projectId)).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.SYLLABUS_VERSION, "syllabus:1", "{}", null)));
		when(snapshots.build(any(), any(), any())).thenAnswer(inv -> {
			List<TaskGitCommitLink> given = inv.getArgument(1);
			List<AiEvidenceDraft> out = new ArrayList<>();
			out.add(new AiEvidenceDraft(AiEvidenceType.COMMIT_MESSAGE, "commit:" + commitId, "{}", null));
			for (TaskGitCommitLink link : given) out.add(new AiEvidenceDraft(AiEvidenceType.TASK_FIELD, "task:" + link.getTask().getId(), "{\"linkSource\":\"" + link.getLinkSource() + "\"}", null));
			return out;
		});
	}

	private AiAnalysisSubmissionService service() {
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("remote");
		when(provider.providerConfigHash()).thenReturn("cfg");
		when(provider.modelId()).thenReturn("platform-model");
		AiGitHubCommitEvidenceAcquirer github = mock(AiGitHubCommitEvidenceAcquirer.class);
		when(github.acquire(any())).thenReturn(List.of());
		AiAnalysisSubmissionService service = new AiAnalysisSubmissionService(
				mock(ProjectDataAuthorization.class), commits, links, mock(JiraTaskFailoverItemRepository.class),
				runs, evidence, decisions, snapshots, github,
				mock(AiAnalysisExecutor.class), List.of(provider), new TrackingPlatformTransactionManager(), credentialResolver, courseSettings);
		service.setTeamKeys(teamKeys);
		service.setManualLinks(manualLinks);
		service.setReviewContext(reviewContext);
		return service;
	}

	private TeamAiCredentialService.TeamKeyRef teamKey() {
		return new TeamAiCredentialService.TeamKeyRef(UUID.randomUUID(), "team-fp", new AiProviderBinding(AiProvider.COHERE, "command-a-plus-05-2026"));
	}

	private AiAnalysisProviderDecision savedDecision() {
		ArgumentCaptor<AiAnalysisProviderDecision> captor = ArgumentCaptor.forClass(AiAnalysisProviderDecision.class);
		verify(decisions).save(captor.capture());
		return captor.getValue();
	}

	@SuppressWarnings("unchecked")
	private List<AiAnalysisEvidence> savedEvidence() {
		ArgumentCaptor<List<AiAnalysisEvidence>> captor = ArgumentCaptor.forClass(List.class);
		verify(evidence).saveAll(captor.capture());
		return captor.getValue();
	}

	// ---------------- merge commits

	@Test
	void aMergeCommitIsNeverReviewed_manualRequestGetsAClearError() {
		commit.setParentCount(2);
		assertThatThrownBy(() -> service().submit(userId, projectId, commitId))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_COMMIT_MERGE_NOT_REVIEWED))
				.hasMessageContaining("Merge commit");
		verify(runs, never()).saveAndFlush(any());
	}

	@Test
	void aMergeCommitIsSkippedByAutomationEvenWithATeamKey() {
		commit.setParentCount(2);
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));

		assertThat(service().submitAutomatic(projectId, commitId)).isEmpty();
		verify(runs, never()).saveAndFlush(any());
		verifyNoInteractions(courseSettings);
	}

	// ---------------- who pays

	@Test
	void aTeamKeyPaysForTheReview_evenWhenTheCourseAutomationIsOff() {
		TeamAiCredentialService.TeamKeyRef key = teamKey();
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(key));

		assertThat(service().submitAutomatic(projectId, commitId)).isPresent();

		AiAnalysisProviderDecision decision = savedDecision();
		assertThat(decision.getCredentialSource()).isEqualTo(AiCredentialSource.COURSE);
		assertThat(decision.getTeamCredentialId()).isEqualTo(key.id());
		assertThat(decision.getCourseCredentialId()).isNull();
		assertThat(decision.getCredentialFingerprint()).isEqualTo("team-fp");
		assertThat(decision.getAiProvider()).isEqualTo(AiProvider.COHERE);
		assertThat(decision.getModelId()).isEqualTo("command-a-plus-05-2026");
		verifyNoInteractions(courseSettings, credentialResolver);
	}

	@Test
	void withoutATeamKey_theCourseKeyIsUsedExactlyAsBefore() {
		UUID courseCredential = UUID.randomUUID();
		when(credentialResolver.resolve(eq(courseId), eq(AiAnalysisType.COMMIT_INTELLIGENCE), eq(AiProviderRole.PRIMARY), eq(AiInvocationOrigin.USER_REQUEST)))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, courseCredential, "course-fp"));

		service().submit(userId, projectId, commitId);

		AiAnalysisProviderDecision decision = savedDecision();
		assertThat(decision.getCourseCredentialId()).isEqualTo(courseCredential);
		assertThat(decision.getTeamCredentialId()).isNull();
		assertThat(decision.getCredentialFingerprint()).isEqualTo("course-fp");
	}

	@Test
	void noTeamKeyAndNoCourseKey_isAReadableError() {
		when(credentialResolver.resolve(any(), any(), any(), any())).thenReturn(AiCredentialResolver.Resolution.UNAVAILABLE);
		assertThatThrownBy(() -> service().submit(userId, projectId, commitId))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE))
				.hasMessageContaining("Nhóm chưa nhập key AI");
	}

	@Test
	void switchingFromCourseKeyToTeamKeyStartsAFreshReview_notTheOldRun() {
		when(credentialResolver.resolve(any(), any(), any(), any()))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "course-fp"));
		AiAnalysisSubmissionService service = service();
		service.submit(userId, projectId, commitId);
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));
		service.submit(userId, projectId, commitId);

		ArgumentCaptor<AiAnalysisRun> captor = ArgumentCaptor.forClass(AiAnalysisRun.class);
		verify(runs, org.mockito.Mockito.times(2)).saveAndFlush(captor.capture());
		assertThat(captor.getAllValues().get(0).getIdempotencyKey()).isNotEqualTo(captor.getAllValues().get(1).getIdempotencyKey());
	}

	// ---------------- what the AI is given

	@Test
	void theRunUsesPromptV2_andCarriesSyllabusAndHandAttachedTasks() {
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));
		Task auto = task("SAGA-1");
		Task manual = task("SAGA-2");
		TaskGitCommitLink automatic = new TaskGitCommitLink();
		automatic.setTask(auto);
		automatic.setGitCommit(commit);
		automatic.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		when(links.findAnalysisEvidenceByGitCommitId(commitId, projectId)).thenReturn(List.of(automatic));
		TaskCommitManualLink byHand = new TaskCommitManualLink();
		byHand.setTask(manual);
		byHand.setGitCommit(commit);
		TaskCommitManualLink duplicateOfAuto = new TaskCommitManualLink();
		duplicateOfAuto.setTask(auto);
		duplicateOfAuto.setGitCommit(commit);
		when(manualLinks.findFetchedByProjectAndCommitIds(projectId, List.of(commitId))).thenReturn(List.of(byHand, duplicateOfAuto));

		var submission = service().submit(userId, projectId, commitId);

		assertThat(submission.run().getPromptVersion()).isEqualTo("commit-intelligence-v4");
		List<AiAnalysisEvidence> rows = savedEvidence();
		assertThat(rows).extracting(AiAnalysisEvidence::getEvidenceType).contains(AiEvidenceType.SYLLABUS_VERSION);
		assertThat(rows).filteredOn(r -> r.getEvidenceType() == AiEvidenceType.TASK_FIELD)
				.extracting(AiAnalysisEvidence::getSourceRef)
				.containsExactly("task:" + auto.getId(), "task:" + manual.getId());
		assertThat(rows).filteredOn(r -> r.getSourceRef().equals("task:" + manual.getId()))
				.singleElement().satisfies(r -> assertThat(r.getPayloadJson()).contains("MANUAL"));
	}

	@Test
	void atMostEightTasksGoToTheAi() {
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));
		List<TaskCommitManualLink> many = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			TaskCommitManualLink link = new TaskCommitManualLink();
			link.setTask(task("SAGA-" + i));
			link.setGitCommit(commit);
			many.add(link);
		}
		when(manualLinks.findFetchedByProjectAndCommitIds(projectId, List.of(commitId))).thenReturn(many);

		service().submit(userId, projectId, commitId);

		assertThat(savedEvidence()).filteredOn(r -> r.getEvidenceType() == AiEvidenceType.TASK_FIELD).hasSize(AiAnalysisSubmissionService.MAX_TASKS_IN_EVIDENCE);
	}

	@Test
	void aMergeArrivingByWebhookWithoutParents_isSkippedByItsMessage() {
		commit.setParentCount(null);
		commit.setMessage("Merge pull request #70 from Saga-Learning-to-Hero/dev");
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));

		assertThat(service().submitAutomatic(projectId, commitId)).isEmpty();
		verify(runs, never()).saveAndFlush(any());
	}

	@Test
	void aMergeFoundOnlyWhenReadingGitHubIsRememberedAndNeverReviewed() {
		commit.setParentCount(null);
		commit.setMessage("Sync with upstream");
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));
		AiAnalysisSubmissionService service = service();
		AiGitHubCommitEvidenceAcquirer github = (AiGitHubCommitEvidenceAcquirer) org.springframework.test.util.ReflectionTestUtils.getField(service, "githubEvidence");
		when(github.acquire(any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS, "github-commit-status:abc123",
				"{\"providerEvidenceStatus\":\"AVAILABLE\",\"codeDiffAvailable\":true,\"parentCount\":2}", null)));

		assertThatThrownBy(() -> service.submit(userId, projectId, commitId))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> assertThat(((IntegrationException) ex).getCode()).isEqualTo(IntegrationErrorCode.AI_COMMIT_MERGE_NOT_REVIEWED));
		verify(commits).setParentCountIfUnknown(commitId, 2);
		verify(runs, never()).saveAndFlush(any());
		assertThat(commit.getParentCount()).isEqualTo(2);
	}

	@Test
	void anOrdinaryCommitLearnsItsParentCountAndIsReviewed() {
		commit.setParentCount(null);
		commit.setMessage("feat: SAGA-1 login");
		when(teamKeys.usableKey(projectId)).thenReturn(Optional.of(teamKey()));
		AiAnalysisSubmissionService service = service();
		AiGitHubCommitEvidenceAcquirer github = (AiGitHubCommitEvidenceAcquirer) org.springframework.test.util.ReflectionTestUtils.getField(service, "githubEvidence");
		when(github.acquire(any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS, "github-commit-status:abc123",
				"{\"providerEvidenceStatus\":\"AVAILABLE\",\"parentCount\":1}", null)));

		service.submit(userId, projectId, commitId);

		verify(commits).setParentCountIfUnknown(commitId, 1);
		verify(runs).saveAndFlush(any());
	}

	private Task task(String key) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setExternalKey(key);
		task.setTitle("Task " + key);
		task.setProject(project);
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		task.setJiraIntegration(integration);
		return task;
	}
}

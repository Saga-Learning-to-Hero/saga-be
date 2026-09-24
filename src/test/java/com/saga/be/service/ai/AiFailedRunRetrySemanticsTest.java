package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.controller.LecturerCourseAiController;
import com.saga.be.dto.ai.AiAnalysisResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

/**
 * Current-source regression guard for the distinction between an idempotent POST result and the
 * independently chronological {@code /latest} read and the immutable retry-attempt lineage.
 */
class AiFailedRunRetrySemanticsTest {
	private final UUID actorId = UUID.randomUUID();
	private final UUID courseId = UUID.randomUUID();

	private LecturerCourseAuthorization courseAuth;
	private TeamRepository teams;
	private AiProgressFactsBuilder facts;
	private AiAnalysisRunRepository runs;
	private AiAnalysisEvidenceRepository evidence;
	private AiAnalysisProviderDecisionRepository decisions;
	private AiAnalysisExecutor executor;
	private AiModelProvider provider;
	private AiCredentialResolver credentialResolver;
	private Course course;
	private AtomicReference<AiCredentialResolver.Resolution> resolution;

	@BeforeEach
	void setUp() {
		courseAuth = mock(LecturerCourseAuthorization.class);
		teams = mock(TeamRepository.class);
		facts = mock(AiProgressFactsBuilder.class);
		runs = mock(AiAnalysisRunRepository.class);
		evidence = mock(AiAnalysisEvidenceRepository.class);
		decisions = mock(AiAnalysisProviderDecisionRepository.class);
		executor = mock(AiAnalysisExecutor.class);
		provider = mock(AiModelProvider.class);
		credentialResolver = mock(AiCredentialResolver.class);
		course = new Course();
		course.setId(courseId);
		resolution = new AtomicReference<>();

		when(courseAuth.requireCourse(any(UserAccount.class), any(UUID.class))).thenReturn(course);
		when(teams.findByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of());
		when(facts.buildCourse(any(Course.class), anyList())).thenReturn(new AiProgressFactsBuilder.Facts(Map.of("scope", "COURSE")));
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn("remote-ai");
		when(provider.providerConfigHash()).thenReturn("remote-adapter-config");
		when(provider.modelId()).thenReturn("remote");
		when(credentialResolver.resolve(any(), any(), any(), any())).thenAnswer(ignored -> resolution.get());
	}

	@Test
	void bindingIdentityCreatesOneCanonicalRunPerModelAndRestoresThePriorIdentityWhenSwitchedBack() {
		Map<String, AiAnalysisRun> canonical = new HashMap<>();
		when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString())).thenAnswer(call -> Optional.ofNullable(canonical.get(call.getArgument(0))));
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(call -> {
			AiAnalysisRun run = call.getArgument(0);
			run.setId(UUID.randomUUID());
			canonical.put(run.getCanonicalIdentityKey(), run);
			return run;
		});

		resolution.set(courseResolution("gemini-3.8-flash"));
		var first38 = service().submitCourse(actor(), courseId);
		first38.run().setStatus(AiAnalysisStatus.COMPLETED);

		resolution.set(courseResolution("gemini-3.5-flash-lite"));
		var first35 = service().submitCourse(actor(), courseId);
		first35.run().setStatus(AiAnalysisStatus.COMPLETED);

		resolution.set(courseResolution("gemini-3.8-flash"));
		var second38 = service().submitCourse(actor(), courseId);

		assertThat(first38.created()).isTrue();
		assertThat(first35.created()).isTrue();
		assertThat(second38.created()).isFalse();
		assertThat(second38.run().getId()).isEqualTo(first38.run().getId());
		assertThat(first38.run().getIdempotencyKey()).isNotEqualTo(first35.run().getIdempotencyKey());
		assertThat(canonical).hasSize(2);
		verify(runs, org.mockito.Mockito.times(2)).saveAndFlush(any(AiAnalysisRun.class));
		verify(provider, never()).analyze(any());
	}

	@Test
	void succeededQueuedAndRunningExactMatchesAreReusedWithoutCreatingAnotherRun() {
		for (AiAnalysisStatus status : List.of(AiAnalysisStatus.COMPLETED, AiAnalysisStatus.QUEUED, AiAnalysisStatus.RUNNING)) {
			AiAnalysisRun existing = new AiAnalysisRun();
			existing.setId(UUID.randomUUID());
			existing.setStatus(status);
			existing.setRetryAttempt(0);
			when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString())).thenReturn(Optional.of(existing));
			resolution.set(courseResolution("gemini-3.8-flash"));

			var submission = service().submitCourse(actor(), courseId);

			assertThat(submission.created()).isFalse();
			assertThat(submission.run()).isSameAs(existing);
		}

		verify(runs, never()).saveAndFlush(any(AiAnalysisRun.class));
		verify(provider, never()).analyze(any());
	}

	@Test
	void manualFailedRunCreatesMonotonicImmutableAttemptsAndReturnsTheEffectiveAttempt() {
		Map<String, AiAnalysisRun> canonical = new HashMap<>();
		when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString()))
				.thenAnswer(call -> Optional.ofNullable(canonical.get(call.getArgument(0))));
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(call -> {
			AiAnalysisRun run = call.getArgument(0);
			run.setId(UUID.randomUUID());
			canonical.put(run.getCanonicalIdentityKey(), run);
			return run;
		});
		resolution.set(courseResolution("gemini-3.8-flash"));

		var attempt0 = service().submitCourse(actor(), courseId);
		attempt0.run().setStatus(AiAnalysisStatus.FAILED);
		attempt0.run().setFailureCode("AI_PROVIDER_FAILED");
		var attempt1 = service().submitCourse(actor(), courseId);
		attempt1.run().setStatus(AiAnalysisStatus.RUNNING);
		var runningRepeat = service().submitCourse(actor(), courseId);
		attempt1.run().setStatus(AiAnalysisStatus.FAILED);
		var attempt2 = service().submitCourse(actor(), courseId);
		attempt2.run().setStatus(AiAnalysisStatus.COMPLETED);
		var completedRepeat = service().submitCourse(actor(), courseId);

		assertThat(attempt0.created()).isTrue();
		assertThat(attempt1.created()).isTrue();
		assertThat(attempt2.created()).isTrue();
		assertThat(attempt0.run().getRetryAttempt()).isZero();
		assertThat(attempt1.run().getRetryAttempt()).isEqualTo(1);
		assertThat(attempt2.run().getRetryAttempt()).isEqualTo(2);
		assertThat(attempt1.run().getCanonicalIdentityKey()).isEqualTo(attempt0.run().getCanonicalIdentityKey());
		assertThat(attempt2.run().getCanonicalIdentityKey()).isEqualTo(attempt0.run().getCanonicalIdentityKey());
		assertThat(attempt1.run().getIdempotencyKey()).isNotEqualTo(attempt0.run().getIdempotencyKey());
		assertThat(attempt2.run().getIdempotencyKey()).isNotEqualTo(attempt1.run().getIdempotencyKey());
		assertThat(attempt0.run().getStatus()).isEqualTo(AiAnalysisStatus.FAILED);
		assertThat(runningRepeat.created()).isFalse();
		assertThat(runningRepeat.run()).isSameAs(attempt1.run());
		assertThat(completedRepeat.created()).isFalse();
		assertThat(completedRepeat.run()).isSameAs(attempt2.run());
		verify(runs, org.mockito.Mockito.times(3)).saveAndFlush(any(AiAnalysisRun.class));
	}

	@Test
	void concurrentManualRetriesReturnOneAttemptOneRun() {
		AiAnalysisRun failed = new AiAnalysisRun();
		failed.setId(UUID.randomUUID());
		failed.setStatus(AiAnalysisStatus.FAILED);
		failed.setRetryAttempt(0);
		AiAnalysisRun winner = new AiAnalysisRun();
		winner.setId(UUID.randomUUID());
		winner.setStatus(AiAnalysisStatus.QUEUED);
		winner.setRetryAttempt(1);
		when(runs.findTopByCanonicalIdentityKeyOrderByRetryAttemptDesc(anyString())).thenReturn(Optional.of(failed));
		when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenThrow(new DataIntegrityViolationException("duplicate retry attempt"));
		when(runs.findByCanonicalIdentityKeyAndRetryAttempt(anyString(), org.mockito.ArgumentMatchers.eq(1))).thenReturn(Optional.of(winner));
		resolution.set(courseResolution("gemini-3.8-flash"));

		var submission = service().submitCourse(actor(), courseId);

		assertThat(submission.created()).isFalse();
		assertThat(submission.run()).isSameAs(winner);
		verify(runs).saveAndFlush(any(AiAnalysisRun.class));
		verify(runs).findByCanonicalIdentityKeyAndRetryAttempt(anyString(), org.mockito.ArgumentMatchers.eq(1));
	}

	@Test
	void coursePostReturnsItsCanonicalMatchWhileLatestCanIndependentlyReturnTheLaterRun() {
		AiAnalysisRun runA = run("gemini-3.8-flash", LocalDateTime.of(2026, 9, 25, 10, 0));
		AiAnalysisRun runB = run("gemini-3.5-flash-lite", LocalDateTime.of(2026, 9, 25, 11, 0));

		AiProgressNarrativeSubmissionService submissions = mock(AiProgressNarrativeSubmissionService.class);
		AiCourseAnalysisReadService reads = mock(AiCourseAnalysisReadService.class);
		UserAccountRepository users = mock(UserAccountRepository.class);
		UserAccount actor = actor();
		SagaUserPrincipal principal = new SagaUserPrincipal(actorId, "lecturer@example.test", "lecturer", "Lecturer", null, AccountRole.LECTURER, false);
		AiAnalysisResponse postBody = mock(AiAnalysisResponse.class);
		AiAnalysisResponse latestBody = mock(AiAnalysisResponse.class);
		when(users.findById(actorId)).thenReturn(Optional.of(actor));
		when(submissions.submitCourse(actor, courseId)).thenReturn(new AiProgressNarrativeSubmissionService.Submission(runA, false));
		when(reads.get(actor, courseId, runA.getId())).thenReturn(postBody);
		when(reads.latest(actor, courseId, AiArtifactType.COURSE, courseId, AiAnalysisType.PROGRESS_NARRATIVE))
				.thenReturn(com.saga.be.dto.ai.AiLatestAnalysisResponse.found(latestBody));

		LecturerCourseAiController controller = new LecturerCourseAiController(submissions, reads, mock(AiProgressReportExportService.class), users);
		var post = controller.submitCourseProgress(principal, courseId);
		var latest = controller.latestCourseProgress(principal, courseId);

		assertThat(post.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(post.getBody()).isSameAs(postBody);
		assertThat(latest.analysis()).isSameAs(latestBody);
		assertThat(runB.getCreatedAt()).isAfter(runA.getCreatedAt());
		verify(reads).get(actor, courseId, runA.getId());
	}

	@Test
	void latestReadUsesTheChronologicallyNewerRunRatherThanTheCanonicalPostMatch() {
		AiAnalysisRun runA = run("gemini-3.8-flash", LocalDateTime.of(2026, 9, 25, 10, 0));
		AiAnalysisRun runB = run("gemini-3.5-flash-lite", LocalDateTime.of(2026, 9, 25, 11, 0));
		when(runs.findTopByCourse_IdAndArtifactTypeAndArtifactIdAndAnalysisTypeOrderByCreatedAtDesc(
				courseId, AiArtifactType.COURSE, courseId, AiAnalysisType.PROGRESS_NARRATIVE)).thenReturn(Optional.of(runB));
		when(evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(runB.getId())).thenReturn(List.of());
		when(decisions.findByAnalysisRun_Id(runB.getId())).thenReturn(Optional.empty());

		var latest = new AiCourseAnalysisReadService(runs, evidence, decisions, courseAuth)
				.latest(actor(), courseId, AiArtifactType.COURSE, courseId, AiAnalysisType.PROGRESS_NARRATIVE);

		assertThat(latest.analysis().id()).isEqualTo(runB.getId());
		assertThat(runB.getCreatedAt()).isAfter(runA.getCreatedAt());
	}

	private AiProgressNarrativeSubmissionService service() {
		return new AiProgressNarrativeSubmissionService(
				mock(ProjectDataAuthorization.class), courseAuth, mock(ProjectRepository.class), teams, facts, runs, evidence, decisions,
				executor, List.of(provider), new TrackingPlatformTransactionManager(), new ObjectMapper(), credentialResolver);
	}

	private AiCredentialResolver.Resolution courseResolution(String modelId) {
		return new AiCredentialResolver.Resolution(
				AiCredentialResolver.Outcome.COURSE,
				UUID.randomUUID(),
				"credential-fingerprint",
				new AiProviderBinding(AiProvider.GEMINI, modelId));
	}

	private UserAccount actor() {
		UserAccount actor = new UserAccount();
		actor.setId(actorId);
		return actor;
	}

	private AiAnalysisRun run(String modelId, LocalDateTime createdAt) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(UUID.randomUUID());
		run.setCourse(course);
		run.setArtifactType(AiArtifactType.COURSE);
		run.setArtifactId(courseId);
		run.setArtifactRevision("revision-" + modelId);
		run.setAnalysisType(AiAnalysisType.PROGRESS_NARRATIVE);
		run.setStatus(AiAnalysisStatus.FAILED);
		run.setCreatedAt(createdAt);
		return run;
	}
}

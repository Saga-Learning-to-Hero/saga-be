package com.saga.be.service.ai;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.AiTaskIntelligenceRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Production-like, no-component-scan context for the exact AI automation graph. The five graph
 * classes are real beans; only repositories/providers are leaves mocked at the boundary.
 */
@SpringJUnitConfig(AiAutomationEventWiringContextTest.Config.class)
class AiAutomationEventWiringContextTest {

	private static final UUID PROJECT_ID = UUID.randomUUID();
	private static final UUID TASK_ID = UUID.randomUUID();

	@org.springframework.beans.factory.annotation.Autowired
	private ApplicationEventPublisher events;
	@org.springframework.beans.factory.annotation.Autowired
	private PlatformTransactionManager transactions;
	@org.springframework.beans.factory.annotation.Autowired
	private TaskRepository tasks;
	@org.springframework.beans.factory.annotation.Autowired
	private AiAnalysisRunRepository runs;
	@org.springframework.beans.factory.annotation.Autowired
	private AiTaskIntelligenceSnapshotBuilder snapshots;
	@org.springframework.beans.factory.annotation.Autowired
	private AiCredentialResolver credentials;
	@org.springframework.beans.factory.annotation.Autowired
	private CourseAiSettingsService settings;
	@org.springframework.beans.factory.annotation.Autowired
	private AiModelProvider provider;

	@BeforeEach
	void resetLeaves() {
		reset(tasks);
		when(tasks.findActiveFetchedByIdAndProject_Id(TASK_ID, PROJECT_ID)).thenReturn(Optional.empty());
	}

	@Test
	void realAutomationGraphStartsWithCircularReferencesDisabled() {
		// Reaching this method proves the real dispatcher -> execution -> finalizer path and the
		// real risk listener/submission path were constructed without a circular reference.
	}

	@Test
	void committedEventInvokesRealRiskAutomationOnce() {
		new TransactionTemplate(transactions)
				.executeWithoutResult(status -> events.publishEvent(new TaskIntelligenceCompletedEvent(PROJECT_ID, TASK_ID)));

		verify(tasks, times(1)).findActiveFetchedByIdAndProject_Id(TASK_ID, PROJECT_ID);
	}

	@Test
	void rolledBackEventDoesNotInvokeRiskAutomation() {
		assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactions)
				.executeWithoutResult(status -> {
					events.publishEvent(new TaskIntelligenceCompletedEvent(PROJECT_ID, TASK_ID));
					throw new IllegalStateException("rollback");
				}));

		verify(tasks, never()).findActiveFetchedByIdAndProject_Id(TASK_ID, PROJECT_ID);
	}

	@Test
	void duplicateCommittedEventsNeverReachAProviderWhenAutomationHasNoCourseCredential() {
		UUID courseId = UUID.randomUUID();
		Course course = new Course();
		course.setId(courseId);
		Project project = new Project();
		project.setId(PROJECT_ID);
		project.setCourse(course);
		Task task = new Task();
		task.setId(TASK_ID);
		task.setProject(project);
		when(tasks.findActiveFetchedByIdAndProject_Id(TASK_ID, PROJECT_ID)).thenReturn(Optional.of(task));
		when(settings.get(courseId)).thenReturn(new CourseAiSettingsService.Settings(true, false));
		when(credentials.resolve(courseId, AiAnalysisType.RISK_ANALYSIS, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, UUID.randomUUID(), "credential-fingerprint"));
		when(snapshots.build(task)).thenReturn(List.of());
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerConfigHash()).thenReturn("provider-config");
		when(provider.providerKey()).thenReturn("primary");
		when(provider.modelId()).thenReturn("test-model");
		java.util.Map<String, AiAnalysisRun> saved = new java.util.HashMap<>();
		when(runs.findByIdempotencyKey(org.mockito.ArgumentMatchers.anyString()))
				.thenAnswer(invocation -> Optional.ofNullable(saved.get(invocation.getArgument(0))));
		when(runs.saveAndFlush(org.mockito.ArgumentMatchers.any(AiAnalysisRun.class))).thenAnswer(invocation -> {
			AiAnalysisRun run = invocation.getArgument(0);
			run.setId(UUID.randomUUID());
			saved.put(run.getIdempotencyKey(), run);
			return run;
		});

		new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new TaskIntelligenceCompletedEvent(PROJECT_ID, TASK_ID)));
		new TransactionTemplate(transactions).executeWithoutResult(status -> events.publishEvent(new TaskIntelligenceCompletedEvent(PROJECT_ID, TASK_ID)));

		verify(runs, times(1)).saveAndFlush(org.mockito.ArgumentMatchers.any(AiAnalysisRun.class));
		verify(provider, never()).analyze(org.mockito.ArgumentMatchers.any());
	}

	@Configuration(proxyBeanMethods = false)
	@EnableTransactionManagement
	static class Config {
		@Bean
		PlatformTransactionManager transactionManager() {
			return new TestTransactionManager();
		}

		@Bean(name = "aiAnalysisExecutor")
		Executor analysisExecutor() {
			return Runnable::run;
		}

		@Bean
		TaskRepository taskRepository() { return org.mockito.Mockito.mock(TaskRepository.class); }
		@Bean
		ProjectRepository projectRepository() { return org.mockito.Mockito.mock(ProjectRepository.class); }
		@Bean
		AiAnalysisRunRepository aiAnalysisRunRepository() { return org.mockito.Mockito.mock(AiAnalysisRunRepository.class); }
		@Bean
		AiAnalysisEvidenceRepository aiAnalysisEvidenceRepository() { return org.mockito.Mockito.mock(AiAnalysisEvidenceRepository.class); }
		@Bean
		AiAnalysisProviderDecisionRepository aiAnalysisProviderDecisionRepository() { return org.mockito.Mockito.mock(AiAnalysisProviderDecisionRepository.class); }
		@Bean
		AiTaskIntelligenceRepository aiTaskIntelligenceRepository() { return org.mockito.Mockito.mock(AiTaskIntelligenceRepository.class); }
		@Bean
		ProjectDataAuthorization projectDataAuthorization() { return org.mockito.Mockito.mock(ProjectDataAuthorization.class); }
		@Bean
		AiTaskIntelligenceSnapshotBuilder taskSnapshots() { return org.mockito.Mockito.mock(AiTaskIntelligenceSnapshotBuilder.class); }
		@Bean
		AiProgressFactsBuilder progressFacts() { return org.mockito.Mockito.mock(AiProgressFactsBuilder.class); }
		@Bean
		AiCredentialResolver credentialResolver() { return org.mockito.Mockito.mock(AiCredentialResolver.class); }
		@Bean
		CourseAiSettingsService courseAiSettingsService() { return org.mockito.Mockito.mock(CourseAiSettingsService.class); }
		@Bean
		AiAnalysisStateService analysisState() { return org.mockito.Mockito.mock(AiAnalysisStateService.class); }
		@Bean
		AiModelProvider primaryProvider() { return org.mockito.Mockito.mock(AiModelProvider.class); }
		@Bean
		AiAcademicProposalFinalizationService academicFinalizer() { return org.mockito.Mockito.mock(AiAcademicProposalFinalizationService.class); }
		@Bean
		AiRiskAnalysisFinalizationService riskFinalizer() { return org.mockito.Mockito.mock(AiRiskAnalysisFinalizationService.class); }
		@Bean
		AiProgressNarrativeFinalizationService progressFinalizer() { return org.mockito.Mockito.mock(AiProgressNarrativeFinalizationService.class); }
		@Bean
		AiSecondaryBrainService secondaryBrain() { return org.mockito.Mockito.mock(AiSecondaryBrainService.class); }
		@Bean
		AiAdjudicationService adjudication() { return org.mockito.Mockito.mock(AiAdjudicationService.class); }
		@Bean
		ObjectMapper objectMapper() { return new ObjectMapper(); }

		@Bean
		AiTaskIntelligenceFinalizationService taskFinalizer(
				AiAnalysisRunRepository runs,
				AiAnalysisProviderDecisionRepository decisions,
				AiTaskIntelligenceRepository results,
				TaskRepository tasks,
				ApplicationEventPublisher events) {
			return new AiTaskIntelligenceFinalizationService(runs, decisions, results, tasks, events);
		}

		@Bean
		AiAnalysisExecutionService execution(
				AiAnalysisStateService state,
				List<AiModelProvider> providers,
				ObjectMapper mapper,
				AiAcademicProposalFinalizationService academicFinalizer,
				AiTaskIntelligenceFinalizationService taskFinalizer,
				AiRiskAnalysisFinalizationService riskFinalizer,
				AiProgressNarrativeFinalizationService progressFinalizer,
				AiSecondaryBrainService secondaryBrain,
				AiAdjudicationService adjudication,
				AiCredentialResolver credentialResolver) {
			return new AiAnalysisExecutionService(
					state, providers, new AiStructuredResultValidator(), mapper,
					new AiAcademicResultValidator(mapper), academicFinalizer,
					new AiTaskIntelligenceResultValidator(), taskFinalizer,
					new AiRiskAnalysisResultValidator(), riskFinalizer,
					new AiProgressNarrativeResultValidator(), progressFinalizer,
					secondaryBrain, adjudication, credentialResolver);
		}

		@Bean
		AiAnalysisExecutor dispatcher(Executor executor, AiAnalysisExecutionService execution, AiAnalysisStateService state) {
			return new AiAnalysisExecutor(executor, execution, state);
		}

		@Bean
		AiRiskAnalysisSubmissionService riskSubmissions(
				ProjectDataAuthorization auth,
				TaskRepository tasks,
				ProjectRepository projects,
				AiTaskIntelligenceSnapshotBuilder snapshots,
				AiTaskIntelligenceRepository prior,
				AiProgressFactsBuilder facts,
				ObjectMapper mapper,
				AiAnalysisRunRepository runs,
				AiAnalysisEvidenceRepository evidence,
				AiAnalysisProviderDecisionRepository decisions,
				AiAnalysisExecutor dispatcher,
				List<AiModelProvider> providers,
				PlatformTransactionManager manager,
				AiCredentialResolver credentials,
				CourseAiSettingsService settings) {
			return new AiRiskAnalysisSubmissionService(
					auth, tasks, projects, snapshots, prior, facts, mapper, runs, evidence, decisions, dispatcher,
					providers, manager, credentials, settings);
		}

		@Bean
		AiRiskAutomationTrigger riskAutomation(AiRiskAnalysisSubmissionService submissions) {
			return new AiRiskAutomationTrigger(submissions);
		}
	}

	static class TestTransactionManager extends AbstractPlatformTransactionManager {
		@Override protected Object doGetTransaction() { return new Object(); }
		@Override protected void doBegin(Object transaction, TransactionDefinition definition) {}
		@Override protected void doCommit(DefaultTransactionStatus status) {}
		@Override protected void doRollback(DefaultTransactionStatus status) {}
	}
}

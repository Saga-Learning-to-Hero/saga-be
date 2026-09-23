package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.controller.LecturerCourseAiController;
import com.saga.be.repository.*;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.projection.ProjectDataAuthorization;
import com.saga.be.service.task.TaskDeadlineWarningService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Regression coverage for the production startup failure fixed alongside V31: {@code
 * AiProgressFactsBuilder}, {@code AiTaskIntelligenceSnapshotBuilder}, and {@code
 * TaskDeadlineWarningService} each had a single constructor that required a {@code java.time.Clock}
 * parameter with no {@code @Autowired} fallback -- and production registers no {@code Clock} bean
 * anywhere -- so Spring failed bean creation with "No qualifying bean of type 'java.time.Clock'
 * available" the moment any of them was reached while building the dependency graph rooted at
 * {@code LecturerCourseAiController}.
 *
 * <p>Like {@link RemoteAiModelProviderContextTest}, this drives real Spring constructor-resolution
 * (not manual {@code new ...(...)} construction) via {@link ApplicationContextRunner}, registering
 * the classes actually under test unmocked so Spring itself picks their {@code @Autowired}
 * constructor exactly as it does in production, while every lower-level repository/service
 * dependency is a plain mock. No {@code java.time.Clock} bean is registered anywhere in this test:
 * if any of the three classes regresses back to a single Clock-requiring constructor, context
 * refresh fails here exactly as it did in production.
 */
class AiBigBuildClockWiringContextTest {

	@Test
	void progressFactsBuilderChainUpToTheLecturerControllerWiresWithoutAClockBean() {
		new ApplicationContextRunner()
				.withUserConfiguration(
						MockedCollaboratorsConfig.class,
						AiProgressFactsBuilder.class,
						AiProgressNarrativeSubmissionService.class,
						LecturerCourseAiController.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(java.time.Clock.class);
					assertThat(context).hasSingleBean(AiProgressFactsBuilder.class);
					assertThat(context).hasSingleBean(AiProgressNarrativeSubmissionService.class);
					assertThat(context).hasSingleBean(LecturerCourseAiController.class);
				});
	}

	@Test
	void taskIntelligenceSnapshotBuilderWiresWithoutAClockBean() {
		new ApplicationContextRunner()
				.withUserConfiguration(MockedCollaboratorsConfig.class, AiTaskIntelligenceSnapshotBuilder.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(java.time.Clock.class);
					assertThat(context).hasSingleBean(AiTaskIntelligenceSnapshotBuilder.class);
				});
	}

	@Test
	void taskDeadlineWarningServiceWiresWithoutAClockBean() {
		new ApplicationContextRunner()
				.withUserConfiguration(MockedCollaboratorsConfig.class, TaskDeadlineWarningService.class)
				.run(context -> {
					assertThat(context).hasNotFailed();
					assertThat(context).doesNotHaveBean(java.time.Clock.class);
					assertThat(context).hasSingleBean(TaskDeadlineWarningService.class);
				});
	}

	/**
	 * Every direct dependency of the five classes above, one level down, as plain mocks -- never
	 * the classes under test themselves. Deliberately registers no {@code java.time.Clock} bean.
	 */
	@Configuration
	static class MockedCollaboratorsConfig {
		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		TaskDeadlineProperties taskDeadlineProperties() {
			return new TaskDeadlineProperties();
		}

		@Bean
		TaskRepository taskRepository() {
			return mock(TaskRepository.class);
		}

		@Bean
		AiTaskIntelligenceRepository aiTaskIntelligenceRepository() {
			return mock(AiTaskIntelligenceRepository.class);
		}

		@Bean
		AiRiskAnalysisRepository aiRiskAnalysisRepository() {
			return mock(AiRiskAnalysisRepository.class);
		}

		@Bean
		TeamRepository teamRepository() {
			return mock(TeamRepository.class);
		}

		@Bean
		TeamMemberRepository teamMemberRepository() {
			return mock(TeamMemberRepository.class);
		}

		@Bean
		TaskGitCommitLinkRepository taskGitCommitLinkRepository() {
			return mock(TaskGitCommitLinkRepository.class);
		}

		@Bean
		TaskWorkSessionRepository taskWorkSessionRepository() {
			return mock(TaskWorkSessionRepository.class);
		}

		@Bean
		BusinessWarningRepository businessWarningRepository() {
			return mock(BusinessWarningRepository.class);
		}

		@Bean
		com.saga.be.service.notification.NotificationService notificationService() {
			return mock(com.saga.be.service.notification.NotificationService.class);
		}

		@Bean
		ProjectDataAuthorization projectDataAuthorization() {
			return mock(ProjectDataAuthorization.class);
		}

		@Bean
		LecturerCourseAuthorization lecturerCourseAuthorization() {
			return mock(LecturerCourseAuthorization.class);
		}

		@Bean
		ProjectRepository projectRepository() {
			return mock(ProjectRepository.class);
		}

		@Bean
		AiAnalysisRunRepository aiAnalysisRunRepository() {
			return mock(AiAnalysisRunRepository.class);
		}

		@Bean
		AiAnalysisEvidenceRepository aiAnalysisEvidenceRepository() {
			return mock(AiAnalysisEvidenceRepository.class);
		}

		@Bean
		AiAnalysisProviderDecisionRepository aiAnalysisProviderDecisionRepository() {
			return mock(AiAnalysisProviderDecisionRepository.class);
		}

		@Bean
		AiAnalysisExecutor aiAnalysisExecutor() {
			return mock(AiAnalysisExecutor.class);
		}

		@Bean
		PlatformTransactionManager platformTransactionManager() {
			return mock(PlatformTransactionManager.class);
		}

		@Bean
		AiCourseAnalysisReadService aiCourseAnalysisReadService() {
			return mock(AiCourseAnalysisReadService.class);
		}

		@Bean
		AiProgressReportExportService aiProgressReportExportService() {
			return mock(AiProgressReportExportService.class);
		}

		@Bean
		UserAccountRepository userAccountRepository() {
			return mock(UserAccountRepository.class);
		}
	}
}

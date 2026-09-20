package com.saga.be.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.service.identity.ProjectIntegrationService;
import com.saga.be.service.projection.JiraFailoverExecutionService;
import com.saga.be.service.projection.JiraFailoverPreviewService;
import com.saga.be.service.projection.ProjectJiraTaskCommandService;
import com.saga.be.service.sync.ProjectManualSyncService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ProjectIntegrationControllerWiringTest {

	@Test
	void springConstructsControllerThroughItsSingleRequiredConstructor() {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.registerBean(ProjectIntegrationService.class, () -> mock(ProjectIntegrationService.class));
			context.registerBean(ProjectJiraTaskCommandService.class, () -> mock(ProjectJiraTaskCommandService.class));
			context.registerBean(ProjectManualSyncService.class, () -> mock(ProjectManualSyncService.class));
			context.registerBean(JiraFailoverPreviewService.class, () -> mock(JiraFailoverPreviewService.class));
			context.registerBean(JiraFailoverExecutionService.class, () -> mock(JiraFailoverExecutionService.class));
			context.registerBean(IntegrationProperties.class, IntegrationProperties::new);
			context.register(ProjectIntegrationController.class);

			context.refresh();

			assertThat(context.getBean(ProjectIntegrationController.class)).isNotNull();
		}
	}
}

package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class JiraTaskProvenanceValidationTest {

	private Project project;
	private JiraIntegration integration;

	@BeforeEach
	void setUp() {
		project = new Project();
		project.setId(UUID.randomUUID());
		integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
	}

	@Test
	void happyPath_taskAndSprintShareExpectedSource() {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setJiraIntegration(integration);

		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		task.setJiraIntegration(integration);
		task.setSprint(sprint);

		assertThatCode(() -> JiraTaskProjectionService.assertTaskProvenance(task, integration))
				.doesNotThrowAnyException();
	}

	@Test
	void rejectsWhenTaskProjectDiffersFromIntegrationProject() {
		Project other = new Project();
		other.setId(UUID.randomUUID());

		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(other);
		task.setJiraIntegration(integration);

		assertThatThrownBy(() -> JiraTaskProjectionService.assertTaskProvenance(task, integration))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException ie = (IntegrationException) ex;
					assertThat(ie.getCode()).isEqualTo(IntegrationErrorCode.INTEGRATION_UNAVAILABLE);
					assertThat(ie.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(ie.getMessage()).contains("Task project must match its Jira integration project");
				});
	}

	@Test
	void rejectsWhenSprintBelongsToDifferentSource() {
		JiraIntegration otherSource = new JiraIntegration();
		otherSource.setId(UUID.randomUUID());
		otherSource.setProject(project);

		Sprint foreignSprint = new Sprint();
		foreignSprint.setId(UUID.randomUUID());
		foreignSprint.setJiraIntegration(otherSource);

		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setProject(project);
		task.setJiraIntegration(integration);
		task.setSprint(foreignSprint);

		assertThatThrownBy(() -> JiraTaskProjectionService.assertTaskProvenance(task, integration))
				.isInstanceOf(IntegrationException.class)
				.satisfies(ex -> {
					IntegrationException ie = (IntegrationException) ex;
					assertThat(ie.getCode()).isEqualTo(IntegrationErrorCode.JIRA_SPRINT_INVALID);
					assertThat(ie.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
					assertThat(ie.getMessage()).contains("Task Jira source must match Sprint Jira source");
				});
	}

	@Test
	void acceptsBacklogWhenSprintIsNull() {
		Task backlog = new Task();
		backlog.setId(UUID.randomUUID());
		backlog.setProject(project);
		backlog.setJiraIntegration(integration);
		backlog.setSprint(null);

		assertThatCode(() -> JiraTaskProjectionService.assertTaskProvenance(backlog, integration))
				.doesNotThrowAnyException();
	}
}

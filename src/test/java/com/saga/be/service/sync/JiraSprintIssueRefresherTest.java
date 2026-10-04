package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSearchPage;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.service.projection.JiraTaskProjectionService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class JiraSprintIssueRefresherTest {

	private JiraIntegrationRepository integrations;
	private JiraTeamTokenService tokens;
	private JiraIssueWriteClient jiraFields;
	private JiraOAuthClient jira;
	private JiraTaskProjectionService projection;
	private ProjectRealtimePublisher realtime;
	private JiraSprintIssueRefresher refresher;
	private JiraIntegration integration;

	@BeforeEach
	void setUp() {
		integrations = mock(JiraIntegrationRepository.class);
		tokens = mock(JiraTeamTokenService.class);
		jiraFields = mock(JiraIssueWriteClient.class);
		jira = mock(JiraOAuthClient.class);
		projection = mock(JiraTaskProjectionService.class);
		realtime = mock(ProjectRealtimePublisher.class);
		PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
		when(transactions.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
		refresher = new JiraSprintIssueRefresher(integrations, tokens, jiraFields, jira, projection, realtime, transactions);
		Project project = new Project();
		project.setId(UUID.randomUUID());
		integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setProjectKey("SG");
		integration.setCloudId("cloud-sg");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(integrations.findFetchedById(integration.getId())).thenReturn(Optional.of(integration));
		when(tokens.accessToken(integration)).thenReturn("token");
		when(jiraFields.resolveStoryPointsFieldId("token", "cloud-sg")).thenReturn("customfield_10016");
		when(jiraFields.resolveSprintFieldId("token", "cloud-sg")).thenReturn("customfield_10020");
		when(jiraFields.resolveStartDateFieldId("token", "cloud-sg")).thenReturn("customfield_10015");
	}

	@Test
	void everyPageOfTheSprintsIssuesIsUpsertedAndTheProjectIsTold() {
		IssueSummary first = mock(IssueSummary.class);
		IssueSummary second = mock(IssueSummary.class);
		when(jira.searchIssues("token", "cloud-sg", "SG", null, 100, "customfield_10016", "customfield_10020", "customfield_10015", "12"))
				.thenReturn(new IssueSearchPage(List.of(first), "tok-2", false, 100));
		when(jira.searchIssues("token", "cloud-sg", "SG", "tok-2", 100, "customfield_10016", "customfield_10020", "customfield_10015", "12"))
				.thenReturn(new IssueSearchPage(List.of(second), null, true, 100));
		when(projection.upsertBatch(eq(integration), eq("SG"), any())).thenReturn(1);

		assertThat(refresher.refresh(integration.getId(), " 12 ")).isEqualTo(2);

		verify(projection).upsertBatch(integration, "SG", List.of(first));
		verify(projection).upsertBatch(integration, "SG", List.of(second));
		verify(realtime).publish(ProjectRealtimeEventType.TASKS_CHANGED, integration.getProject().getId());
		verify(realtime).publish(ProjectRealtimeEventType.SPRINTS_CHANGED, integration.getProject().getId());
	}

	@Test
	void anInactiveSourceOrANonNumericSprintIsLeftAlone() {
		assertThat(refresher.refresh(integration.getId(), "12 OR 1=1")).isZero();
		integration.setConnectionStatus(IntegrationStatus.REVOKED);
		assertThat(refresher.refresh(integration.getId(), "12")).isZero();

		verifyNoInteractions(tokens, jira, projection, realtime);
	}

	@Test
	void anEmptySprintChangesNothingAndAFailureIsSwallowedByTheAsyncEntry() {
		when(jira.searchIssues(any(), any(), any(), any(), eq(100), any(), any(), any(), eq("12")))
				.thenReturn(new IssueSearchPage(List.of(), null, true, 100));
		assertThat(refresher.refresh(integration.getId(), "12")).isZero();
		verify(projection, never()).upsertBatch(any(), any(), any());
		verify(realtime, never()).publish(any(), any());

		when(tokens.accessToken(integration)).thenThrow(new IllegalStateException("token"));
		refresher.refreshAsync(integration.getId(), "12"); // logged, not thrown
	}
}

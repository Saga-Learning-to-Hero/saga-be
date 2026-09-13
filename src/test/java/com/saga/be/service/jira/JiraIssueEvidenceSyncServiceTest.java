package com.saga.be.service.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskFileProperties;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraCloudWorkClient;
import com.saga.be.integration.jira.JiraCloudWorkClient.EvidenceSearchPage;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Proves JiraIssueEvidenceSyncService.syncIntegration correctly consumes {@link
 * JiraCloudWorkClient.EvidenceSearchPage}'s cursor-based pagination (the shape returned by the
 * now-fixed {@code /rest/api/3/search/jql} endpoint) instead of the old numeric-offset loop that
 * called the removed {@code /rest/api/3/search} endpoint.
 */
@ExtendWith(MockitoExtension.class)
class JiraIssueEvidenceSyncServiceTest {

	@Mock
	private JiraIntegrationRepository integrations;
	@Mock
	private JiraTeamTokenService tokens;
	@Mock
	private JiraCloudWorkClient jira;
	@Mock
	private TaskRepository tasks;
	@Mock
	private TaskWebLinkRepository links;
	@Mock
	private TaskFileRepository files;
	@Mock
	private TaskAttachmentRepository attachments;

	private JiraIssueEvidenceSyncService service;
	private JiraIntegration integration;

	@BeforeEach
	void setUp() {
		service = new JiraIssueEvidenceSyncService(
				integrations, tokens, jira, tasks, links, files, attachments, new TaskFileProperties(), new ObjectMapper());
		Project project = new Project();
		project.setId(UUID.randomUUID());
		integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setCloudId("cloud-1");
		integration.setProjectKey("SAGA");
		when(tokens.accessToken(integration)).thenReturn("token");
		org.mockito.Mockito.lenient().when(tasks.save(any())).thenAnswer(inv -> {
			Task task = inv.getArgument(0);
			if (task.getId() == null) {
				task.setId(UUID.randomUUID());
			}
			return task;
		});
	}

	@Test
	void syncIntegration_singlePage_processesAllIssuesAndStops() {
		when(tasks.findByProject_IdAndExternalId(any(), eq("10001"))).thenReturn(Optional.empty());
		when(tasks.findByProject_IdAndExternalId(any(), eq("10002"))).thenReturn(Optional.empty());
		when(jira.searchIssues("token", "cloud-1", "SAGA", null, 50))
				.thenReturn(new EvidenceSearchPage(List.of(issue("10001", "SAGA-1"), issue("10002", "SAGA-2")), null, true));

		int processed = service.syncIntegration(integration);

		assertThat(processed).isEqualTo(2);
		org.mockito.Mockito.verify(jira, org.mockito.Mockito.times(1)).searchIssues(any(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt());
	}

	@Test
	void syncIntegration_multiplePages_followsNextPageTokenUntilLast() {
		when(tasks.findByProject_IdAndExternalId(any(), any())).thenReturn(Optional.empty());
		when(jira.searchIssues("token", "cloud-1", "SAGA", null, 50))
				.thenReturn(new EvidenceSearchPage(List.of(issue("10001", "SAGA-1")), "tok-2", false));
		when(jira.searchIssues("token", "cloud-1", "SAGA", "tok-2", 50))
				.thenReturn(new EvidenceSearchPage(List.of(issue("10002", "SAGA-2")), "tok-3", false));
		when(jira.searchIssues("token", "cloud-1", "SAGA", "tok-3", 50))
				.thenReturn(new EvidenceSearchPage(List.of(issue("10003", "SAGA-3")), null, true));

		int processed = service.syncIntegration(integration);

		assertThat(processed).isEqualTo(3);
		org.mockito.Mockito.verify(jira).searchIssues("token", "cloud-1", "SAGA", null, 50);
		org.mockito.Mockito.verify(jira).searchIssues("token", "cloud-1", "SAGA", "tok-2", 50);
		org.mockito.Mockito.verify(jira).searchIssues("token", "cloud-1", "SAGA", "tok-3", 50);
	}

	@Test
	void syncIntegration_emptyFirstPage_processesNothingWithoutError() {
		when(jira.searchIssues("token", "cloud-1", "SAGA", null, 50))
				.thenReturn(new EvidenceSearchPage(List.of(), null, true));

		int processed = service.syncIntegration(integration);

		assertThat(processed).isEqualTo(0);
	}

	private static JsonNode issue(String id, String key) {
		try {
			return new ObjectMapper().readTree(
					"""
					{"id":"%s","key":"%s","fields":{"project":{"id":"10067","key":"SAGA"},"summary":"Evidence issue",
					"status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},"issuetype":{"name":"Task"}}}
					"""
							.formatted(id, key));
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}

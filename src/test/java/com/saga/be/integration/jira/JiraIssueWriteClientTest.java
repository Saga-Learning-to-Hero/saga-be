package com.saga.be.integration.jira;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/**
 * Field-discovery and payload-parsing audit for Company-managed vs Team-managed Jira projects.
 * Team-managed (next-gen) projects commonly expose Story Points as "Story point estimate" rather
 * than Company-managed's "Story Points", and are not assumed to share Company-managed's Sprint
 * field schema ({@code gh-sprint}) -- see {@link JiraIssueWriteClient#toSummary}.
 */
class JiraIssueWriteClientTest {

	private MockRestServiceServer server;
	private JiraIssueWriteClient client;
	private IntegrationProperties properties;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		properties = new IntegrationProperties();
		client = new JiraIssueWriteClient(builder.build(), properties, new ObjectMapper());
	}

	@Test
	void resolveStoryPointsFieldId_companyManaged_exactNameMatch() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-1/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10016","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}},
						  {"id":"customfield_10001","name":"Epic Link","schema":{"custom":"com.pyxis.greenhopper.jira:gh-epic-link"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertEquals("customfield_10016", client.resolveStoryPointsFieldId("token", "cloud-1"));
		server.verify();
	}

	@Test
	void resolveStoryPointsFieldId_teamManaged_storyPointEstimateName() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-2/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10038","name":"Story point estimate","schema":{"custom":"com.pyxis.greenhopper.jira:jsw-story-points"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertEquals("customfield_10038", client.resolveStoryPointsFieldId("token", "cloud-2"));
		server.verify();
	}

	@Test
	void resolveStoryPointsFieldId_ambiguousExactNameMatches_failsSafeInsteadOfPickingFirst() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-3/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10016","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}},
						  {"id":"customfield_20099","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertEquals("", client.resolveStoryPointsFieldId("token", "cloud-3"));
		server.verify();
	}

	@Test
	void resolveSprintFieldId_teamManaged_exactNameWithoutGhSprintSchema() {
		// Team-managed Sprint field is not assumed to carry Company-managed's gh-sprint schema
		// custom type -- exact name "Sprint" alone must still resolve it.
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-4/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10020","name":"Sprint","schema":{"custom":"com.pyxis.greenhopper.jira:some-other-type"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertEquals("customfield_10020", client.resolveSprintFieldId("token", "cloud-4"));
		server.verify();
	}

	@Test
	void resolveSprintFieldId_companyManaged_schemaFallbackWhenNoExactNameMatch() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-5/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10007","name":"Flexible Sprints","schema":{"custom":"com.pyxis.greenhopper.jira:gh-sprint"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertEquals("customfield_10007", client.resolveSprintFieldId("token", "cloud-5"));
		server.verify();
	}

	@Test
	void resolveStoryPointsFieldId_configuredOverride_skipsDiscoveryEntirely() {
		properties.getJira().setStoryPointsFieldId("customfield_99999");
		assertEquals("customfield_99999", client.resolveStoryPointsFieldId("token", "cloud-6"));
		server.verify();
	}

	@Test
	void peekCachedStoryPointsFieldId_beforeAnyDiscovery_returnsNullWithoutHttpCall() {
		assertEquals(null, client.peekCachedStoryPointsFieldId("cloud-never-synced"));
		server.verify();
	}

	@Test
	void peekCachedStoryPointsFieldId_afterDiscovery_returnsCachedValue() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-7/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[{"id":"customfield_10016","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}]
						""",
						MediaType.APPLICATION_JSON));
		client.resolveStoryPointsFieldId("token", "cloud-7");

		assertEquals("customfield_10016", client.peekCachedStoryPointsFieldId("cloud-7"));
		server.verify();
	}

	@Test
	void toSummary_companyManagedPayload_parsesStoryPointsAndSprint() {
		JsonNode issue = readTree(
				"""
				{"id":"200","key":"SAGA-9","fields":{"summary":"Auth","status":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}},
				"issuetype":{"name":"Story","id":"10001"},"assignee":{"accountId":"acct-1","displayName":"Alice"},
				"priority":{"id":"2","name":"High"},"customfield_10016":8,
				"customfield_10007":[{"id":31,"name":"Sprint 1","state":"active"}],
				"created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000"}}
				""");

		IssueSummary summary = JiraIssueWriteClient.toSummary(issue, "customfield_10016", "customfield_10007", true);
		assertEquals(8, summary.storyPoints());
		assertEquals("31", summary.sprintExternalId());
		assertEquals("Sprint 1", summary.sprintName());
		assertEquals(true, summary.storyPointsProvided());
		assertEquals(true, summary.sprintProvided());
	}

	@Test
	void toSummary_teamManagedPayload_parsesStoryPointEstimateAndSprintField() {
		JsonNode issue = readTree(
				"""
				{"id":"300","key":"TM-4","fields":{"summary":"Onboarding","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},
				"issuetype":{"name":"Task","id":"10002"},"customfield_10038":5,
				"customfield_10020":{"id":"77","name":"TM Sprint 1","state":"active"},
				"created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000"}}
				""");

		IssueSummary summary = JiraIssueWriteClient.toSummary(issue, "customfield_10038", "customfield_10020", true);
		assertEquals(5, summary.storyPoints());
		assertEquals("77", summary.sprintExternalId());
		assertEquals("TM Sprint 1", summary.sprintName());
	}

	@Test
	void toSummary_missingCustomField_authoritative_marksProvidedWithNullValue() {
		// Authoritative (bulk/full sync) fetch explicitly requested the field; its absence from
		// the response is the true current value (cleared), not an unrelated omission.
		JsonNode issue = readTree(
				"""
				{"id":"400","key":"SAGA-1","fields":{"summary":"No estimate","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},
				"issuetype":{"name":"Task","id":"10002"},
				"created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000"}}
				""");

		IssueSummary summary = JiraIssueWriteClient.toSummary(issue, "customfield_10016", "customfield_10007", true);
		assertEquals(null, summary.storyPoints());
		assertEquals(true, summary.storyPointsProvided());
	}

	@Test
	void toSummary_missingCustomField_nonAuthoritative_marksNotProvided() {
		// Webhook (non-authoritative): payload simply doesn't carry the field -- must not be
		// treated as "Jira cleared this field".
		JsonNode issue = readTree(
				"""
				{"id":"400","key":"SAGA-1","fields":{"summary":"No estimate","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},
				"issuetype":{"name":"Task","id":"10002"},
				"created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000"}}
				""");

		IssueSummary summary = JiraIssueWriteClient.toSummary(issue, "customfield_10016", "customfield_10007", false);
		assertEquals(null, summary.storyPoints());
		assertEquals(false, summary.storyPointsProvided());
	}

	@Test
	void toSummary_explicitNullCustomField_marksProvidedEvenNonAuthoritative() {
		// The custom field key is present but explicitly null -- Jira told us the true value
		// (cleared), so even a non-authoritative (webhook) call must mark it provided.
		JsonNode issue = readTree(
				"""
				{"id":"400","key":"SAGA-1","fields":{"summary":"Cleared","status":{"id":"1","name":"To Do","statusCategory":{"key":"new"}},
				"issuetype":{"name":"Task","id":"10002"},"customfield_10016":null,
				"created":"2026-01-01T00:00:00.000+0000","updated":"2026-01-02T00:00:00.000+0000"}}
				""");

		IssueSummary summary = JiraIssueWriteClient.toSummary(issue, "customfield_10016", "customfield_10007", false);
		assertEquals(null, summary.storyPoints());
		assertEquals(true, summary.storyPointsProvided());
	}

	private static JsonNode readTree(String json) {
		try {
			return new ObjectMapper().readTree(json);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}

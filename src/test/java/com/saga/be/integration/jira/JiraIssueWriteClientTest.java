package com.saga.be.integration.jira;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.http.converter.ResourceHttpMessageConverter;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
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
		// Mirrors IntegrationConfiguration#integrationRestClient's explicit converter list. A
		// bare RestClient.builder() picks Spring Boot 4's Jackson-3-based default JSON converter
		// (Jackson 3 is also on the classpath), which does not recognize Jackson 2's JsonNode/
		// ObjectNode as its own tree types -- reads of JsonNode.class fail outright, and writes of
		// an ObjectNode body silently serialize via generic bean reflection (its isArray()/
		// isObject()/... getters) instead of the JSON tree it holds, so Jira receives a body with
		// no "fields" key and rejects the write. This is that same bug reproduced in a test.
		RestClient.Builder builder = RestClient.builder().messageConverters(converters -> {
			converters.clear();
			converters.add(new ByteArrayHttpMessageConverter());
			converters.add(new StringHttpMessageConverter(StandardCharsets.UTF_8));
			converters.add(new ResourceHttpMessageConverter());
			converters.add(new FormHttpMessageConverter());
			converters.add(new MappingJackson2HttpMessageConverter(new ObjectMapper()));
		});
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

	// ==================== WRITE PATH: story point estimation ====================

	@Test
	void setIssueEstimation_boardEstimationSucceeds_neverFallsBackToCustomFieldWrite() {
		server.expect(requestTo(
						"https://api.atlassian.com/ex/jira/cloud-8/rest/agile/1.0/issue/10001/estimation?boardId=68"))
				.andExpect(method(HttpMethod.PUT))
				.andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

		client.setIssueEstimation("token", "cloud-8", "68", "10001", 5);

		// Only the estimation call is expected; a custom-field discovery/write would be an
		// unexpected extra request and would fail server.verify() below.
		server.verify();
	}

	@Test
	void setIssueEstimation_noBoardId_companyManaged_writesResolvedCustomField() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-9/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[{"id":"customfield_10016","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}]
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-9/rest/api/3/issue/10001/editmeta"))
				.andRespond(withSuccess(
						"""
						{"fields":{"customfield_10016":{"operations":["set"]}}}
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-9/rest/api/3/issue/10001"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(content().json("""
						{"fields":{"customfield_10016":5}}
						"""))
				.andRespond(withSuccess());

		client.setIssueEstimation("token", "cloud-9", null, "10001", 5);
		server.verify();
	}

	@Test
	void setIssueEstimation_noBoardId_teamManaged_writesStoryPointEstimateField() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-10/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[{"id":"customfield_10038","name":"Story point estimate","schema":{"custom":"com.pyxis.greenhopper.jira:jsw-story-points"}}]
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-10/rest/api/3/issue/10001/editmeta"))
				.andRespond(withSuccess(
						"""
						{"fields":{"customfield_10038":{"operations":["set"]}}}
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-10/rest/api/3/issue/10001"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(content().json("""
						{"fields":{"customfield_10038":5}}
						"""))
				.andRespond(withSuccess());

		client.setIssueEstimation("token", "cloud-10", null, "10001", 5);
		server.verify();
	}

	@Test
	void setIssueEstimation_ambiguousStoryPointField_throwsControlledError_neverWrites() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-11/rest/api/3/field"))
				.andRespond(withSuccess(
						"""
						[
						  {"id":"customfield_10016","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}},
						  {"id":"customfield_20099","name":"Story Points","schema":{"custom":"com.atlassian.jira.plugin.system.customfieldtypes:float"}}
						]
						""",
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.setIssueEstimation("token", "cloud-11", null, "10001", 5))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		// Only the field-metadata lookup is expected -- no editmeta/PUT after an ambiguous result.
		server.verify();
	}

	// ==================== WRITE PATH: field editability (reject-field root cause) ====================

	@Test
	void updateIssueFields_fieldNotOnEditScreen_throwsBeforeSendingWrite() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-12/rest/api/3/issue/10001/editmeta"))
				.andRespond(withSuccess(
						"""
						{"fields":{"summary":{"operations":["set"]}}}
						""",
						MediaType.APPLICATION_JSON));

		assertThatThrownBy(() -> client.updateIssueFields(
						"token", "cloud-12", "10001", Map.of("customfield_10016", 5)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.JIRA_FIELD_INVALID);
		// Only editmeta is expected -- the PUT must never be sent for a field editmeta didn't list.
		server.verify();
	}

	@Test
	void updateIssueFields_fieldOnEditScreen_sendsWrite() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-13/rest/api/3/issue/10001/editmeta"))
				.andRespond(withSuccess(
						"""
						{"fields":{"summary":{"operations":["set"]}}}
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-13/rest/api/3/issue/10001"))
				.andExpect(method(HttpMethod.PUT))
				.andExpect(content().json("""
						{"fields":{"summary":"New title"}}
						"""))
				.andRespond(withSuccess());

		client.updateIssueFields("token", "cloud-13", "10001", Map.of("summary", "New title"));
		server.verify();
	}

	@Test
	void updateIssueFields_editMetaFetchFails_stillAttemptsWrite() {
		// The editability pre-check is a diagnostic, not a hard dependency -- if editmeta itself
		// can't be fetched, fall through to attempting the write (protected by mapWriteFailure's
		// field-error surfacing if Jira then rejects it).
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-14/rest/api/3/issue/10001/editmeta"))
				.andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-14/rest/api/3/issue/10001"))
				.andExpect(method(HttpMethod.PUT))
				.andRespond(withSuccess());

		client.updateIssueFields("token", "cloud-14", "10001", Map.of("summary", "New title"));
		server.verify();
	}

	@Test
	void updateIssueFields_jiraRejects400_mapsFieldErrorDetailIntoMessage() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-15/rest/api/3/issue/10001/editmeta"))
				.andRespond(withSuccess(
						"""
						{"fields":{"customfield_10016":{"operations":["set"]}}}
						""",
						MediaType.APPLICATION_JSON));
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-15/rest/api/3/issue/10001"))
				.andExpect(method(HttpMethod.PUT))
				.andRespond(withStatus(HttpStatus.BAD_REQUEST)
						.contentType(MediaType.APPLICATION_JSON)
						.body(
								"""
								{"errorMessages":[],"errors":{"customfield_10016":"Field 'customfield_10016' cannot be set. It is not on the appropriate screen, or unknown."}}
								"""));

		assertThatThrownBy(() -> client.updateIssueFields(
						"token", "cloud-15", "10001", Map.of("customfield_10016", 5)))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getMessage())
				.satisfies(message -> assertThat((String) message).contains("customfield_10016"));
		server.verify();
	}

	// ==================== WRITE PATH: sprint update omits unrequested fields ====================

	@Test
	void updateSprint_renameOnly_bodyContainsOnlyName() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-16/rest/agile/1.0/sprint/31"))
				.andExpect(method(HttpMethod.POST))
				.andExpect(content().json("""
						{"name":"Sprint Renamed"}
						"""))
				.andRespond(withSuccess(
						"""
						{"id":31,"name":"Sprint Renamed","state":"active"}
						""",
						MediaType.APPLICATION_JSON));

		client.updateSprint("token", "cloud-16", "31", "Sprint Renamed", null, null, null, null);
		server.verify();
	}

	@Test
	void updateSprint_goalOnly_bodyContainsOnlyGoal() {
		server.expect(requestTo("https://api.atlassian.com/ex/jira/cloud-17/rest/agile/1.0/sprint/31"))
				.andExpect(content().json("""
						{"goal":"Ship auth"}
						"""))
				.andRespond(withSuccess(
						"""
						{"id":31,"name":"Sprint 1","state":"active","goal":"Ship auth"}
						""",
						MediaType.APPLICATION_JSON));

		client.updateSprint("token", "cloud-17", "31", null, "Ship auth", null, null, null);
		server.verify();
	}

	private static JsonNode readTree(String json) {
		try {
			return new ObjectMapper().readTree(json);
		} catch (Exception ex) {
			throw new RuntimeException(ex);
		}
	}
}

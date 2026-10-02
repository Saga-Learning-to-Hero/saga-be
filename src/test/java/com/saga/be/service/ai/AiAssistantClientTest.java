package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class AiAssistantClientTest {

	private static final UUID COURSE = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID CREDENTIAL = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID TASK = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final String URL = "http://saga-ai:8000/internal/v1/analyses";

	private final ObjectMapper mapper = new ObjectMapper();
	private AiAnalysisProperties props;
	private AiCredentialResolver credentials;
	private MockRestServiceServer server;
	private AiAssistantClient client;

	@BeforeEach
	void setUp() {
		props = new AiAnalysisProperties();
		props.getRuntime().setEnabled(true);
		props.getRuntime().setBaseUrl("http://saga-ai:8000");
		props.getRuntime().setInternalToken("internal-token");
		props.getOpenai().setModel("gpt-5.6-sol");
		props.getOpenai().setReasoningEffort("medium");
		credentials = mock(AiCredentialResolver.class);
		RestClient.Builder builder = RestClient.builder();
		server = MockRestServiceServer.bindTo(builder).build();
		client = new AiAssistantClient(props, mapper, credentials, builder.build());
	}

	private void resolvesTo(AiCredentialResolver.Resolution resolution) {
		when(credentials.resolve(COURSE, AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.USER_REQUEST))
				.thenReturn(resolution);
	}

	@Test
	void aPlatformAnswerSendsTheChatContractWithoutAnEnvelopeAndParsesTheResult() throws Exception {
		resolvesTo(AiCredentialResolver.Resolution.PLATFORM);
		AtomicReference<String> sent = new AtomicReference<>();
		server.expect(requestTo(URL))
				.andExpect(method(HttpMethod.POST))
				.andExpect(header("Authorization", "Bearer internal-token"))
				.andExpect(request -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withSuccess(response("chat:1", "SAGA-1 đang trễ.", "[{\"kind\":\"TASK\",\"evidenceId\":\"" + TASK + "\"}]",
						false, false), MediaType.APPLICATION_JSON));

		AiAssistantClient.Answer answer = client.ask("chat:1", COURSE, List.of(evidence()), context());

		JsonNode body = mapper.readTree(sent.get());
		assertThat(body.path("analysisType").asText()).isEqualTo("CHAT_ANSWER");
		assertThat(body.path("contractVersion").asText()).isEqualTo("saga-ai-inference-v1");
		assertThat(body.path("promptVersion").asText()).isEqualTo("chat-answer-v1");
		assertThat(body.path("credentialSource").asText()).isEqualTo("PLATFORM");
		assertThat(body.has("credentialEnvelope")).isFalse();
		assertThat(body.path("provider").path("model").asText()).isEqualTo("gpt-5.6-sol");
		assertThat(body.path("provider").has("name")).isFalse();
		assertThat(body.path("evidence").get(0).path("type").asText()).isEqualTo("TASK");
		assertThat(body.path("evidence").get(0).path("payload").path("key").asText()).isEqualTo("SAGA-1");
		assertThat(body.path("evidence").get(0).path("payload").has("assignee")).isTrue();
		assertThat(body.path("context").path("question").asText()).isEqualTo("Task nào trễ?");
		assertThat(answer.answer()).isEqualTo("SAGA-1 đang trễ.");
		assertThat(answer.citations()).containsExactly(new AiAssistantClient.Citation("TASK", TASK));
		assertThat(answer.followUpQuestions()).containsExactly("Ai làm SAGA-1?");
		assertThat(answer.credentialSource()).isEqualTo("PLATFORM");
		assertThat(answer.modelId()).isEqualTo("gpt-5.6-sol");
		assertThat(answer.latencyMs()).isEqualTo(850L);
		server.verify();
	}

	@Test
	void aCourseAnswerSendsTheResealedKeyAndTheBindingAndMarksTheKeyHealthy() throws Exception {
		resolvesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, CREDENTIAL, "fp",
				new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash")));
		when(credentials.buildEnvelope(CREDENTIAL, AiProviderRole.PRIMARY, COURSE))
				.thenReturn(new AiCredentialEnvelope(1, "AES-256-GCM", "bm9uY2U=", "Y2lwaGVy"));
		AtomicReference<String> sent = new AtomicReference<>();
		server.expect(requestTo(URL))
				.andExpect(request -> sent.set(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withSuccess(response("chat:2", "Ổn.", "[]", true, false), MediaType.APPLICATION_JSON));

		AiAssistantClient.Answer answer = client.ask("chat:2", COURSE, List.of(evidence()), context());

		JsonNode body = mapper.readTree(sent.get());
		assertThat(body.path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(body.path("credentialEnvelope").path("ciphertext").asText()).isEqualTo("Y2lwaGVy");
		assertThat(body.path("provider").path("name").asText()).isEqualTo("GEMINI");
		assertThat(body.path("provider").path("model").asText()).isEqualTo("gemini-3.6-flash");
		assertThat(answer.credentialSource()).isEqualTo("COURSE");
		assertThat(answer.insufficientData()).isTrue();
		verify(credentials).markSuccessful(CREDENTIAL);
	}

	@Test
	void aRejectedCourseKeyIsMarkedInvalidAndAQuotaFailureDegraded() {
		resolvesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, CREDENTIAL, "fp", null));
		when(credentials.buildEnvelope(any(), any(), any())).thenReturn(new AiCredentialEnvelope(1, "AES-256-GCM", "n", "c"));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY)
				.contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"AI_PROVIDER_AUTH_FAILED\",\"message\":\"x\"}"));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
				.contentType(MediaType.APPLICATION_JSON).body("{\"code\":\"AI_PROVIDER_QUOTA_EXHAUSTED\",\"message\":\"x\"}"));

		expectUnavailable(() -> client.ask("chat:3", COURSE, List.of(evidence()), context()), "AI_PROVIDER_AUTH_FAILED");
		expectUnavailable(() -> client.ask("chat:4", COURSE, List.of(evidence()), context()), "AI_PROVIDER_QUOTA_EXHAUSTED");

		verify(credentials).markInvalid(CREDENTIAL);
		verify(credentials).markDegraded(CREDENTIAL);
		verify(credentials, never()).markSuccessful(any());
	}

	@Test
	void anExhaustedCourseModelFallsBackDownTheCoursesOwnChainWithEachProvidersKey() throws Exception {
		UUID openRouterKey = UUID.fromString("44444444-4444-4444-4444-444444444444");
		AiProviderBinding gemini = new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash");
		AiProviderBinding cohere = new AiProviderBinding(AiProvider.COHERE, "command-a-03-2025");
		AiProviderBinding openRouter = new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free");
		resolvesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, CREDENTIAL, "fp", gemini));
		// the primary repeated in the chain is not retried; a provider without a course key is skipped
		when(credentials.primaryFallbackChain(COURSE)).thenReturn(List.of(gemini, cohere, openRouter));
		when(credentials.usableCourseCredential(COURSE, AiProviderRole.PRIMARY, AiProvider.COHERE)).thenReturn(java.util.Optional.empty());
		when(credentials.usableCourseCredential(COURSE, AiProviderRole.PRIMARY, AiProvider.OPENROUTER))
				.thenReturn(java.util.Optional.of(new AiCredentialResolver.CourseCredentialRef(openRouterKey, "fp2")));
		when(credentials.buildEnvelope(any(), any(), any())).thenReturn(new AiCredentialEnvelope(1, "AES-256-GCM", "n", "c"));
		List<String> sent = new java.util.ArrayList<>();
		server.expect(requestTo(URL))
				.andExpect(request -> sent.add(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
						.body("{\"code\":\"AI_PROVIDER_QUOTA_EXHAUSTED\",\"message\":\"x\"}"));
		server.expect(requestTo(URL))
				.andExpect(request -> sent.add(((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString()))
				.andRespond(withSuccess(response("chat:20", "Đã trả lời.", "[]", true, false), MediaType.APPLICATION_JSON));

		AiAssistantClient.Answer answer = client.ask("chat:20", COURSE, List.of(evidence()), context());

		assertThat(answer.answer()).isEqualTo("Đã trả lời.");
		assertThat(sent).hasSize(2);
		assertThat(mapper.readTree(sent.get(0)).path("provider").path("name").asText()).isEqualTo("GEMINI");
		assertThat(mapper.readTree(sent.get(1)).path("provider").path("name").asText()).isEqualTo("OPENROUTER");
		assertThat(mapper.readTree(sent.get(1)).path("provider").path("model").asText()).isEqualTo("openrouter/free");
		assertThat(mapper.readTree(sent.get(1)).path("credentialSource").asText()).isEqualTo("COURSE");
		verify(credentials).markDegraded(CREDENTIAL);
		verify(credentials).buildEnvelope(openRouterKey, AiProviderRole.PRIMARY, COURSE);
		verify(credentials).markSuccessful(openRouterKey);
		server.verify();
	}

	@Test
	void aRejectedKeyDoesNotFallBackAndAnExhaustedChainReportsTheLastFailure() {
		AiProviderBinding gemini = new AiProviderBinding(AiProvider.GEMINI, "gemini-3.6-flash");
		AiProviderBinding openRouter = new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free");
		resolvesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, CREDENTIAL, "fp", gemini));
		when(credentials.primaryFallbackChain(COURSE)).thenReturn(List.of(openRouter));
		when(credentials.usableCourseCredential(COURSE, AiProviderRole.PRIMARY, AiProvider.OPENROUTER))
				.thenReturn(java.util.Optional.of(new AiCredentialResolver.CourseCredentialRef(UUID.randomUUID(), "fp2")));
		when(credentials.buildEnvelope(any(), any(), any())).thenReturn(new AiCredentialEnvelope(1, "AES-256-GCM", "n", "c"));
		// 1st ask: auth failure stops at once
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"AI_PROVIDER_AUTH_FAILED\",\"message\":\"x\"}"));
		// 2nd ask: both models exhausted
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"AI_PROVIDER_QUOTA_EXHAUSTED\",\"message\":\"x\"}"));
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.GATEWAY_TIMEOUT).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"AI_PROVIDER_TIMEOUT\",\"message\":\"x\"}"));

		expectUnavailable(() -> client.ask("chat:21", COURSE, List.of(evidence()), context()), "AI_PROVIDER_AUTH_FAILED");
		expectUnavailable(() -> client.ask("chat:22", COURSE, List.of(evidence()), context()), "AI_PROVIDER_TIMEOUT");
		server.verify();
	}

	@Test
	void anOutdatedRuntimeRejectingTheContractIsReportedAsSuch() {
		resolvesTo(AiCredentialResolver.Resolution.PLATFORM);
		server.expect(requestTo(URL)).andRespond(withStatus(HttpStatus.UNPROCESSABLE_CONTENT).contentType(MediaType.APPLICATION_JSON)
				.body("{\"code\":\"INVALID_REQUEST\",\"message\":\"Invalid inference request.\"}"));

		expectUnavailable(() -> client.ask("chat:23", COURSE, List.of(evidence()), context()), "AI_RUNTIME_OUTDATED");
	}

	@Test
	void withoutARuntimeOrAKeyNothingIsSent() {
		props.getRuntime().setEnabled(false);
		expectUnavailable(() -> client.ask("chat:5", COURSE, List.of(), context()), "AI_RUNTIME_NOT_CONFIGURED");
		assertThat(client.keySource(COURSE)).isEqualTo("UNAVAILABLE");

		props.getRuntime().setEnabled(true);
		resolvesTo(AiCredentialResolver.Resolution.UNAVAILABLE);
		expectUnavailable(() -> client.ask("chat:6", COURSE, List.of(), context()), "AI_CREDENTIAL_UNAVAILABLE");
		assertThat(client.keySource(COURSE)).isEqualTo("UNAVAILABLE");
		server.verify();
	}

	@Test
	void aResultForAnotherRequestOrWithoutAnAnswerIsRejected() {
		resolvesTo(AiCredentialResolver.Resolution.PLATFORM);
		server.expect(requestTo(URL)).andRespond(withSuccess(response("chat:other", "Hi", "[]", false, false), MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL)).andRespond(withSuccess(response("chat:8", "   ", "[]", false, false), MediaType.APPLICATION_JSON));
		server.expect(requestTo(URL)).andRespond(withSuccess("not json", MediaType.APPLICATION_JSON));

		expectUnavailable(() -> client.ask("chat:7", COURSE, List.of(evidence()), context()), "AI_PROVIDER_RESULT_INVALID");
		expectUnavailable(() -> client.ask("chat:8", COURSE, List.of(evidence()), context()), "AI_PROVIDER_RESULT_INVALID");
		expectUnavailable(() -> client.ask("chat:9", COURSE, List.of(evidence()), context()), "AI_PROVIDER_RESULT_INVALID");
	}

	@Test
	void anUnparseableCitationIsSkippedNotFatal() {
		resolvesTo(AiCredentialResolver.Resolution.PLATFORM);
		server.expect(requestTo(URL)).andRespond(withSuccess(response("chat:10", "Ok", "[{\"kind\":\"TASK\",\"evidenceId\":\"nope\"},"
				+ "{\"kind\":\"TASK\",\"evidenceId\":\"" + TASK + "\"}]", false, false), MediaType.APPLICATION_JSON));

		assertThat(client.ask("chat:10", COURSE, List.of(evidence()), context()).citations())
				.containsExactly(new AiAssistantClient.Citation("TASK", TASK));
	}

	@Test
	void aSagaSideKeyProblemIsReportedWithoutTouchingTheKey() {
		resolvesTo(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, CREDENTIAL, "fp", null));
		when(credentials.buildEnvelope(any(), any(), any())).thenThrow(new AiCredentialCryptoException("AI_CREDENTIAL_TRANSPORT_NOT_CONFIGURED"));

		expectUnavailable(() -> client.ask("chat:11", COURSE, List.of(evidence()), context()), "AI_CREDENTIAL_TRANSPORT_NOT_CONFIGURED");
		verify(credentials, never()).markInvalid(any());
		server.verify();
	}

	// ------------------------------------------------------------------ fixtures

	private static AiAssistantClient.Evidence evidence() {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("key", "SAGA-1");
		payload.put("assignee", null);
		return new AiAssistantClient.Evidence(TASK, "TASK", "task:" + TASK, payload);
	}

	private static Map<String, Object> context() {
		Map<String, Object> context = new LinkedHashMap<>();
		context.put("question", "Task nào trễ?");
		context.put("viewer", Map.of("role", "STUDENT"));
		return context;
	}

	private static String response(String requestId, String answer, String citations, boolean noData, boolean outOfScope) {
		return "{\"requestId\":\"" + requestId + "\",\"analysisType\":\"CHAT_ANSWER\",\"contractVersion\":\"saga-ai-inference-v1\","
				+ "\"provider\":{\"providerKey\":\"openai\",\"modelId\":\"gpt-5.6-sol\",\"modelRevision\":null,\"responseId\":\"r1\"},"
				+ "\"usage\":{\"inputUnits\":10,\"outputUnits\":5,\"latencyMs\":850},"
				+ "\"result\":{\"kind\":\"CHAT_ANSWER\",\"payload\":{\"answer\":\"" + answer + "\",\"citations\":" + citations
				+ ",\"insufficientData\":" + noData + ",\"outOfScope\":" + outOfScope + ",\"followUpQuestions\":[\"Ai làm SAGA-1?\"]}}}";
	}

	private static void expectUnavailable(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, String code) {
		assertThatThrownBy(call).isInstanceOf(AiAssistantClient.Unavailable.class)
				.extracting(ex -> ((AiAssistantClient.Unavailable) ex).code())
				.isEqualTo(code);
	}
}

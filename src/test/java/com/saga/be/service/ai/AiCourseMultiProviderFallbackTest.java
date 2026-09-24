package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * Course multi-provider execution through the real {@link RemoteAiModelProvider} against a local
 * HTTP stand-in for saga-ai (scripted per provider name; no provider or paid API is contacted).
 * Proves dispatch per binding, per-credential status policy, the bounded course-owned fallback
 * chain, provenance, the legacy OpenAI-only path, and SECONDARY independence.
 */
class AiCourseMultiProviderFallbackTest {

	private static final AiProviderBinding GEMINI = new AiProviderBinding(AiProvider.GEMINI, "gemini-3.8-flash");
	private static final AiProviderBinding OPENROUTER = new AiProviderBinding(AiProvider.OPENROUTER, "openrouter/free");
	private static final AiProviderBinding OPENAI = new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-terra");

	private final ObjectMapper mapper = new ObjectMapper();
	private final UUID runId = UUID.randomUUID();
	private final Map<AiProvider, UUID> credentialIds = new HashMap<>();
	private final AiCredentialEnvelope envelope = new AiCredentialEnvelope(1, "AES-256-GCM", "nonce-b64", "ciphertext-b64");

	private AiAnalysisStateService state;
	private AiCredentialResolver resolver;
	private AiTaskIntelligenceFinalizationService taskFinalizer;
	private AiModelProvider platformProvider;

	private HttpServer server;
	private final Map<String, int[]> statusByProvider = new HashMap<>();
	private final Map<String, String> bodyByProvider = new HashMap<>();
	private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void setUp() throws Exception {
		for (AiProvider provider : AiProvider.values()) credentialIds.put(provider, UUID.randomUUID());
		state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		resolver = mock(AiCredentialResolver.class);
		when(resolver.buildEnvelope(any(), eq(AiProviderRole.PRIMARY), any())).thenReturn(envelope);
		for (AiProvider provider : AiProvider.values())
			when(resolver.usableCourseCredential(any(), eq(AiProviderRole.PRIMARY), eq(provider))).thenReturn(Optional.of(new AiCredentialResolver.CourseCredentialRef(credentialIds.get(provider), "fp-" + provider)));
		taskFinalizer = mock(AiTaskIntelligenceFinalizationService.class);
		when(taskFinalizer.finalize(any(), any(), any())).thenReturn(true);
		platformProvider = mock(AiModelProvider.class);
		when(platformProvider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(platformProvider.providerKey()).thenReturn("openai");
		when(platformProvider.providerConfigHash()).thenReturn("platform-cfg");

		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/v1/analyses", exchange -> {
			JsonNode request = mapper.readTree(exchange.getRequestBody());
			requests.add(request);
			String name = request.path("provider").path("name").asText("LEGACY");
			int status = statusByProvider.getOrDefault(name, new int[] {200})[0];
			String body = status == 200 ? success(request, name) : bodyByProvider.get(name);
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() { server.stop(0); }

	// ---- dispatch per binding ----

	@ParameterizedTest(name = "{0} binding is dispatched to the {0} adapter with its model")
	@CsvSource({"OPENAI, gpt-5.6-luna", "GEMINI, gemini-3.7-flash", "OPENROUTER, openrouter/free"})
	void boundPrimaryIsDispatchedWithItsProviderNameModelAndCourseEnvelope(AiProvider provider, String model) {
		RemoteAiModelProvider remote = realRemote();
		loadBoundRun(remote, new AiProviderBinding(provider, model));

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		JsonNode sent = requests.get(0);
		assertThat(sent.path("provider").path("name").asText()).isEqualTo(provider.name());
		assertThat(sent.path("provider").path("model").asText()).isEqualTo(model);
		assertThat(sent.path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(sent.path("credentialEnvelope").path("ciphertext").asText()).isEqualTo("ciphertext-b64");
		verify(resolver).markSuccessful(credentialIds.get(provider));
		verify(taskFinalizer).finalize(any(), any(), any());
		verify(state, never()).fail(any(), any(), eq(false));
		verifyProvenance(provider, model, credentialIds.get(provider), List.of(provider.name() + ":" + model + ":SUCCEEDED"));
	}

	@Test
	void legacyOpenAiOnlyDecisionKeepsTheExactPreMultiProviderShapeAndNeverFallsBack() {
		RemoteAiModelProvider remote = realRemote();
		fail("LEGACY", 429, "AI_PROVIDER_QUOTA_EXHAUSTED");
		loadRun(remote, null, credentialIds.get(AiProvider.OPENAI));

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		assertThat(requests.get(0).path("provider").has("name")).isFalse();
		assertThat(requests.get(0).path("provider").path("model").asText()).isEqualTo(remote.modelId());
		verify(resolver, never()).primaryFallbackChain(any());
		verify(resolver).markDegraded(credentialIds.get(AiProvider.OPENAI));
		verify(state, never()).recordPrimaryProvenance(any(), any(), any(), any(), any(), any());
		verify(state).fail(runId, "AI_PROVIDER_QUOTA_EXHAUSTED", false);
	}

	// ---- credential status policy, per provider ----

	@ParameterizedTest(name = "invalid {0} key -> only the {0} credential is INVALID, no fallback")
	@ValueSource(strings = {"OPENAI", "GEMINI", "OPENROUTER"})
	void invalidKeyInvalidatesOnlyThatProvidersCredentialAndNeverFallsBack(AiProvider provider) {
		RemoteAiModelProvider remote = realRemote();
		AiProviderBinding primary = new AiProviderBinding(provider, catalogModel(provider));
		fail(provider.name(), 502, "AI_PROVIDER_AUTH_FAILED");
		chain(fallbacksExcluding(provider));
		loadBoundRun(remote, primary);

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		verify(resolver).markInvalid(credentialIds.get(provider));
		verify(resolver, never()).markInvalid(eq(otherThan(provider)));
		verify(resolver, never()).markSuccessful(any());
		verify(resolver, never()).markDegraded(any());
		verify(state).fail(runId, "AI_PROVIDER_AUTH_FAILED", false);
		verifyProvenance(provider, primary.modelId(), credentialIds.get(provider), List.of(provider.name() + ":" + primary.modelId() + ":AI_PROVIDER_AUTH_FAILED"));
	}

	@ParameterizedTest(name = "{1} -> DEGRADED, not INVALID")
	@CsvSource({"429, AI_PROVIDER_QUOTA_EXHAUSTED", "429, AI_PROVIDER_RATE_LIMITED"})
	void quotaAndRateLimitDegradeTheCredential(int status, String code) {
		RemoteAiModelProvider remote = realRemote();
		fail("GEMINI", status, code);
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		verify(resolver).markDegraded(credentialIds.get(AiProvider.GEMINI));
		verify(resolver, never()).markInvalid(any());
		verify(state).fail(runId, code, false);
	}

	@ParameterizedTest(name = "{1} leaves the credential untouched")
	@CsvSource({"504, AI_PROVIDER_TIMEOUT", "503, AI_PROVIDER_UNAVAILABLE", "400, AI_CREDENTIAL_ENVELOPE_INVALID", "401, UNAUTHORIZED"})
	void transientAndSagaSideFailuresNeverInvalidateOrDegrade(int status, String code) {
		RemoteAiModelProvider remote = realRemote();
		fail("OPENROUTER", status, code);
		loadBoundRun(remote, OPENROUTER);

		service(remote).execute(runId);

		verify(resolver, never()).markInvalid(any());
		verify(resolver, never()).markDegraded(any());
		verify(resolver, never()).markSuccessful(any());
	}

	// ---- course-owned fallback ----

	@Test
	void geminiQuotaFallsBackToOpenRouterWithTheOpenRouterCredential() {
		RemoteAiModelProvider remote = realRemote();
		fail("GEMINI", 429, "AI_PROVIDER_QUOTA_EXHAUSTED");
		chain(List.of(OPENROUTER));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		assertThat(requests).extracting(r -> r.path("provider").path("name").asText()).containsExactly("GEMINI", "OPENROUTER");
		assertThat(requests.get(1).path("provider").path("model").asText()).isEqualTo("openrouter/free");
		verify(resolver).buildEnvelope(eq(credentialIds.get(AiProvider.OPENROUTER)), eq(AiProviderRole.PRIMARY), any());
		verify(resolver).markDegraded(credentialIds.get(AiProvider.GEMINI));
		verify(resolver).markSuccessful(credentialIds.get(AiProvider.OPENROUTER));
		verify(taskFinalizer).finalize(any(), any(), any());
		verifyProvenance(AiProvider.OPENROUTER, "openrouter/free", credentialIds.get(AiProvider.OPENROUTER),
				List.of("GEMINI:gemini-3.8-flash:AI_PROVIDER_QUOTA_EXHAUSTED", "OPENROUTER:openrouter/free:SUCCEEDED"));
	}

	@Test
	void openRouterUnavailableFallsBackToOpenAi() {
		RemoteAiModelProvider remote = realRemote();
		fail("OPENROUTER", 503, "AI_PROVIDER_UNAVAILABLE");
		chain(List.of(OPENAI));
		loadBoundRun(remote, OPENROUTER);

		service(remote).execute(runId);

		assertThat(requests).extracting(r -> r.path("provider").path("name").asText()).containsExactly("OPENROUTER", "OPENAI");
		verify(resolver).markSuccessful(credentialIds.get(AiProvider.OPENAI));
		verify(resolver, never()).markDegraded(any()); // unavailability is not the key's fault
		verify(resolver, never()).markInvalid(any());
		verifyProvenance(AiProvider.OPENAI, "gpt-5.6-terra", credentialIds.get(AiProvider.OPENAI),
				List.of("OPENROUTER:openrouter/free:AI_PROVIDER_UNAVAILABLE", "OPENAI:gpt-5.6-terra:SUCCEEDED"));
	}

	@ParameterizedTest(name = "{1} never triggers fallback")
	@CsvSource({
		"502, AI_PROVIDER_RESULT_INVALID",
		"400, AI_MODEL_CAPABILITY_UNSUPPORTED",
		"400, AI_MODEL_NOT_SUPPORTED",
		"502, AI_PROVIDER_MODEL_NOT_FOUND",
		"502, AI_PROVIDER_FAILED",
		"400, AI_CREDENTIAL_ENVELOPE_INVALID"
	})
	void schemaCapabilityModelAndConfigFailuresNeverFallBack(int status, String code) {
		RemoteAiModelProvider remote = realRemote();
		fail("GEMINI", status, code);
		chain(List.of(OPENROUTER, OPENAI));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		verify(state).fail(runId, code, false);
		verify(resolver, never()).buildEnvelope(eq(credentialIds.get(AiProvider.OPENROUTER)), any(), any());
	}

	@Test
	void malformedResultAcceptedByTransportIsRejectedOnceWithoutFallback() {
		RemoteAiModelProvider remote = realRemote();
		bodyByProvider.put("GEMINI-MALFORMED", "true");
		chain(List.of(OPENROUTER));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		verify(state).fail(runId, "AI_PROVIDER_RESULT_INVALID", false);
		verify(resolver, never()).markSuccessful(any());
	}

	@Test
	void localCryptoFailureNeverFallsBackAndNeverBlamesACredential() {
		RemoteAiModelProvider remote = realRemote();
		when(resolver.buildEnvelope(eq(credentialIds.get(AiProvider.GEMINI)), eq(AiProviderRole.PRIMARY), any())).thenThrow(new AiCredentialCryptoException("AI_CREDENTIAL_DECRYPTION_FAILED"));
		chain(List.of(OPENROUTER));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		assertThat(requests).isEmpty();
		verify(state).fail(runId, "AI_CREDENTIAL_DECRYPTION_FAILED", false);
		verify(resolver, never()).markInvalid(any());
		verify(resolver, never()).markDegraded(any());
	}

	@Test
	void fallbackNeverUsesAPlatformCredentialOrPlatformProvider() {
		RemoteAiModelProvider remote = realRemote();
		fail("GEMINI", 429, "AI_PROVIDER_RATE_LIMITED");
		fail("OPENROUTER", 504, "AI_PROVIDER_TIMEOUT");
		chain(List.of(OPENROUTER));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		verify(platformProvider, never()).analyze(any());
		assertThat(requests).allSatisfy(r -> {
			assertThat(r.path("credentialSource").asText()).isEqualTo("COURSE");
			assertThat(r.path("credentialEnvelope").isObject()).isTrue();
		});
		verify(resolver, never()).usableCourseCredential(any(), eq(AiProviderRole.SECONDARY), any());
		verify(state).fail(runId, "AI_PROVIDER_TIMEOUT", false);
	}

	@Test
	void attemptsAreBoundedToOnePerBindingAndSkipBindingsWithoutACourseCredential() {
		RemoteAiModelProvider remote = realRemote();
		for (AiProvider provider : AiProvider.values()) fail(provider.name(), 429, "AI_PROVIDER_RATE_LIMITED");
		when(resolver.usableCourseCredential(any(), eq(AiProviderRole.PRIMARY), eq(AiProvider.OPENAI))).thenReturn(Optional.empty());
		// Defensive: even a chain that (bypassing validation) repeats bindings is tried once each.
		chain(List.of(OPENROUTER, GEMINI, OPENROUTER, OPENAI));
		loadBoundRun(remote, GEMINI);

		service(remote).execute(runId);

		assertThat(requests).extracting(r -> r.path("provider").path("name").asText()).containsExactly("GEMINI", "OPENROUTER");
		verify(state).fail(runId, "AI_PROVIDER_RATE_LIMITED", false);
		verifyProvenance(AiProvider.OPENROUTER, "openrouter/free", credentialIds.get(AiProvider.OPENROUTER), List.of(
				"GEMINI:gemini-3.8-flash:AI_PROVIDER_RATE_LIMITED",
				"OPENROUTER:openrouter/free:AI_PROVIDER_RATE_LIMITED",
				"OPENAI:gpt-5.6-terra:SKIPPED_NO_CREDENTIAL"));
	}

	// ---- SECONDARY independence ----

	@Test
	void secondaryUsesItsOwnBindingAndCredentialAndNeverFallsBack() throws Exception {
		AiAnalysisProperties properties = properties();
		properties.setSecondaryEnabled(true);
		RemoteAiSecondaryModelProvider secondary = new RemoteAiSecondaryModelProvider(properties, mapper);
		UUID secondaryCredential = UUID.randomUUID();
		AiProviderBinding secondaryBinding = new AiProviderBinding(AiProvider.OPENAI, "gpt-5.6-terra");
		when(resolver.resolve(any(), any(), eq(AiProviderRole.SECONDARY), any()))
				.thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE, secondaryCredential, "secondary-fp", secondaryBinding));
		when(resolver.buildEnvelope(eq(secondaryCredential), eq(AiProviderRole.SECONDARY), any())).thenReturn(envelope);
		chain(List.of(OPENROUTER));
		fail("OPENAI", 429, "AI_PROVIDER_QUOTA_EXHAUSTED");
		AiAnalysisProviderDecisionRepository decisions = mock(AiAnalysisProviderDecisionRepository.class);
		when(decisions.findByAnalysisRun_IdAndProviderRole(runId, AiProviderRole.SECONDARY)).thenReturn(Optional.empty());
		when(decisions.claimSecondaryPending(eq(runId), any())).thenReturn(1);
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId); run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE); run.setPromptVersion("task-intelligence-v1");

		new AiSecondaryBrainService(properties, List.of(secondary), decisions, mock(AiResultValidation.class), mapper, resolver)
				.maybeRun(new AiAnalysisStateService.ExecutionInput(run, List.of(), new AiAnalysisProviderDecision()));

		assertThat(requests).hasSize(1);
		assertThat(requests.get(0).path("provider").path("role").asText()).isEqualTo("SECONDARY");
		assertThat(requests.get(0).path("provider").path("name").asText()).isEqualTo("OPENAI");
		assertThat(requests.get(0).path("provider").path("model").asText()).isEqualTo("gpt-5.6-terra");
		ArgumentCaptor<AiAnalysisProviderDecision> row = ArgumentCaptor.forClass(AiAnalysisProviderDecision.class);
		verify(decisions).saveAndFlush(row.capture());
		assertThat(row.getValue().getAiProvider()).isEqualTo(AiProvider.OPENAI);
		assertThat(row.getValue().getModelId()).isEqualTo("gpt-5.6-terra");
		assertThat(row.getValue().getCourseCredentialId()).isEqualTo(secondaryCredential);
		verify(resolver).markDegraded(secondaryCredential);
		verify(resolver, never()).buildEnvelope(any(), eq(AiProviderRole.PRIMARY), any());
		verify(resolver, never()).primaryFallbackChain(any());
		verify(decisions).failSecondaryActive(eq(runId), eq("AI_PROVIDER_QUOTA_EXHAUSTED"), eq(false), any());
	}

	// ---- helpers ----

	private AiAnalysisExecutionService service(AiModelProvider remote) {
		return new AiAnalysisExecutionService(state, List.of(platformProvider, remote), new AiStructuredResultValidator(), mapper,
				new AiAcademicResultValidator(mapper), null,
				new AiTaskIntelligenceResultValidator(), taskFinalizer,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				null, null, resolver);
	}

	private void loadBoundRun(RemoteAiModelProvider remote, AiProviderBinding binding) {
		loadRun(remote, binding, credentialIds.get(binding.provider()));
	}

	private void loadRun(RemoteAiModelProvider remote, AiProviderBinding binding, UUID credentialId) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId);
		run.setStatus(AiAnalysisStatus.RUNNING);
		run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE);
		run.setPromptVersion("task-intelligence-v1");
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setProviderRole(AiProviderRole.PRIMARY);
		decision.setProviderKey(remote.providerKey());
		decision.setProviderConfigHash(remote.providerConfigHash());
		decision.setCredentialSource(AiCredentialSource.COURSE);
		decision.setCourseCredentialId(credentialId);
		decision.setCredentialFingerprint("fp");
		decision.setModelId(binding == null ? remote.modelId() : binding.modelId());
		decision.setAiProvider(binding == null ? null : binding.provider());
		when(state.loadExecution(runId)).thenReturn(new AiAnalysisStateService.ExecutionInput(run, List.of(), decision));
	}

	private void chain(List<AiProviderBinding> bindings) { when(resolver.primaryFallbackChain(any())).thenReturn(bindings); }

	private void fail(String providerName, int status, String code) {
		statusByProvider.put(providerName, new int[] {status});
		bodyByProvider.put(providerName, "{\"code\":\"" + code + "\",\"message\":\"safe message\"}");
	}

	private String success(JsonNode request, String name) {
		boolean malformed = bodyByProvider.containsKey(name + "-MALFORMED");
		String payload = malformed ? "{\"evidenceStrength\":42}" : "{\"evidenceStrength\":\"ACTIVE_PROGRESS\",\"summary\":\"ok\",\"deviationDetected\":false,\"deviationSummary\":null,\"evidence\":[],\"humanReviewRequired\":false}";
		return "{\"requestId\":\"" + request.path("requestId").asText() + "\",\"analysisType\":\"TASK_INTELLIGENCE\",\"contractVersion\":\"saga-ai-inference-v1\","
				+ "\"provider\":{\"providerKey\":\"" + name.toLowerCase() + "\",\"modelId\":\"" + request.path("provider").path("model").asText() + "\",\"modelRevision\":null,\"responseId\":\"resp-1\"},"
				+ "\"usage\":{\"inputUnits\":3,\"outputUnits\":2,\"latencyMs\":5},"
				+ "\"result\":{\"kind\":\"TASK_INTELLIGENCE\",\"payload\":" + payload + "}}";
	}

	private void verifyProvenance(AiProvider provider, String model, UUID credentialId, List<String> expectedAttempts) {
		ArgumentCaptor<String> attempts = ArgumentCaptor.forClass(String.class);
		verify(state).recordPrimaryProvenance(eq(runId), eq(provider), eq(model), eq(credentialId), anyString(), attempts.capture());
		try {
			List<String> actual = new ArrayList<>();
			for (JsonNode attempt : mapper.readTree(attempts.getValue())) actual.add(attempt.path("provider").asText() + ":" + attempt.path("modelId").asText() + ":" + attempt.path("outcome").asText());
			assertThat(actual).containsExactlyElementsOf(expectedAttempts);
			assertThat(attempts.getValue()).doesNotContain("ciphertext", "nonce");
		} catch (Exception e) { throw new AssertionError(e); }
	}

	private List<AiProviderBinding> fallbacksExcluding(AiProvider provider) {
		return List.of(GEMINI, OPENROUTER, OPENAI).stream().filter(b -> b.provider() != provider).toList();
	}

	private UUID otherThan(AiProvider provider) {
		return credentialIds.entrySet().stream().filter(e -> e.getKey() != provider).map(Map.Entry::getValue).findFirst().orElseThrow();
	}

	private static String catalogModel(AiProvider provider) {
		return switch (provider) { case OPENAI -> "gpt-5.6-sol"; case GEMINI -> "gemini-3.8-flash"; case OPENROUTER -> "openrouter/free"; };
	}

	private RemoteAiModelProvider realRemote() { return new RemoteAiModelProvider(properties(), mapper); }

	private AiAnalysisProperties properties() {
		AiAnalysisProperties properties = new AiAnalysisProperties();
		properties.setEnabled(true);
		properties.setPrimaryProvider("remote");
		properties.getRuntime().setEnabled(true);
		properties.getRuntime().setBaseUrl("http://localhost:" + server.getAddress().getPort());
		properties.getRuntime().setInternalToken("service-token");
		return properties;
	}
}

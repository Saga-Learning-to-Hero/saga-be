package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiAnalysisRequest;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.ai.AiProviderResponse;
import com.saga.be.ai.AiTaskIntelligenceResult;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.enums.AiTaskEvidenceStrength;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/**
 * BYOK production-correctness gate: a COURSE credential is only ever served by the remote
 * saga-ai path, saga-ai's real error contract drives the credential status policy, and local
 * crypto/config failures keep their specific safe code without touching the provider key status.
 */
class AiByokExecutionCorrectnessTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final UUID runId = UUID.randomUUID();
	private final UUID courseCredentialId = UUID.randomUUID();
	private final AiCredentialEnvelope envelope = new AiCredentialEnvelope(1, "AES-256-GCM", "nonce-b64", "ciphertext-b64");

	private AiAnalysisStateService state;
	private AiCredentialResolver resolver;
	private AiTaskIntelligenceFinalizationService taskFinalizer;
	private AiModelProvider platformProvider;

	private HttpServer server;
	private final AtomicInteger remoteCalls = new AtomicInteger();
	private final AtomicInteger remoteStatus = new AtomicInteger(200);
	private final AtomicReference<String> remoteBody = new AtomicReference<>("{}");
	private final AtomicReference<JsonNode> remoteRequest = new AtomicReference<>();

	@BeforeEach
	void setUp() throws Exception {
		state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		resolver = mock(AiCredentialResolver.class);
		when(resolver.buildEnvelope(eq(courseCredentialId), eq(AiProviderRole.PRIMARY), any())).thenReturn(envelope);
		taskFinalizer = mock(AiTaskIntelligenceFinalizationService.class);
		when(taskFinalizer.finalize(any(), any(), any())).thenReturn(true);
		platformProvider = provider("openai", "platform-cfg", false);

		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/v1/analyses", exchange -> {
			remoteCalls.incrementAndGet();
			remoteRequest.set(mapper.readTree(exchange.getRequestBody()));
			byte[] bytes = remoteBody.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(remoteStatus.get(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void tearDown() {
		server.stop(0);
	}

	// ---- A. a COURSE credential can never be served by a platform-key provider ----

	@Test
	void courseCredentialOnPlatformOnlyProviderFailsClosedBeforeAnyDecryptOrCall() {
		loadRun(AiCredentialSource.COURSE, "openai", "platform-cfg");

		service(List.of(platformProvider)).execute(runId);

		verify(state).fail(runId, AiCredentialResolver.COURSE_CREDENTIAL_REQUIRES_REMOTE_PROVIDER, false);
		verify(platformProvider, never()).analyze(any());
		verify(resolver, never()).buildEnvelope(any(), any(), any());
		verifyCredentialStatusUntouched();
	}

	@Test
	void courseCredentialOnRemoteProviderSendsCourseEnvelopeAndOnlySuccessMarksActive() {
		AiModelProvider remote = provider("saga-ai", "remote-cfg", true);
		when(remote.analyze(any())).thenReturn(validTaskResponse());
		loadRun(AiCredentialSource.COURSE, "saga-ai", "remote-cfg");

		service(List.of(platformProvider, remote)).execute(runId);

		ArgumentCaptor<AiAnalysisRequest> sent = ArgumentCaptor.forClass(AiAnalysisRequest.class);
		verify(remote).analyze(sent.capture());
		assertThat(sent.getValue().credentialSource()).isEqualTo(AiCredentialSource.COURSE);
		assertThat(sent.getValue().credentialEnvelope()).isSameAs(envelope);
		verify(resolver).markSuccessful(courseCredentialId);
		verify(platformProvider, never()).analyze(any());
	}

	@Test
	void platformRunStaysPlatformWithoutEnvelopeOrCredentialStatusChanges() {
		when(platformProvider.analyze(any())).thenReturn(validTaskResponse());
		loadRun(AiCredentialSource.PLATFORM, "openai", "platform-cfg");

		service(List.of(platformProvider)).execute(runId);

		ArgumentCaptor<AiAnalysisRequest> sent = ArgumentCaptor.forClass(AiAnalysisRequest.class);
		verify(platformProvider).analyze(sent.capture());
		assertThat(sent.getValue().credentialSource()).isEqualTo(AiCredentialSource.PLATFORM);
		assertThat(sent.getValue().credentialEnvelope()).isNull();
		verify(resolver, never()).buildEnvelope(any(), any(), any());
		verifyCredentialStatusUntouched();
	}

	// ---- B. saga-ai's real top-level error contract drives the status policy ----

	@Test
	void wrongCourseProviderKeyMarksTheCredentialInvalidWithoutPlatformFallback() {
		RemoteAiModelProvider remote = realRemote();
		remoteError(502, "AI_PROVIDER_AUTH_FAILED");
		loadRun(AiCredentialSource.COURSE, remote.providerKey(), remote.providerConfigHash());

		service(List.of(platformProvider, remote)).execute(runId);

		verify(state).fail(runId, "AI_PROVIDER_AUTH_FAILED", false);
		verify(resolver).markInvalid(courseCredentialId);
		verify(resolver, never()).markSuccessful(any());
		verify(resolver, never()).markDegraded(any());
		assertThat(remoteCalls).hasValue(1);
		assertThat(remoteRequest.get().path("credentialSource").asText()).isEqualTo("COURSE");
		verify(platformProvider, never()).analyze(any());
	}

	@Test
	void rateLimitedCourseKeyIsDegradedNotInvalid() {
		RemoteAiModelProvider remote = realRemote();
		remoteError(429, "AI_PROVIDER_RATE_LIMITED");
		loadRun(AiCredentialSource.COURSE, remote.providerKey(), remote.providerConfigHash());

		service(List.of(platformProvider, remote)).execute(runId);

		verify(state).fail(runId, "AI_PROVIDER_RATE_LIMITED", false);
		verify(resolver).markDegraded(courseCredentialId);
		verify(resolver, never()).markInvalid(any());
		verify(resolver, never()).markSuccessful(any());
		verify(platformProvider, never()).analyze(any());
	}

	@ParameterizedTest(name = "HTTP {0} {1} -> {2}, credential untouched")
	@CsvSource({
		"400, AI_CREDENTIAL_ENVELOPE_INVALID, AI_CREDENTIAL_ENVELOPE_INVALID",
		"401, UNAUTHORIZED, AI_RUNTIME_UNAVAILABLE",
		"500, , AI_PROVIDER_FAILED",
		"503, AI_PROVIDER_FAILED, AI_PROVIDER_FAILED",
		"504, AI_PROVIDER_TIMEOUT, AI_PROVIDER_TIMEOUT"
	})
	void infrastructureTransportAndTransientFailuresNeverInvalidateTheLecturerKey(int status, String sagaAiCode, String expectedRunCode) {
		RemoteAiModelProvider remote = realRemote();
		if (sagaAiCode == null) {
			remoteStatus.set(status);
			remoteBody.set("upstream failure");
		} else {
			remoteError(status, sagaAiCode);
		}
		loadRun(AiCredentialSource.COURSE, remote.providerKey(), remote.providerConfigHash());

		service(List.of(platformProvider, remote)).execute(runId);

		verify(state).fail(runId, expectedRunCode, false);
		verifyCredentialStatusUntouched();
		assertThat(remoteCalls).hasValue(1);
		verify(platformProvider, never()).analyze(any());
	}

	// ---- C. local crypto/config failures keep their specific safe code ----

	@ParameterizedTest(name = "{0} is persisted on the run, provider never called")
	@ValueSource(strings = {
		"AI_CREDENTIAL_MASTER_KEY_NOT_CONFIGURED",
		"AI_CREDENTIAL_TRANSPORT_KEY_NOT_CONFIGURED",
		"AI_CREDENTIAL_DECRYPTION_FAILED",
		"AI_CREDENTIAL_KEY_VERSION_UNSUPPORTED",
		"AI_CREDENTIAL_ENVELOPE_SOURCE_MISSING"
	})
	void cryptoFailureKeepsItsSpecificRunCodeAndNeverTouchesProviderKeyStatus(String cryptoCode) {
		RemoteAiModelProvider remote = realRemote();
		when(resolver.buildEnvelope(eq(courseCredentialId), eq(AiProviderRole.PRIMARY), any()))
				.thenThrow(new AiCredentialCryptoException(cryptoCode));
		loadRun(AiCredentialSource.COURSE, remote.providerKey(), remote.providerConfigHash());

		service(List.of(platformProvider, remote)).execute(runId);

		verify(state).fail(runId, cryptoCode, false);
		verify(state, never()).fail(runId, "AI_ANALYSIS_PROVIDER_FAILED", false);
		assertThat(remoteCalls).hasValue(0);
		verify(platformProvider, never()).analyze(any());
		verifyCredentialStatusUntouched();
	}

	// ---- helpers ----

	private AiAnalysisExecutionService service(List<AiModelProvider> providers) {
		return new AiAnalysisExecutionService(state, providers, new AiStructuredResultValidator(), mapper,
				new AiAcademicResultValidator(mapper), null,
				new AiTaskIntelligenceResultValidator(), taskFinalizer,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				null, null, resolver);
	}

	private void loadRun(AiCredentialSource source, String providerKey, String providerConfigHash) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId);
		run.setStatus(AiAnalysisStatus.RUNNING);
		run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE);
		run.setPromptVersion("task-intelligence-v1");
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setProviderRole(AiProviderRole.PRIMARY);
		decision.setProviderKey(providerKey);
		decision.setProviderConfigHash(providerConfigHash);
		decision.setCredentialSource(source);
		if (source == AiCredentialSource.COURSE) decision.setCourseCredentialId(courseCredentialId);
		when(state.loadExecution(runId)).thenReturn(new AiAnalysisStateService.ExecutionInput(run, List.of(), decision));
	}

	private AiModelProvider provider(String key, String configHash, boolean supportsCourseCredential) {
		AiModelProvider provider = mock(AiModelProvider.class);
		when(provider.role()).thenReturn(AiProviderRole.PRIMARY);
		when(provider.providerKey()).thenReturn(key);
		when(provider.providerConfigHash()).thenReturn(configHash);
		when(provider.modelId()).thenReturn("model");
		when(provider.supportsCourseCredential()).thenReturn(supportsCourseCredential);
		return provider;
	}

	private RemoteAiModelProvider realRemote() {
		AiAnalysisProperties properties = new AiAnalysisProperties();
		properties.setEnabled(true);
		properties.setPrimaryProvider("remote");
		properties.getRuntime().setEnabled(true);
		properties.getRuntime().setBaseUrl("http://localhost:" + server.getAddress().getPort());
		properties.getRuntime().setInternalToken("service-token");
		return new RemoteAiModelProvider(properties, mapper);
	}

	private void remoteError(int status, String code) {
		remoteStatus.set(status);
		remoteBody.set("{\"code\":\"" + code + "\",\"message\":\"safe message\"}");
	}

	private static AiProviderResponse validTaskResponse() {
		return new AiProviderResponse(
				new AiTaskIntelligenceResult(AiTaskEvidenceStrength.ACTIVE_PROGRESS, "ok", false, null, List.of(), false),
				1L, 1L, 1L, null, null);
	}

	private void verifyCredentialStatusUntouched() {
		verify(resolver, never()).markSuccessful(any());
		verify(resolver, never()).markInvalid(any());
		verify(resolver, never()).markDegraded(any());
	}
}

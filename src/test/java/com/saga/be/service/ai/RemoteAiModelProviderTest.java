package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.saga.be.ai.AiAcademicClassificationResult;
import com.saga.be.ai.AiAnalysisRequest;
import com.saga.be.ai.AiProviderResponse;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiProviderRole;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class RemoteAiModelProviderTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final AtomicReference<String> response = new AtomicReference<>();
	private final AtomicInteger status = new AtomicInteger(200);
	private final AtomicInteger calls = new AtomicInteger();
	private final AtomicLong delayMillis = new AtomicLong();
	private final AtomicBoolean httpInsideTransaction = new AtomicBoolean();
	private final AtomicReference<JsonNode> request = new AtomicReference<>();
	private HttpServer server;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/v1/analyses", exchange -> {
			calls.incrementAndGet();
			httpInsideTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
			request.set(mapper.readTree(exchange.getRequestBody()));
			try {
				Thread.sleep(delayMillis.get());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().set("Content-Type", "application/json");
			exchange.sendResponseHeaders(status.get(), bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void commitGoldenFixtureMapsAndPreservesWireContract() throws Exception {
		UUID run = UUID.randomUUID();
		UUID evidence = UUID.fromString("11111111-1111-1111-1111-111111111111");
		String golden = fixture("commit_intelligence_response.json");
		assertThatCode(() -> mapper.treeToValue(payload(golden), AiStructuredResult.class)).doesNotThrowAnyException();
		respond(golden, run);

		AiProviderResponse mapped = provider().analyze(commitRequest(run, List.of(input(evidence, "COMMIT_MESSAGE", "{\"message\":\"x\"}", "{}"))));

		assertThat(calls).hasValue(1);
		assertThat(httpInsideTransaction).isFalse();
		assertThat(request.get().path("contractVersion").asText()).isEqualTo("saga-ai-inference-v1");
		assertThat(request.get().path("analysisType").asText()).isEqualTo("COMMIT_INTELLIGENCE");
		assertThat(request.get().path("requestId").asText()).isEqualTo(run + ":PRIMARY");
		assertThat(request.get().path("provider").path("role").asText()).isEqualTo("PRIMARY");
		assertThat(mapped.result()).isInstanceOf(AiStructuredResult.class);
		assertThat(new AiStructuredResultValidator().invalidReason((AiStructuredResult) mapped.result(), java.util.Set.of(evidence))).isEmpty();
	}

	@Test
	void courseCredentialEnvelopeIsSerializedOnTheActualHttpRequestWithoutPlaintext() throws Exception {
		UUID run = UUID.randomUUID();
		String plaintext = "course-api-key-must-never-cross-the-wire";
		AiCredentialEnvelope envelope = new AiCredentialEnvelope(1, "AES-256-GCM", "fresh-transport-nonce", "transport-ciphertext");
		respond(fixture("commit_intelligence_response.json"), run, AiProviderRole.PRIMARY);

		provider().analyze(new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.COMMIT_INTELLIGENCE,
				"commit-intelligence-v1", null, "contract", List.of(), AiCredentialSource.COURSE, envelope));

		assertThat(request.get().path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(request.get().path("credentialEnvelope").path("version").asInt()).isEqualTo(1);
		assertThat(request.get().path("credentialEnvelope").path("algorithm").asText()).isEqualTo("AES-256-GCM");
		assertThat(request.get().path("credentialEnvelope").path("nonce").asText()).isEqualTo("fresh-transport-nonce");
		assertThat(request.get().path("credentialEnvelope").path("ciphertext").asText()).isEqualTo("transport-ciphertext");
		assertThat(mapper.writeValueAsString(request.get())).doesNotContain(plaintext);
	}

	@Test
	void platformCredentialRequestHasNoEnvelopeAndKeepsThePlatformPath() throws Exception {
		UUID run = UUID.randomUUID();
		respond(fixture("commit_intelligence_response.json"), run, AiProviderRole.PRIMARY);

		provider().analyze(commitRequest(run, List.of()));

		assertThat(request.get().path("credentialSource").asText()).isEqualTo("PLATFORM");
		assertThat(request.get().has("credentialEnvelope")).isFalse();
	}

	@Test
	void courseSourceWithoutEnvelopeIsNotSilentlyDowngradedToPlatform() throws Exception {
		UUID run = UUID.randomUUID();
		status.set(400);
		response.set("{\"error\":{\"code\":\"AI_CREDENTIAL_ENVELOPE_INVALID\"}}");
		AiAnalysisRequest malformedCourseRequest = new AiAnalysisRequest(run, AiProviderRole.PRIMARY,
				AiAnalysisType.COMMIT_INTELLIGENCE, "commit-intelligence-v1", null, "contract", List.of(),
				AiCredentialSource.COURSE, null);

		assertSafeFailureOnce(malformedCourseRequest, "AI_CREDENTIAL_ENVELOPE_INVALID");

		assertThat(request.get().path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(request.get().has("credentialEnvelope")).isFalse();
	}

	@Test
	void secondaryProviderSendsSecondaryRoleAndItsOwnCourseEnvelope() throws Exception {
		UUID run = UUID.randomUUID();
		AiCredentialEnvelope envelope = new AiCredentialEnvelope(1, "AES-256-GCM", "secondary-nonce", "secondary-ciphertext");
		respond(fixture("commit_intelligence_response.json"), run, AiProviderRole.SECONDARY);
		AiAnalysisRequest secondaryRequest = new AiAnalysisRequest(run, AiProviderRole.SECONDARY,
				AiAnalysisType.COMMIT_INTELLIGENCE, "commit-intelligence-v1", null, "contract", List.of(),
				AiCredentialSource.COURSE, envelope);

		new RemoteAiSecondaryModelProvider(properties(), mapper).analyze(secondaryRequest);

		assertThat(request.get().path("requestId").asText()).isEqualTo(run + ":SECONDARY");
		assertThat(request.get().path("provider").path("role").asText()).isEqualTo("SECONDARY");
		assertThat(request.get().path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(request.get().path("credentialEnvelope").path("ciphertext").asText()).isEqualTo("secondary-ciphertext");
	}

	@Test
	void academicGoldenFixtureMapsAndExistingValidatorOwnsDomainValidation() throws Exception {
		UUID run = UUID.randomUUID();
		UUID syllabus = UUID.randomUUID();
		UUID phase = UUID.randomUUID();
		UUID artifact = UUID.randomUUID();
		String golden = fixture("academic_classification_response.json");
		assertThatCode(() -> mapper.treeToValue(payload(golden), AiAcademicClassificationResult.class)).doesNotThrowAnyException();
		respond(golden, run);
		List<AiAnalysisRequest.AiEvidenceInput> rows = List.of(
				input(artifact, "COMMIT_MESSAGE", "{}", null),
				input(UUID.randomUUID(), "SYLLABUS_VERSION", json(Map.of("syllabusVersionId", syllabus)), json(Map.of("syllabusVersionId", syllabus, "candidateContext", "COMPLETE"))),
				input(UUID.randomUUID(), "SYLLABUS_PHASE", json(Map.of("targetType", "PHASE", "targetId", phase, "syllabusVersionId", syllabus)), null));

		AiProviderResponse mapped = provider().analyze(academicRequest(run, rows));

		assertThat(mapped.result()).isInstanceOf(AiAcademicClassificationResult.class);
		assertThat(new AiAcademicResultValidator(mapper).invalidReason((AiAcademicClassificationResult) mapped.result(), rows)).isEmpty();
		assertThat(request.get().path("taxonomyVersion").asText()).isEqualTo("academic-taxonomy-v1");
		assertThat(httpInsideTransaction).isFalse();
	}

	@Test
	void taskIntelligenceGoldenFixtureMapsAndPreservesWireContract() throws Exception {
		UUID run = UUID.randomUUID();
		UUID evidence = UUID.fromString("33333333-3333-3333-3333-333333333301");
		String golden = fixture("task_intelligence_response.json");
		assertThatCode(() -> mapper.treeToValue(payload(golden), com.saga.be.ai.AiTaskIntelligenceResult.class)).doesNotThrowAnyException();
		respond(golden, run);

		AiProviderResponse mapped = provider().analyze(taskIntelligenceRequest(run, List.of(input(evidence, "TASK_FIELD", "{}", null))));

		assertThat(calls).hasValue(1);
		assertThat(request.get().path("analysisType").asText()).isEqualTo("TASK_INTELLIGENCE");
		assertThat(mapped.result()).isInstanceOf(com.saga.be.ai.AiTaskIntelligenceResult.class);
		assertThat(new AiTaskIntelligenceResultValidator().invalidReason((com.saga.be.ai.AiTaskIntelligenceResult) mapped.result(), java.util.Set.of(evidence))).isEmpty();
	}

	@Test
	void riskAnalysisGoldenFixtureMapsAndPreservesWireContract() throws Exception {
		UUID run = UUID.randomUUID();
		UUID evidence = UUID.fromString("33333333-3333-3333-3333-333333333401");
		String golden = fixture("risk_analysis_response.json");
		assertThatCode(() -> mapper.treeToValue(payload(golden), com.saga.be.ai.AiRiskAnalysisResult.class)).doesNotThrowAnyException();
		respond(golden, run);

		AiProviderResponse mapped = provider().analyze(riskAnalysisRequest(run, List.of(input(evidence, "TASK_FIELD", "{}", null))));

		assertThat(calls).hasValue(1);
		assertThat(request.get().path("analysisType").asText()).isEqualTo("RISK_ANALYSIS");
		assertThat(mapped.result()).isInstanceOf(com.saga.be.ai.AiRiskAnalysisResult.class);
		assertThat(new AiRiskAnalysisResultValidator().invalidReason((com.saga.be.ai.AiRiskAnalysisResult) mapped.result(), java.util.Set.of(evidence))).isEmpty();
	}

	@Test
	void progressNarrativeGoldenFixtureMapsAndPreservesWireContract() throws Exception {
		UUID run = UUID.randomUUID();
		UUID evidence = UUID.fromString("33333333-3333-3333-3333-333333333501");
		String golden = fixture("progress_narrative_response.json");
		assertThatCode(() -> mapper.treeToValue(payload(golden), com.saga.be.ai.AiProgressNarrativeResult.class)).doesNotThrowAnyException();
		respond(golden, run);

		AiProviderResponse mapped = provider().analyze(progressNarrativeRequest(run, List.of(input(evidence, "METADATA", "{}", null))));

		assertThat(calls).hasValue(1);
		assertThat(request.get().path("analysisType").asText()).isEqualTo("PROGRESS_NARRATIVE");
		assertThat(mapped.result()).isInstanceOf(com.saga.be.ai.AiProgressNarrativeResult.class);
		assertThat(new AiProgressNarrativeResultValidator().invalidReason((com.saga.be.ai.AiProgressNarrativeResult) mapped.result(), java.util.Set.of(evidence))).isEmpty();
	}

	@Test
	void envelopeMismatchesMalformedPayloadAndInvalidJsonAreRejectedOnce() throws Exception {
		UUID run = UUID.randomUUID();
		String commit = fixture("commit_intelligence_response.json");
		for (String field : List.of("contractVersion", "requestId", "analysisType")) {
			ObjectNode invalid = (ObjectNode) mapper.readTree(commit);
			invalid.put(field, "wrong");
			respond(mapper.writeValueAsString(invalid), run);
			assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
		}
		respond(fixture("academic_classification_response.json"), run);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
		respond(fixture("commit_intelligence_response.json"), run);
		assertSafeFailureOnce(academicRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
		ObjectNode unknownKind = (ObjectNode) mapper.readTree(commit);
		((ObjectNode) unknownKind.path("result")).put("kind", "UNKNOWN");
		respond(mapper.writeValueAsString(unknownKind), run);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
		ObjectNode malformed = (ObjectNode) mapper.readTree(commit);
		((ObjectNode) malformed.path("result")).put("payload", "not-an-object");
		respond(mapper.writeValueAsString(malformed), run);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
		respond("{", run);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_RESULT_INVALID");
	}

	@Test
	void typedRemoteErrorsAndGenericFailuresMapSafelyWithOneAttempt() throws Exception {
		UUID run = UUID.randomUUID();
		status.set(401);
		respond("{\"error\":{}}", run);
		status.set(401);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_RUNTIME_UNAVAILABLE");
		for (String code : List.of("AI_RUNTIME_DISABLED", "AI_RUNTIME_UNAVAILABLE", "AI_PROVIDER_AUTH_FAILED", "AI_PROVIDER_RATE_LIMITED", "AI_PROVIDER_TIMEOUT", "AI_PROVIDER_FAILED", "AI_PROVIDER_RESULT_INVALID", "AI_CONTRACT_VERSION_UNSUPPORTED")) {
			respond("{\"error\":{\"code\":\"" + code + "\"}}", run);
			status.set(503);
			assertSafeFailureOnce(commitRequest(run, List.of()), code);
		}
		respond("{\"error\":{}}", run);
		status.set(500);
		assertSafeFailureOnce(commitRequest(run, List.of()), "AI_PROVIDER_FAILED");
	}

	@Test
	void unavailableConfigurationFailsBeforeHttpAndRemoteTimeoutHasOneAttempt() throws Exception {
		AiAnalysisProperties unavailable = properties();
		unavailable.getRuntime().setInternalToken("");
		assertThatThrownBy(() -> new RemoteAiModelProvider(unavailable, mapper).analyze(commitRequest(UUID.randomUUID(), List.of())))
				.isInstanceOfSatisfying(AiProviderException.class, ex -> assertThat(ex.safeCode()).isEqualTo("AI_RUNTIME_NOT_CONFIGURED"));
		assertThat(calls).hasValue(0);

		AiAnalysisProperties timeoutProperties = properties();
		timeoutProperties.getRuntime().setTimeout(Duration.ofMillis(50));
		delayMillis.set(250);
		respond(fixture("commit_intelligence_response.json"), UUID.randomUUID());
		delayMillis.set(250);
		assertSafeFailureOnce(new RemoteAiModelProvider(timeoutProperties, mapper), commitRequest(UUID.randomUUID(), List.of()), "AI_PROVIDER_TIMEOUT");
	}

	@Test
	void connectionFailureHasOneObservedAttemptAndNoProviderFallback() throws Exception {
		try (ServerSocket socket = new ServerSocket(0)) {
			AtomicInteger connections = new AtomicInteger();
			Thread acceptor = Thread.ofVirtual().start(() -> {
				long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
				while (System.nanoTime() < deadline) {
					try {
						socket.setSoTimeout(100);
						try (var ignored = socket.accept()) {
							connections.incrementAndGet();
						}
					} catch (Exception ignored) {
						// Keep observing the raw listener until the one-second window closes.
					}
				}
			});
			AiAnalysisProperties connectionProperties = properties();
			connectionProperties.getRuntime().setBaseUrl("http://localhost:" + socket.getLocalPort());
			connectionProperties.getRuntime().setTimeout(Duration.ofMillis(100));
			assertThatThrownBy(() -> new RemoteAiModelProvider(connectionProperties, mapper).analyze(commitRequest(UUID.randomUUID(), List.of())))
					.isInstanceOfSatisfying(AiProviderException.class, ex -> assertThat(ex.safeCode()).isEqualTo("AI_PROVIDER_TIMEOUT"));
			acceptor.join(Duration.ofSeconds(2));
			assertThat(connections).hasValue(1);
			assertThat(List.of(RemoteAiModelProvider.class.getDeclaredFields())).noneMatch(field -> field.getType().equals(OpenAiModelProvider.class));
		}
	}

	private void assertSafeFailureOnce(AiAnalysisRequest input, String expectedCode) {
		assertSafeFailureOnce(provider(), input, expectedCode);
	}

	private void assertSafeFailureOnce(RemoteAiModelProvider remote, AiAnalysisRequest input, String expectedCode) {
		int before = calls.get();
		assertThatThrownBy(() -> remote.analyze(input))
				.isInstanceOfSatisfying(AiProviderException.class, ex -> assertThat(ex.safeCode()).isEqualTo(expectedCode));
		assertThat(calls).hasValue(before + 1);
		assertThat(httpInsideTransaction).isFalse();
	}

	private void respond(String body, UUID run) {
		respond(body, run, AiProviderRole.PRIMARY);
	}

	private void respond(String body, UUID run, AiProviderRole role) {
		status.set(200);
		delayMillis.set(0);
		response.set(body.replace("00000000-0000-0000-0000-000000000001", run + ":" + role)
				.replace("00000000-0000-0000-0000-000000000002", run + ":" + role)
				.replace("00000000-0000-0000-0000-000000000003", run + ":" + role)
				.replace("00000000-0000-0000-0000-000000000004", run + ":" + role)
				.replace("00000000-0000-0000-0000-000000000005", run + ":" + role));
	}

	private RemoteAiModelProvider provider() {
		return new RemoteAiModelProvider(properties(), mapper);
	}

	private AiAnalysisProperties properties() {
		AiAnalysisProperties properties = new AiAnalysisProperties();
		properties.setEnabled(true);
		properties.setPrimaryProvider("remote");
		properties.getRuntime().setEnabled(true);
		properties.getRuntime().setBaseUrl("http://localhost:" + server.getAddress().getPort());
		properties.getRuntime().setInternalToken("service-token");
		return properties;
	}

	private AiAnalysisRequest commitRequest(UUID run, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		return new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.COMMIT_INTELLIGENCE, "commit-intelligence-v1", null, "contract", evidence);
	}

	private AiAnalysisRequest academicRequest(UUID run, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		return new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.ACADEMIC_CLASSIFICATION, "academic-classification-v1", "academic-taxonomy-v1", "contract", evidence);
	}

	private AiAnalysisRequest taskIntelligenceRequest(UUID run, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		return new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.TASK_INTELLIGENCE, "task-intelligence-v1", null, "contract", evidence);
	}

	private AiAnalysisRequest riskAnalysisRequest(UUID run, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		return new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.RISK_ANALYSIS, "risk-analysis-v1", null, "contract", evidence);
	}

	private AiAnalysisRequest progressNarrativeRequest(UUID run, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
		return new AiAnalysisRequest(run, AiProviderRole.PRIMARY, AiAnalysisType.PROGRESS_NARRATIVE, "progress-narrative-v1", null, "contract", evidence);
	}

	private AiAnalysisRequest.AiEvidenceInput input(UUID id, String type, String payload, String metadata) {
		return new AiAnalysisRequest.AiEvidenceInput(id, type, type, payload, metadata);
	}

	private JsonNode payload(String response) throws Exception {
		return mapper.readTree(response).path("result").path("payload");
	}

	private String fixture(String name) throws Exception {
		return Files.readString(Path.of("..", "saga-ai", "tests", "fixtures", name));
	}

	private String json(Object value) throws Exception {
		return mapper.writeValueAsString(value);
	}
}

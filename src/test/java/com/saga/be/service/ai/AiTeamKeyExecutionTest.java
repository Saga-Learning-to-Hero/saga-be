package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiProvider;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.project.Project;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** A run paid by a team key: one attempt with the team's model, never the course fallback chain. */
class AiTeamKeyExecutionTest {

	private final ObjectMapper mapper = new ObjectMapper();
	private final UUID runId = UUID.randomUUID();
	private final UUID projectId = UUID.randomUUID();
	private final UUID teamCredentialId = UUID.randomUUID();
	private final AiCredentialEnvelope envelope = new AiCredentialEnvelope(1, "AES-256-GCM", "team-nonce", "team-ciphertext");

	private AiAnalysisStateService state;
	private AiCredentialResolver courseResolver;
	private TeamAiCredentialService teamKeys;
	private AiTaskIntelligenceFinalizationService taskFinalizer;
	private HttpServer server;
	private int status = 200;
	private String errorCode;
	private final List<JsonNode> requests = Collections.synchronizedList(new ArrayList<>());

	@BeforeEach
	void setUp() throws Exception {
		state = mock(AiAnalysisStateService.class);
		when(state.claim(runId)).thenReturn(true);
		courseResolver = mock(AiCredentialResolver.class);
		teamKeys = mock(TeamAiCredentialService.class);
		when(teamKeys.buildEnvelope(teamCredentialId, projectId)).thenReturn(envelope);
		taskFinalizer = mock(AiTaskIntelligenceFinalizationService.class);
		when(taskFinalizer.finalize(any(), any(), any())).thenReturn(true);
		server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
		server.createContext("/internal/v1/analyses", exchange -> {
			JsonNode request = mapper.readTree(exchange.getRequestBody());
			requests.add(request);
			String body = status == 200 ? success(request) : "{\"code\":\"" + errorCode + "\",\"message\":\"safe\"}";
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

	@Test
	void theTeamKeyAndTheTeamModelAreSent_andTheKeyIsMarkedWorking() {
		RemoteAiModelProvider remote = remote();
		loadTeamRun(remote);

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		JsonNode sent = requests.get(0);
		assertThat(sent.path("credentialSource").asText()).isEqualTo("COURSE");
		assertThat(sent.path("credentialEnvelope").path("ciphertext").asText()).isEqualTo("team-ciphertext");
		assertThat(sent.path("provider").path("name").asText()).isEqualTo("COHERE");
		assertThat(sent.path("provider").path("model").asText()).isEqualTo("command-a-plus-05-2026");
		verify(teamKeys).markSuccessful(teamCredentialId);
		verify(taskFinalizer).finalize(any(), any(), any());
		// the lecturer's keys and fallback chain are never touched
		verifyNoInteractions(courseResolver);
	}

	@Test
	void aRejectedTeamKeyIsMarkedInvalid_andTheRunFailsWithoutFallingBack() {
		status = 502; // saga-ai answers provider failures with 502 + a safe code
		errorCode = "AI_PROVIDER_AUTH_FAILED";
		RemoteAiModelProvider remote = remote();
		loadTeamRun(remote);

		service(remote).execute(runId);

		assertThat(requests).hasSize(1);
		verify(teamKeys).markInvalid(teamCredentialId, "AI_PROVIDER_AUTH_FAILED");
		verify(teamKeys, never()).markSuccessful(any());
		verify(state).fail(runId, "AI_PROVIDER_AUTH_FAILED", false);
		verifyNoInteractions(courseResolver);
	}

	@Test
	void anExhaustedTeamQuotaMarksTheKeyDegraded() {
		status = 429;
		errorCode = "AI_PROVIDER_QUOTA_EXHAUSTED";
		RemoteAiModelProvider remote = remote();
		loadTeamRun(remote);

		service(remote).execute(runId);

		verify(teamKeys).markDegraded(teamCredentialId, "AI_PROVIDER_QUOTA_EXHAUSTED");
		verify(state).fail(runId, "AI_PROVIDER_QUOTA_EXHAUSTED", false);
		verifyNoInteractions(courseResolver);
	}

	@Test
	void aRunWithoutTeamKeyWiringFailsSafely() {
		RemoteAiModelProvider remote = remote();
		loadTeamRun(remote);
		AiAnalysisExecutionService service = service(remote);
		service.setTeamKeys(null);

		service.execute(runId);

		assertThat(requests).isEmpty();
		verify(state).fail(runId, "AI_CREDENTIAL_ENVELOPE_UNAVAILABLE", false);
	}

	@Test
	void aCourseRunIsUnchanged_teamKeysAreNotConsulted() {
		RemoteAiModelProvider remote = remote();
		loadTeamRun(remote);
		AiAnalysisProviderDecision decision = state.loadExecution(runId).decision();
		decision.setTeamCredentialId(null);
		decision.setCourseCredentialId(UUID.randomUUID());
		when(courseResolver.buildEnvelope(any(), eq(AiProviderRole.PRIMARY), any())).thenReturn(envelope);
		when(courseResolver.primaryFallbackChain(any())).thenReturn(List.of());

		service(remote).execute(runId);

		verify(courseResolver).markSuccessful(decision.getCourseCredentialId());
		verifyNoInteractions(teamKeys);
	}

	private AiAnalysisExecutionService service(AiModelProvider remote) {
		AiAnalysisExecutionService service = new AiAnalysisExecutionService(state, List.of(remote), new AiStructuredResultValidator(), mapper,
				new AiAcademicResultValidator(mapper), null,
				new AiTaskIntelligenceResultValidator(), taskFinalizer,
				new AiRiskAnalysisResultValidator(), null,
				new AiProgressNarrativeResultValidator(), null,
				null, null, courseResolver);
		service.setTeamKeys(teamKeys);
		return service;
	}

	private void loadTeamRun(RemoteAiModelProvider remote) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setId(runId);
		run.setStatus(AiAnalysisStatus.RUNNING);
		run.setAnalysisType(AiAnalysisType.TASK_INTELLIGENCE);
		run.setPromptVersion("task-intelligence-v1");
		Project project = new Project();
		project.setId(projectId);
		run.setProject(project);
		AiAnalysisProviderDecision decision = new AiAnalysisProviderDecision();
		decision.setProviderRole(AiProviderRole.PRIMARY);
		decision.setProviderKey(remote.providerKey());
		decision.setProviderConfigHash(remote.providerConfigHash());
		decision.setCredentialSource(AiCredentialSource.COURSE);
		decision.setTeamCredentialId(teamCredentialId);
		decision.setCredentialFingerprint("team-fp");
		decision.setAiProvider(AiProvider.COHERE);
		decision.setModelId("command-a-plus-05-2026");
		when(state.loadExecution(runId)).thenReturn(new AiAnalysisStateService.ExecutionInput(run, List.of(), decision));
	}

	private String success(JsonNode request) {
		return "{\"requestId\":\"" + request.path("requestId").asText() + "\",\"analysisType\":\"TASK_INTELLIGENCE\",\"contractVersion\":\"saga-ai-inference-v1\","
				+ "\"provider\":{\"providerKey\":\"cohere\",\"modelId\":\"" + request.path("provider").path("model").asText() + "\",\"modelRevision\":null,\"responseId\":\"resp-1\"},"
				+ "\"usage\":{\"inputUnits\":3,\"outputUnits\":2,\"latencyMs\":5},"
				+ "\"result\":{\"kind\":\"TASK_INTELLIGENCE\",\"payload\":{\"evidenceStrength\":\"ACTIVE_PROGRESS\",\"summary\":\"ok\",\"deviationDetected\":false,\"deviationSummary\":null,\"evidence\":[],\"humanReviewRequired\":false}}}";
	}

	private RemoteAiModelProvider remote() {
		AiAnalysisProperties properties = new AiAnalysisProperties();
		properties.setEnabled(true);
		properties.setPrimaryProvider("remote");
		properties.getRuntime().setEnabled(true);
		properties.getRuntime().setBaseUrl("http://localhost:" + server.getAddress().getPort());
		properties.getRuntime().setInternalToken("service-token");
		return new RemoteAiModelProvider(properties, mapper);
	}
}

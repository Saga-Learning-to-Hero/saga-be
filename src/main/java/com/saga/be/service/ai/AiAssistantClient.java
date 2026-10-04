package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiProviderBinding;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialSource;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Sends one assistant question to saga-ai ({@code CHAT_ANSWER}) and returns its structured answer.
 *
 * <p>Deliberately separate from the analysis pipeline: a chat answer is synchronous, never stored as
 * an analysis run and never feeds assessment. It shares that pipeline's key rules: the course's own
 * credential when it has one, else the platform key only when the lecturer allows the platform
 * fallback (the same manual-request rule as the progress narrative). The course key is decrypted
 * and resealed right before the one HTTP call and never kept.
 */
@Component
@Profile("!test")
public class AiAssistantClient {

	public static final String PROMPT_VERSION = "chat-answer-v1";
	static final String CONTRACT = "saga-ai-inference-v1";
	static final String ANALYSIS_TYPE = "CHAT_ANSWER";
	private static final Logger log = LoggerFactory.getLogger(AiAssistantClient.class);

	/** One fact sent as a saga-ai evidence item. */
	public record Evidence(UUID id, String type, String sourceRef, Map<String, Object> payload) {}

	public record Citation(String kind, UUID id) {}

	public record Answer(
			String answer,
			List<Citation> citations,
			boolean insufficientData,
			boolean outOfScope,
			List<String> followUpQuestions,
			String credentialSource,
			String providerKey,
			String modelId,
			Long latencyMs) {}

	/** The AI could not answer; {@code code} is a safe reason (never a key or a provider body). */
	public static final class Unavailable extends RuntimeException {
		private final String code;

		public Unavailable(String code) {
			super(code);
			this.code = code;
		}

		public String code() {
			return code;
		}
	}

	private final AiAnalysisProperties props;
	private final ObjectMapper mapper;
	private final AiCredentialResolver credentials;
	private final RestClient client;

	@Autowired
	public AiAssistantClient(AiAnalysisProperties props, ObjectMapper mapper, AiCredentialResolver credentials) {
		this(props, mapper, credentials, client(props));
	}

	AiAssistantClient(AiAnalysisProperties props, ObjectMapper mapper, AiCredentialResolver credentials, RestClient client) {
		this.props = props;
		this.mapper = mapper;
		this.credentials = credentials;
		this.client = client;
	}

	private static RestClient client(AiAnalysisProperties props) {
		SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
		factory.setConnectTimeout(props.getRuntime().getTimeout());
		factory.setReadTimeout(props.getRuntime().getTimeout());
		return RestClient.builder().requestFactory(factory).build();
	}

	public boolean runtimeConfigured() {
		AiAnalysisProperties.Runtime runtime = props.getRuntime();
		return runtime.isEnabled() && !blank(runtime.getBaseUrl()) && !blank(runtime.getInternalToken());
	}

	private TeamAiCredentialService teamKeys;

	@Autowired(required = false)
	public void setTeamKeys(TeamAiCredentialService teamKeys) { this.teamKeys = teamKeys; }

	/**
	 * Which key answers this person in this project: a lecturer asks with the system (platform) key; a
	 * student's team asks with its own key (TEAM), else the course key only when the lecturer picked
	 * the team (COURSE, or PLATFORM where the course allows it), else UNAVAILABLE.
	 */
	public String keySource(UUID projectId, UUID courseId, boolean lecturer) {
		if (!runtimeConfigured()) {
			return AiCredentialResolver.Outcome.UNAVAILABLE.name();
		}
		if (lecturer) {
			return AiCredentialResolver.Outcome.PLATFORM.name();
		}
		try {
			AiCredentialResolver.Resolution resolution = resolveForTeam(projectId, courseId);
			return resolution.team() ? "TEAM" : resolution.outcome().name();
		} catch (RuntimeException ex) {
			return AiCredentialResolver.Outcome.UNAVAILABLE.name();
		}
	}

	/** One answer for a person in a project, following {@link #keySource(UUID, UUID, boolean)}. */
	public Answer ask(String requestId, UUID projectId, UUID courseId, boolean lecturer, List<Evidence> evidence, Map<String, Object> context) {
		if (!runtimeConfigured()) {
			throw new Unavailable("AI_RUNTIME_NOT_CONFIGURED");
		}
		List<Object> items = items(evidence);
		if (lecturer) {
			return send(requestId, courseId, items, context, null, null);
		}
		AiCredentialResolver.Resolution resolution;
		try {
			resolution = resolveForTeam(projectId, courseId);
		} catch (RuntimeException ex) {
			throw new Unavailable("AI_MODEL_NOT_SUPPORTED");
		}
		if (resolution.team()) {
			return sendTeam(requestId, projectId, items, context, resolution);
		}
		return answerWith(requestId, courseId, items, context, resolution);
	}

	private AiCredentialResolver.Resolution resolveForTeam(UUID projectId, UUID courseId) {
		return credentials.resolveForProject(projectId, courseId, AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.USER_REQUEST);
	}

	/** The team's own key and model: one attempt, never the course chain. */
	private Answer sendTeam(String requestId, UUID projectId, List<Object> items, Map<String, Object> context, AiCredentialResolver.Resolution resolution) {
		if (teamKeys == null) throw new Unavailable("AI_CREDENTIAL_ENVELOPE_UNAVAILABLE");
		UUID teamCredentialId = resolution.teamCredentialId();
		try {
			AiCredentialEnvelope envelope = teamKeys.buildEnvelope(teamCredentialId, projectId);
			Answer answer = dispatch(requestId, items, context, resolution.binding(), envelope, true);
			teamKeys.markSuccessful(teamCredentialId);
			return answer;
		} catch (AiCredentialCryptoException ex) {
			throw new Unavailable(ex.safeCode());
		} catch (Unavailable ex) {
			if (AiAnalysisExecutionService.CREDENTIAL_INVALID_CODES.contains(ex.code())) teamKeys.markInvalid(teamCredentialId, ex.code());
			else if (AiAnalysisExecutionService.CREDENTIAL_DEGRADED_CODES.contains(ex.code())) teamKeys.markDegraded(teamCredentialId, ex.code());
			throw ex;
		}
	}

	/** COURSE, PLATFORM or UNAVAILABLE: which key would answer for this course right now. */
	public String keySource(UUID courseId) {
		if (!runtimeConfigured()) {
			return AiCredentialResolver.Outcome.UNAVAILABLE.name();
		}
		try {
			return resolve(courseId).outcome().name();
		} catch (RuntimeException ex) {
			return AiCredentialResolver.Outcome.UNAVAILABLE.name();
		}
	}

	/**
	 * One answer. With a course binding the course's own fallback chain is followed exactly like the
	 * analysis pipeline: only after a quota / rate-limit / timeout / unavailable failure, each model at
	 * most once, each with the course's own key for that provider -- never the platform key.
	 */
	public Answer ask(String requestId, UUID courseId, List<Evidence> evidence, Map<String, Object> context) {
		if (!runtimeConfigured()) {
			throw new Unavailable("AI_RUNTIME_NOT_CONFIGURED");
		}
		AiCredentialResolver.Resolution resolution;
		try {
			resolution = resolve(courseId);
		} catch (RuntimeException ex) {
			throw new Unavailable("AI_MODEL_NOT_SUPPORTED");
		}
		return answerWith(requestId, courseId, items(evidence), context, resolution);
	}

	private List<Object> items(List<Evidence> evidence) {
		List<Object> items = new ArrayList<>();
		for (Evidence item : evidence) {
			items.add(Map.of("id", item.id().toString(), "type", item.type(), "sourceRef", item.sourceRef(),
					"payload", item.payload(), "metadata", Map.of()));
		}
		return items;
	}

	/** A course or platform resolution: the course key and its fallback chain, or the platform key. */
	private Answer answerWith(String requestId, UUID courseId, List<Object> items, Map<String, Object> context, AiCredentialResolver.Resolution resolution) {
		if (resolution.outcome() == AiCredentialResolver.Outcome.UNAVAILABLE) {
			throw new Unavailable("AI_CREDENTIAL_UNAVAILABLE");
		}
		if (resolution.outcome() == AiCredentialResolver.Outcome.PLATFORM) {
			return send(requestId, courseId, items, context, null, null);
		}
		List<Attempt> attempts = new ArrayList<>();
		attempts.add(new Attempt(resolution.binding(), resolution.courseCredentialId()));
		if (resolution.binding() != null) {
			Set<AiProviderBinding> tried = new HashSet<>();
			tried.add(resolution.binding());
			for (AiProviderBinding binding : credentials.primaryFallbackChain(courseId)) {
				if (tried.add(binding)) {
					credentials.usableCourseCredential(courseId, AiProviderRole.PRIMARY, binding.provider())
							.ifPresent(credential -> attempts.add(new Attempt(binding, credential.id())));
				}
			}
		}
		Unavailable last = null;
		for (Attempt attempt : attempts) {
			try {
				return send(requestId, courseId, items, context, attempt.binding(), attempt.credentialId());
			} catch (Unavailable ex) {
				last = ex;
				if (!AiAnalysisExecutionService.FALLBACK_ELIGIBLE_CODES.contains(ex.code())) {
					break;
				}
				log.info("assistant course fallback requestId={} fromProvider={} code={}", requestId,
						attempt.binding() == null ? null : attempt.binding().provider(), ex.code());
			}
		}
		throw last;
	}

	/** One model and one key; a null credential means the platform key. */
	private record Attempt(AiProviderBinding binding, UUID credentialId) {}

	private Answer send(
			String requestId, UUID courseId, List<Object> items, Map<String, Object> context, AiProviderBinding binding, UUID credentialId) {
		boolean course = credentialId != null;
		if (!course) {
			return dispatch(requestId, items, context, binding, null, false);
		}
		AiCredentialEnvelope envelope;
		try {
			// Decrypt-and-reseal exactly here, right before this one dispatch.
			envelope = credentials.buildEnvelope(credentialId, AiProviderRole.PRIMARY, courseId);
		} catch (AiCredentialCryptoException ex) {
			throw new Unavailable(ex.safeCode());
		}
		try {
			Answer answer = dispatch(requestId, items, context, binding, envelope, true);
			credentials.markSuccessful(credentialId);
			return answer;
		} catch (Unavailable ex) {
			if (AiAnalysisExecutionService.CREDENTIAL_INVALID_CODES.contains(ex.code())) {
				credentials.markInvalid(credentialId);
			} else if (AiAnalysisExecutionService.CREDENTIAL_DEGRADED_CODES.contains(ex.code())) {
				credentials.markDegraded(credentialId);
			}
			throw ex;
		}
	}

	/** One HTTP call to saga-ai: with a sealed key (COURSE wire format) or with the platform key. */
	private Answer dispatch(
			String requestId, List<Object> items, Map<String, Object> context, AiProviderBinding binding, AiCredentialEnvelope envelope, boolean course) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("requestId", requestId);
		body.put("analysisType", ANALYSIS_TYPE);
		body.put("contractVersion", CONTRACT);
		body.put("promptVersion", PROMPT_VERSION);
		body.put("taxonomyVersion", null);
		body.put("provider", providerBody(binding));
		body.put("credentialSource", (course ? AiCredentialSource.COURSE : AiCredentialSource.PLATFORM).name());
		body.put("evidence", items);
		body.put("context", context);
		try {
			if (course) {
				body.put("credentialEnvelope", Map.of("version", envelope.version(), "algorithm", envelope.algorithm(),
						"nonce", envelope.nonce(), "ciphertext", envelope.ciphertext()));
			}
			String raw = client.post()
					.uri(props.getRuntime().getBaseUrl() + "/internal/v1/analyses")
					.contentType(MediaType.APPLICATION_JSON)
					.header("Authorization", "Bearer " + props.getRuntime().getInternalToken())
					.body(body)
					.retrieve()
					.body(String.class);
			return parse(requestId, raw, course);
		} catch (Unavailable ex) {
			throw ex;
		} catch (RestClientResponseException ex) {
			// 422 = saga-ai rejected the request shape: a runtime older than the CHAT_ANSWER contract.
			String code = ex.getStatusCode().value() == 422 ? "AI_RUNTIME_OUTDATED" : RemoteAiErrorCodes.from(mapper, ex);
			log.warn("assistant answer failed requestId={} provider={} code={}", requestId,
					binding == null ? null : binding.provider(), code);
			throw new Unavailable(code);
		} catch (RestClientException ex) {
			log.warn("assistant answer failed requestId={} code=AI_PROVIDER_TIMEOUT", requestId);
			throw new Unavailable("AI_PROVIDER_TIMEOUT");
		} catch (RuntimeException ex) {
			log.warn("assistant answer failed requestId={} code=AI_PROVIDER_RESULT_INVALID type={}", requestId, ex.getClass().getSimpleName());
			throw new Unavailable("AI_PROVIDER_RESULT_INVALID");
		}
	}

	/** A chat follows the progress narrative's manual-request rule for the platform fallback. */
	private AiCredentialResolver.Resolution resolve(UUID courseId) {
		return credentials.resolve(courseId, AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.USER_REQUEST);
	}

	private Map<String, Object> providerBody(AiProviderBinding binding) {
		Map<String, Object> provider = new LinkedHashMap<>();
		provider.put("role", AiProviderRole.PRIMARY.name());
		if (binding == null) {
			provider.put("model", props.getOpenai().getModel());
		} else {
			provider.put("model", binding.modelId());
			provider.put("name", binding.provider().name());
		}
		provider.put("reasoningEffort", props.getOpenai().getReasoningEffort());
		return provider;
	}

	private Answer parse(String requestId, String raw, boolean course) {
		JsonNode response;
		try {
			response = mapper.readTree(raw);
		} catch (Exception ex) {
			throw new Unavailable("AI_PROVIDER_RESULT_INVALID");
		}
		if (response == null
				|| !requestId.equals(response.path("requestId").asText())
				|| !ANALYSIS_TYPE.equals(response.path("analysisType").asText())
				|| !CONTRACT.equals(response.path("contractVersion").asText())
				|| !ANALYSIS_TYPE.equals(response.path("result").path("kind").asText())) {
			throw new Unavailable("AI_PROVIDER_RESULT_INVALID");
		}
		JsonNode payload = response.path("result").path("payload");
		String answer = payload.path("answer").asText("");
		if (answer.isBlank() || !payload.path("insufficientData").isBoolean() || !payload.path("outOfScope").isBoolean()
				|| !payload.path("citations").isArray() || !payload.path("followUpQuestions").isArray()) {
			throw new Unavailable("AI_PROVIDER_RESULT_INVALID");
		}
		List<Citation> citations = new ArrayList<>();
		for (JsonNode citation : payload.path("citations")) {
			UUID id = uuid(citation.path("evidenceId").asText(null));
			String kind = citation.path("kind").asText(null);
			if (id != null && kind != null) {
				citations.add(new Citation(kind, id));
			}
		}
		List<String> followUps = new ArrayList<>();
		for (JsonNode question : payload.path("followUpQuestions")) {
			if (question.isTextual() && !question.asText().isBlank()) {
				followUps.add(question.asText().strip());
			}
		}
		JsonNode provider = response.path("provider");
		JsonNode usage = response.path("usage");
		return new Answer(
				answer.strip(),
				citations,
				payload.path("insufficientData").asBoolean(),
				payload.path("outOfScope").asBoolean(),
				followUps,
				(course ? AiCredentialSource.COURSE : AiCredentialSource.PLATFORM).name(),
				provider.path("providerKey").asText(null),
				provider.path("modelId").asText(null),
				usage.path("latencyMs").isNumber() ? usage.path("latencyMs").asLong() : null);
	}

	private static UUID uuid(String value) {
		try {
			return value == null ? null : UUID.fromString(value);
		} catch (IllegalArgumentException ex) {
			return null;
		}
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}
}

package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.AiProviderRole;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Primary Responses API adapter. All input is the immutable persisted evidence bundle. */
@Component
@Profile("!test & !local")
@ConditionalOnExpression("'${saga.ai.enabled:false}' == 'true' and '${saga.ai.primary-provider:openai}' == 'openai'")
public class OpenAiModelProvider implements AiModelProvider {
	private final AiAnalysisProperties properties; private final ObjectMapper mapper; private final RestClient client;
	public OpenAiModelProvider(AiAnalysisProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; this.client = RestClient.builder().baseUrl(properties.getOpenai().getBaseUrl()).build(); }
	public AiProviderRole role() { return AiProviderRole.PRIMARY; }
	public String providerKey() { return "openai"; }
	public String providerConfigHash() { return AiHashes.sha256("openai|" + properties.getOpenai().getModel() + "|" + properties.getOpenai().getReasoningEffort() + "|commit-intelligence-v1|ai-2-schema-v1"); }
	public String modelId() { return properties.getOpenai().getModel(); }
	public AiProviderResponse analyze(AiAnalysisRequest request) {
		if (properties.getOpenai().getApiKey() == null || properties.getOpenai().getApiKey().isBlank()) throw new AiProviderException("AI_PROVIDER_NOT_CONFIGURED");
		try {
			Map<String, Object> body = new LinkedHashMap<>(); body.put("model", modelId()); body.put("reasoning", Map.of("effort", properties.getOpenai().getReasoningEffort())); body.put("max_output_tokens", properties.getOpenai().getMaxOutputTokens());
			body.put("input", List.of(Map.of("role", "developer", "content", request.systemContract()), Map.of("role", "user", "content", compactEvidence(request))));
			body.put("text", Map.of("format", Map.of("type", "json_schema", "name", "commit_intelligence", "strict", true, "schema", schema())));
			JsonNode response = client.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON).header("Authorization", "Bearer " + properties.getOpenai().getApiKey()).body(body).retrieve().body(JsonNode.class);
			String text = outputText(response); if (text == null) throw new AiProviderException("AI_ANALYSIS_RESULT_INVALID");
			AiStructuredResult result = mapper.readValue(text, AiStructuredResult.class); JsonNode usage = response == null ? null : response.path("usage");
			return new AiProviderResponse(result, latency(response), usage == null || usage.isMissingNode() ? null : usage.path("input_tokens").asLong(), usage == null || usage.isMissingNode() ? null : usage.path("output_tokens").asLong(), response == null ? null : response.path("model").asText(null), response == null ? null : mapper.writeValueAsString(Map.of("responseId", response.path("id").asText(""), "reasoningEffort", properties.getOpenai().getReasoningEffort(), "status", response.path("status").asText(""))));
		} catch (AiProviderException ex) { throw ex; }
		catch (RestClientResponseException ex) { int code = ex.getStatusCode().value(); throw new AiProviderException(code == 401 || code == 403 ? "AI_PROVIDER_AUTH_FAILED" : code == 429 ? "AI_PROVIDER_RATE_LIMITED" : "AI_ANALYSIS_PROVIDER_FAILED"); }
		catch (RestClientException ex) { throw new AiProviderException("AI_PROVIDER_TIMEOUT"); }
		catch (Exception ex) { throw new AiProviderException("AI_ANALYSIS_RESULT_INVALID"); }
	}
	private String compactEvidence(AiAnalysisRequest request) throws Exception { return mapper.writeValueAsString(Map.of("runId", request.runId(), "evidence", request.evidence())); }
	private static Long latency(JsonNode response) { return response != null && response.has("latency_ms") ? response.path("latency_ms").asLong() : null; }
	private static String outputText(JsonNode node) { if (node == null) return null; if (node.hasNonNull("output_text")) return node.path("output_text").asText(); for (JsonNode output : node.path("output")) for (JsonNode content : output.path("content")) if (content.hasNonNull("text")) return content.path("text").asText(); return null; }
	private static Map<String, Object> schema() { return Map.of("type", "object", "additionalProperties", false, "required", List.of("commitMessageAssessment", "codeAssessment", "taskAlignments", "taskAlignmentSummary", "academicClassifications", "overallDecision", "humanReviewRequired"), "properties", Map.of("commitMessageAssessment", Map.of("type", "object"), "codeAssessment", Map.of("type", "object"), "taskAlignments", Map.of("type", "array"), "taskAlignmentSummary", Map.of("type", "string"), "academicClassifications", Map.of("type", "array", "maxItems", 0), "overallDecision", Map.of("type", "string"), "humanReviewRequired", Map.of("type", "boolean"))); }
}

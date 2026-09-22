package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.config.AiAnalysisProperties;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/** Primary Responses API adapter. It receives only the immutable persisted evidence bundle. */
@Component @Profile("!test & !local")
@ConditionalOnExpression("'${saga.ai.enabled:false}' == 'true' and '${saga.ai.primary-provider:openai}' == 'openai'")
public class OpenAiModelProvider implements AiModelProvider {
 private final AiAnalysisProperties properties; private final ObjectMapper mapper; private final RestClient client;
 public OpenAiModelProvider(AiAnalysisProperties properties,ObjectMapper mapper){this(properties,mapper,RestClient.builder().baseUrl(properties.getOpenai().getBaseUrl()).build());}
 OpenAiModelProvider(AiAnalysisProperties properties,ObjectMapper mapper,RestClient client){this.properties=properties;this.mapper=mapper;this.client=client;}
 public AiProviderRole role(){return AiProviderRole.PRIMARY;} public String providerKey(){return "openai";} public String providerConfigHash(){return AiHashes.sha256("openai|"+properties.getOpenai().getModel()+"|"+properties.getOpenai().getReasoningEffort()+"|commit-intelligence-v1|ai-2-schema-v1");} public String modelId(){return properties.getOpenai().getModel();}
 public AiProviderResponse analyze(AiAnalysisRequest request) {
  if(properties.getOpenai().getApiKey()==null||properties.getOpenai().getApiKey().isBlank())throw new AiProviderException("AI_PROVIDER_NOT_CONFIGURED");
  try { DispatchProfile profile=profile(request.analysisType()); Map<String,Object> body=new LinkedHashMap<>();body.put("model",modelId());body.put("reasoning",Map.of("effort",properties.getOpenai().getReasoningEffort()));body.put("max_output_tokens",properties.getOpenai().getMaxOutputTokens());body.put("input",List.of(Map.of("role","developer","content",request.systemContract()),Map.of("role","user","content",compactEvidence(request))));body.put("text",Map.of("format",Map.of("type","json_schema","name",profile.schemaName(),"strict",true,"schema",profile.schema())));
   JsonNode response=client.post().uri("/v1/responses").contentType(MediaType.APPLICATION_JSON).header("Authorization","Bearer "+properties.getOpenai().getApiKey()).body(body).retrieve().body(JsonNode.class);String text=outputText(response);if(text==null)throw new AiProviderException("AI_ANALYSIS_RESULT_INVALID");AiAnalysisResult result=profile.type()==AiAnalysisType.ACADEMIC_CLASSIFICATION?mapper.readValue(text,AiAcademicClassificationResult.class):mapper.readValue(text,AiStructuredResult.class);JsonNode usage=response==null?null:response.path("usage");return new AiProviderResponse(result,latency(response),usage==null||usage.isMissingNode()?null:usage.path("input_tokens").asLong(),usage==null||usage.isMissingNode()?null:usage.path("output_tokens").asLong(),response==null?null:response.path("model").asText(null),response==null?null:mapper.writeValueAsString(Map.of("responseId",response.path("id").asText(""),"reasoningEffort",properties.getOpenai().getReasoningEffort(),"status",response.path("status").asText(""))));
  } catch(AiProviderException ex){throw ex;}catch(RestClientResponseException ex){int code=ex.getStatusCode().value();throw new AiProviderException(code==401||code==403?"AI_PROVIDER_AUTH_FAILED":code==429?"AI_PROVIDER_RATE_LIMITED":"AI_ANALYSIS_PROVIDER_FAILED");}catch(RestClientException ex){throw new AiProviderException("AI_PROVIDER_TIMEOUT");}catch(Exception ex){throw new AiProviderException("AI_ANALYSIS_RESULT_INVALID");}
 }
 DispatchProfile profile(AiAnalysisType type){if(type==AiAnalysisType.COMMIT_INTELLIGENCE)return new DispatchProfile(type,"commit_intelligence",commitSchema());if(type==AiAnalysisType.ACADEMIC_CLASSIFICATION)return new DispatchProfile(type,"academic_classification",academicSchema());throw new AiProviderException("AI_ANALYSIS_RESULT_INVALID");}
 private String compactEvidence(AiAnalysisRequest request)throws Exception {List<AiAnalysisRequest.AiEvidenceInput> artifact=new ArrayList<>(),candidates=new ArrayList<>();for(var row:request.evidence())if("SYLLABUS_VERSION".equals(row.type())||"SYLLABUS_PHASE".equals(row.type())||"SYLLABUS_DELIVERABLE".equals(row.type()))candidates.add(row);else artifact.add(row);return mapper.writeValueAsString(Map.of("runId",request.runId(),"analysisType",request.analysisType(),"promptVersion",request.promptVersion(),"taxonomyVersion",request.taxonomyVersion()==null?"":request.taxonomyVersion(),"artifactEvidence",artifact,"academicCandidateEvidence",candidates));}
 private static Long latency(JsonNode response){return response!=null&&response.has("latency_ms")?response.path("latency_ms").asLong():null;} private static String outputText(JsonNode node){if(node==null)return null;if(node.hasNonNull("output_text"))return node.path("output_text").asText();for(JsonNode output:node.path("output"))for(JsonNode content:output.path("content"))if(content.hasNonNull("text"))return content.path("text").asText();return null;}
 static Map<String,Object> commitSchema(){return Map.of("type","object","additionalProperties",false,"required",List.of("commitMessageAssessment","codeAssessment","taskAlignments","taskAlignmentSummary","academicClassifications","overallDecision","humanReviewRequired"),"properties",Map.of("commitMessageAssessment",Map.of("type","object"),"codeAssessment",Map.of("type","object"),"taskAlignments",Map.of("type","array"),"taskAlignmentSummary",Map.of("type","string"),"academicClassifications",Map.of("type","array","maxItems",0),"overallDecision",Map.of("type","string"),"humanReviewRequired",Map.of("type","boolean")));}
 static Map<String,Object> academicSchema(){Map<String,Object> classification=Map.of("type","object","additionalProperties",false,"required",List.of("targetType","targetId","confidence","summary","evidence"),"properties",Map.of("targetType",Map.of("type","string","enum",List.of("PHASE","EXPECTED_DELIVERABLE")),"targetId",Map.of("type","string"),"confidence",Map.of("type","number","minimum",0,"maximum",1),"summary",Map.of("type","string"),"evidence",Map.of("type","array","items",Map.of("type","string"))));return Map.of("type","object","additionalProperties",false,"required",List.of("classificationDecision","classifications","summary","humanReviewRequired"),"properties",Map.of("classificationDecision",Map.of("type","string","enum",List.of("PROPOSED","UNCLASSIFIED","INSUFFICIENT_EVIDENCE")),"classifications",Map.of("type","array","items",classification),"summary",Map.of("type","string"),"humanReviewRequired",Map.of("type","boolean")));}
 record DispatchProfile(AiAnalysisType type,String schemaName,Map<String,Object> schema) {}
}

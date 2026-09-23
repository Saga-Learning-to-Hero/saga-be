package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

/** Validates an academic response solely against this run's immutable evidence bundle. */
@Component @Profile("!test")
public class AiAcademicResultValidator {
 private final ObjectMapper mapper;
 public AiAcademicResultValidator(ObjectMapper mapper) { this.mapper=mapper; }
 public Optional<String> invalidReason(AiAcademicClassificationResult result, List<AiAnalysisRequest.AiEvidenceInput> evidence) {
  if(result==null || result.classificationDecision()==null || result.classifications()==null || !result.humanReviewRequired()) return Optional.of("MISSING_OR_UNSAFE_ACADEMIC_RESULT");
  Candidates candidates=candidates(evidence); if(candidates.invalid()) return Optional.of("INVALID_CANDIDATE_CONTEXT");
  if(result.classificationDecision()==AiAcademicClassificationDecision.PROPOSED) { if(result.classifications().isEmpty()) return Optional.of("PROPOSED_WITHOUT_CLASSIFICATIONS"); if(!candidates.complete()) return Optional.of("INCOMPLETE_CANDIDATE_CONTEXT"); }
  else if(!result.classifications().isEmpty()) return Optional.of("NON_PROPOSED_WITH_CLASSIFICATIONS");
  Set<String> seen=new HashSet<>(); Map<UUID,AiAnalysisRequest.AiEvidenceInput> byId=new HashMap<>(); for(var item:evidence) if(item!=null && item.id()!=null) byId.put(item.id(),item);
  for(AiAcademicProposedClassification item:result.classifications()) {
   if(item==null || !legalType(item.targetType()) || !uuid(item.targetId()).isPresent() || !confidence(item.confidence()) || item.evidence()==null || item.evidence().isEmpty()) return Optional.of("INVALID_CLASSIFICATION");
   UUID target=uuid(item.targetId()).orElseThrow(); String key=item.targetType()+":"+target; if(!seen.add(key)) return Optional.of("DUPLICATE_TARGET"); Candidate candidate=candidates.rows().get(key); if(candidate==null) return Optional.of("TARGET_NOT_IN_CANDIDATES");
   boolean artifact=false,candidateEvidence=false;
   for(String ref:item.evidence()) { Optional<UUID> id=uuid(ref); if(id.isEmpty() || !byId.containsKey(id.get())) return Optional.of("UNKNOWN_EVIDENCE_REFERENCE"); AiAnalysisRequest.AiEvidenceInput row=byId.get(id.get()); if(forbidden(row.type())) return Optional.of("FORBIDDEN_EVIDENCE_REFERENCE"); if(candidate.evidenceId().equals(id.get())) candidateEvidence=true; if(artifact(row.type())) artifact=true; }
   if(!artifact) return Optional.of("MISSING_ARTIFACT_EVIDENCE"); if(!candidateEvidence) return Optional.of("MISSING_MATCHING_CANDIDATE_EVIDENCE");
  }
  return Optional.empty();
 }
 private Candidates candidates(List<AiAnalysisRequest.AiEvidenceInput> evidence) {
  UUID syllabus=null; boolean complete=true; Map<String,Candidate> out=new HashMap<>();
  for(var row:evidence) try { if(row==null || row.payloadJson()==null) continue; JsonNode json=mapper.readTree(row.payloadJson()); if("SYLLABUS_VERSION".equals(row.type())) { JsonNode metadata=row.metadataJson()==null?json:mapper.readTree(row.metadataJson()); syllabus=uuid(metadata.path("syllabusVersionId").asText(json.path("syllabusVersionId").asText(null))).orElse(null); String coverage=metadata.path("candidateContext").asText(json.path("candidateContext").asText("COMPLETE")); complete="COMPLETE".equals(coverage); } }
  catch(Exception ignored) { return new Candidates(null,false,Map.of(),true); }
  if(syllabus==null) return new Candidates(null,complete,Map.of(),true);
  for(var row:evidence) try { if(row==null || row.id()==null || row.payloadJson()==null || !("SYLLABUS_PHASE".equals(row.type()) || "SYLLABUS_DELIVERABLE".equals(row.type()))) continue; JsonNode json=mapper.readTree(row.payloadJson()); String type=json.path("targetType").asText(null); UUID target=uuid(json.path("targetId").asText(null)).orElse(null); UUID candidateSyllabus=uuid(json.path("syllabusVersionId").asText(null)).orElse(null); if(!legalType(type)||target==null||candidateSyllabus==null||!syllabus.equals(candidateSyllabus)) return new Candidates(syllabus,complete,Map.of(),true); out.put(type+":"+target,new Candidate(row.id())); }
  catch(Exception ignored) { return new Candidates(syllabus,complete,Map.of(),true); }
  return new Candidates(syllabus,complete,Map.copyOf(out),false);
 }
 private static boolean legalType(String type){return "PHASE".equals(type)||"EXPECTED_DELIVERABLE".equals(type);} private static boolean confidence(Double value){return value!=null&&value>=0d&&value<=1d;} private static Optional<UUID> uuid(String value){try{return value==null?Optional.empty():Optional.of(UUID.fromString(value));}catch(IllegalArgumentException ex){return Optional.empty();}} private static boolean forbidden(String type){return "EXCLUSION_MANIFEST".equals(type);}
 /** Confirmed examples are citable context, not a substitute for real artifact evidence. */
 private static boolean contextOnly(String type){return "HUMAN_CONFIRMED_EXAMPLE".equals(type);}
 private static boolean artifact(String type){return type!=null&&!type.startsWith("SYLLABUS_")&&!forbidden(type)&&!contextOnly(type);}
 private record Candidate(UUID evidenceId) {} private record Candidates(UUID syllabusId,boolean complete,Map<String,Candidate> rows,boolean invalid) {}
}

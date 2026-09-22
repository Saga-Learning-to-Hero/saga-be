package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.academic.*;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The run CAS, provider decision, and every AI proposal commit or roll back together. */
@Service @Profile("!test")
public class AiAcademicProposalFinalizationService {
 private final AiAnalysisRunRepository runs; private final AiAnalysisProviderDecisionRepository decisions; private final AiAcademicClassificationRepository classifications; private final SubjectSyllabusVersionRepository syllabuses; private final SyllabusPhaseRepository phases; private final SyllabusExpectedDeliverableRepository deliverables; private final ObjectMapper mapper;
 public AiAcademicProposalFinalizationService(AiAnalysisRunRepository runs,AiAnalysisProviderDecisionRepository decisions,AiAcademicClassificationRepository classifications,SubjectSyllabusVersionRepository syllabuses,SyllabusPhaseRepository phases,SyllabusExpectedDeliverableRepository deliverables,ObjectMapper mapper){this.runs=runs;this.decisions=decisions;this.classifications=classifications;this.syllabuses=syllabuses;this.phases=phases;this.deliverables=deliverables;this.mapper=mapper;}
 @Transactional public boolean finalize(AiAnalysisStateService.ExecutionInput input,AiProviderResponse response,String resultJson) {
  UUID runId=input.run().getId(); LocalDateTime now=LocalDateTime.now();
  // DB CAS first: a late response returns false before any proposal can be written.
  if(runs.completeRunning(runId,now)!=1)return false;
  if(decisions.completeRunning(runId,resultJson,true,response.latencyMs(),response.inputUnits(),response.outputUnits(),response.modelRevision(),response.costMetadataJson(),now)!=1)throw new IllegalStateException("AI academic completion is missing a running provider decision");
  AiAcademicClassificationResult result=(AiAcademicClassificationResult)response.result();
  if(result.classificationDecision()!=AiAcademicClassificationDecision.PROPOSED)return true;
  Candidates candidates=candidates(input.evidence());
  List<AiAcademicClassification> rows=new ArrayList<>(); for(AiAcademicProposedClassification proposal:result.classifications())rows.add(row(input.run(),proposal,candidates,now));
  classifications.saveAllAndFlush(rows); return true;
 }
 private AiAcademicClassification row(AiAnalysisRun run,AiAcademicProposedClassification proposal,Candidates candidates,LocalDateTime now) {
  UUID target=UUID.fromString(proposal.targetId()); Candidate candidate=Optional.ofNullable(candidates.byKey().get(proposal.targetType()+":"+target)).orElseThrow(()->new IllegalStateException("Validated academic target is absent from immutable candidates")); SubjectSyllabusVersion syllabus=syllabuses.findById(candidate.syllabusId()).orElseThrow();
  AiAcademicClassification row=new AiAcademicClassification();row.setProject(run.getProject());row.setAnalysisRun(run);row.setArtifactType(run.getArtifactType());row.setArtifactId(run.getArtifactId());row.setArtifactRevision(run.getArtifactRevision());row.setSyllabusVersion(syllabus);row.setConfidence(proposal.confidence());row.setAiSummary(proposal.summary());row.setStatus(AiAcademicClassificationStatus.PROPOSED);row.setProvenance(AiAcademicProvenance.AI);
  if("PHASE".equals(proposal.targetType())){SyllabusPhase phase=phases.findById(target).filter(x->candidate.syllabusId().equals(x.getSyllabusVersionId())).orElseThrow();row.setTargetType(AiAcademicTargetType.PHASE);row.setPhase(phase);}else {SyllabusExpectedDeliverable deliverable=deliverables.findById(target).filter(x->candidate.syllabusId().equals(x.getSyllabusVersionId())).orElseThrow();row.setTargetType(AiAcademicTargetType.EXPECTED_DELIVERABLE);row.setDeliverable(deliverable);} return row;
 }
 private Candidates candidates(List<AiAnalysisEvidence> evidence){UUID syllabus=null;Map<String,Candidate> rows=new HashMap<>();try{for(var row:evidence)if(row.getEvidenceType()==AiEvidenceType.SYLLABUS_VERSION){JsonNode payload=mapper.readTree(row.getPayloadJson());JsonNode metadata=row.getMetadataJson()==null?payload:mapper.readTree(row.getMetadataJson());syllabus=UUID.fromString(metadata.path("syllabusVersionId").asText(payload.path("syllabusVersionId").asText()));}if(syllabus==null)throw new IllegalStateException("Academic run has no pinned syllabus evidence");for(var row:evidence)if(row.getEvidenceType()==AiEvidenceType.SYLLABUS_PHASE||row.getEvidenceType()==AiEvidenceType.SYLLABUS_DELIVERABLE){JsonNode payload=mapper.readTree(row.getPayloadJson());UUID candidateSyllabus=UUID.fromString(payload.path("syllabusVersionId").asText());if(!syllabus.equals(candidateSyllabus))throw new IllegalStateException("Candidate syllabus mismatch");rows.put(payload.path("targetType").asText()+":"+payload.path("targetId").asText(),new Candidate(candidateSyllabus));}}catch(Exception ex){throw new IllegalStateException("Academic candidate evidence is invalid",ex);}return new Candidates(Map.copyOf(rows));}
 private record Candidate(UUID syllabusId) {} private record Candidates(Map<String,Candidate> byKey) {}
}

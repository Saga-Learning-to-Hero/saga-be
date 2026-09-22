package com.saga.be.service.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.*;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class AiAcademicExecutionValidationTest {
 @Test void invalidAcademicResultFailsSafelyWithoutRetryOrPartialCompletion() throws Exception {
  UUID runId=UUID.randomUUID(),syllabus=UUID.randomUUID(),phase=UUID.randomUUID(); AiAnalysisStateService state=mock(AiAnalysisStateService.class);when(state.claim(runId)).thenReturn(true); AiAnalysisRun run=new AiAnalysisRun();run.setId(runId);run.setStatus(AiAnalysisStatus.RUNNING);run.setAnalysisType(AiAnalysisType.ACADEMIC_CLASSIFICATION);run.setPromptVersion("academic-classification-v1");run.setTaxonomyVersion("academic-taxonomy-v1"); AiAnalysisProviderDecision decision=new AiAnalysisProviderDecision();decision.setProviderRole(AiProviderRole.PRIMARY);decision.setProviderKey("test");decision.setProviderConfigHash("cfg"); when(state.loadExecution(runId)).thenReturn(new AiAnalysisStateService.ExecutionInput(run,List.of(evidence(UUID.randomUUID(),AiEvidenceType.COMMIT_MESSAGE,"{}"),evidence(UUID.randomUUID(),AiEvidenceType.SYLLABUS_VERSION,json(Map.of("syllabusVersionId",syllabus,"candidateContext","COMPLETE"))),evidence(UUID.randomUUID(),AiEvidenceType.SYLLABUS_PHASE,json(Map.of("targetType","PHASE","targetId",phase,"syllabusVersionId",syllabus)))),decision));
  AiModelProvider provider=mock(AiModelProvider.class);when(provider.role()).thenReturn(AiProviderRole.PRIMARY);when(provider.providerKey()).thenReturn("test");when(provider.providerConfigHash()).thenReturn("cfg");when(provider.analyze(any())).thenReturn(new AiProviderResponse(new AiAcademicClassificationResult(AiAcademicClassificationDecision.PROPOSED,List.of(),"bad",true),null,null,null,null,null));
  new AiAnalysisExecutionService(state,List.of(provider),new AiStructuredResultValidator(),new ObjectMapper()).execute(runId);
  verify(state).fail(runId,"AI_ANALYSIS_RESULT_INVALID",false);verify(state,never()).complete(any(),anyString(),anyBoolean(),any(),any(),any(),any(),any());verify(provider,times(1)).analyze(any());
 }
 private static AiAnalysisEvidence evidence(UUID id,AiEvidenceType type,String payload){AiAnalysisEvidence row=new AiAnalysisEvidence();row.setId(id);row.setEvidenceType(type);row.setSourceRef(type.name());row.setPayloadJson(payload);return row;} private static String json(Object value)throws Exception{return new ObjectMapper().writeValueAsString(value);}
}

package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiModelProvider;
import com.saga.be.controller.ProjectAiAnalysisController;
import com.saga.be.dto.ai.AiAnalysisResponse;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.academic.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.IntegrationException;
import com.saga.be.persistence.TrackingPlatformTransactionManager;
import com.saga.be.repository.*;
import com.saga.be.security.SagaUserPrincipal;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AiAcademicCommitSubmissionServiceTest {
 private final UUID userId=UUID.randomUUID(),projectId=UUID.randomUUID(),commitId=UUID.randomUUID(),repoId=UUID.randomUUID(),syllabusId=UUID.randomUUID(),phaseOneId=UUID.randomUUID(),phaseTwoId=UUID.randomUUID(),deliverableOneId=UUID.randomUUID(),deliverableTwoId=UUID.randomUUID();
 private final String persistedSha="a1b2c3d4";
 private ProjectDataAuthorization auth; private GitCommitRepository commits; private TaskGitCommitLinkRepository links; private JiraTaskFailoverItemRepository failover; private AiAcademicContextService context; private AiCommitEvidenceSnapshotBuilder snapshots; private AiGitHubCommitEvidenceAcquirer github; private AiAnalysisRunRepository runs; private AiAnalysisEvidenceRepository evidence; private AiAnalysisProviderDecisionRepository decisions; private AiAnalysisExecutor executor; private AiModelProvider provider; private TrackingPlatformTransactionManager transactions; private GitCommit commit; private AiCredentialResolver credentialResolver;

 @BeforeEach void setUp() {
  auth=mock(ProjectDataAuthorization.class); commits=mock(GitCommitRepository.class); links=mock(TaskGitCommitLinkRepository.class); failover=mock(JiraTaskFailoverItemRepository.class); context=mock(AiAcademicContextService.class); snapshots=mock(AiCommitEvidenceSnapshotBuilder.class); github=mock(AiGitHubCommitEvidenceAcquirer.class); runs=mock(AiAnalysisRunRepository.class); evidence=mock(AiAnalysisEvidenceRepository.class); decisions=mock(AiAnalysisProviderDecisionRepository.class); executor=mock(AiAnalysisExecutor.class); provider=mock(AiModelProvider.class); transactions=new TrackingPlatformTransactionManager(); credentialResolver=mock(AiCredentialResolver.class);
  when(credentialResolver.resolve(any(),any(),any(),any())).thenReturn(new AiCredentialResolver.Resolution(AiCredentialResolver.Outcome.COURSE,UUID.randomUUID(),"test-fingerprint"));
  when(provider.role()).thenReturn(AiProviderRole.PRIMARY); when(provider.providerKey()).thenReturn("primary"); when(provider.providerConfigHash()).thenReturn("provider-config"); when(provider.modelId()).thenReturn("model");
  Project project=new Project(); project.setId(projectId); GitRepo repo=new GitRepo(); repo.setId(repoId);repo.setProject(project);repo.setOwnerLogin("owner");repo.setName("repository");repo.setDefaultBranch("main-latest-must-not-be-used"); GithubInstallation installation=new GithubInstallation();installation.setInstallationId(9001L);repo.setInstallation(installation); commit=new GitCommit();commit.setId(commitId);commit.setRepo(repo);commit.setShaHash(persistedSha);commit.setHeadRef("branch-head-must-not-be-used");
  when(commits.findAnalysisTargetById(commitId)).thenReturn(Optional.of(commit)); when(links.findAnalysisEvidenceByGitCommitId(commitId,projectId)).thenReturn(List.of());
  when(snapshots.build(eq(commit), anyList(), anyList())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.COMMIT_MESSAGE,"commit:"+commitId,"{\"message\":\"persisted\"}",null)));
  SubjectSyllabusVersion syllabus=new SubjectSyllabusVersion();syllabus.setId(syllabusId);syllabus.setVersionLabel("PINNED-2026"); SyllabusPhase first=phase(phaseOneId,"P1",1), second=phase(phaseTwoId,"P2",2); SyllabusExpectedDeliverable firstDeliverable=deliverable(deliverableOneId,phaseOneId,"D1",1), secondDeliverable=deliverable(deliverableTwoId,phaseTwoId,"D2",2);
  when(context.resolve(projectId)).thenReturn(new AiAcademicContextService.Context(project,syllabus,List.of(first,second),List.of(firstDeliverable,secondDeliverable)));
  when(runs.findByIdempotencyKey(anyString())).thenReturn(Optional.empty()); when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(invocation->{AiAnalysisRun run=invocation.getArgument(0);run.setId(UUID.randomUUID());return run;});
 }

 @Test void createsQueuedCanonicalAcademicCommitWithPersistedShaAndAllPinnedCandidatesBeforeHash() {
  List<AiEvidenceDraft> remote=List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS,"github:commit:"+persistedSha,"{\"state\":\"AVAILABLE\"}",null)); when(github.acquire(any())).thenAnswer(invocation->{assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return remote;});
  AtomicReference<List<AiAnalysisEvidence>> savedEvidence=new AtomicReference<>(); doAnswer(invocation->{Iterable<AiAnalysisEvidence> rows=invocation.getArgument(0);savedEvidence.set(StreamSupport.stream(rows.spliterator(),false).toList());return rows;}).when(evidence).saveAll(any());
  AiAnalysisRun run=service().submitCommit(userId,projectId,commitId);
  ArgumentCaptor<AiGitHubCommitEvidenceAcquirer.Target> target=ArgumentCaptor.forClass(AiGitHubCommitEvidenceAcquirer.Target.class);verify(github).acquire(target.capture()); assertThat(target.getValue().sha()).isEqualTo(persistedSha);assertThat(target.getValue().sha()).isNotEqualTo(commit.getHeadRef()).isNotEqualTo(commit.getRepo().getDefaultBranch());
  assertThat(run.getArtifactType()).isEqualTo(AiArtifactType.COMMIT);assertThat(run.getArtifactId()).isEqualTo(commitId);assertThat(run.getArtifactRevision()).isEqualTo(persistedSha);assertThat(run.getAnalysisType()).isEqualTo(AiAnalysisType.ACADEMIC_CLASSIFICATION);assertThat(run.getStatus()).isEqualTo(AiAnalysisStatus.QUEUED);assertThat(run.getPromptVersion()).isEqualTo("academic-classification-v1");assertThat(run.getTaxonomyVersion()).isEqualTo("academic-taxonomy-v1");
  assertThat(savedEvidence.get()).extracting(AiAnalysisEvidence::getSourceRef).contains("syllabus:"+syllabusId,"phase:"+phaseOneId,"phase:"+phaseTwoId,"deliverable:"+deliverableOneId,"deliverable:"+deliverableTwoId);
  List<AiEvidenceDraft> expected=new ArrayList<>(snapshots.build(commit,List.of(),List.of()));expected.addAll(remote);expected.addAll(new AiAcademicCandidateEvidenceBuilder(new ObjectMapper()).build(context.resolve(projectId)));assertThat(run.getEvidenceHash()).isEqualTo(AiAcademicSubmissionService.hashEvidence(expected));
  verify(decisions).save(argThat(decision->decision.getProviderRole()==AiProviderRole.PRIMARY && decision.getRoute()==AiProviderRoute.NORMAL));verify(executor).enqueue(run.getId());verify(provider,never()).analyze(any());verifyNoInteractions(failover);
 }

 @Test void rejectsCommitOwnedByAnotherProjectBeforeGitHubOrPersistence() {
  Project other=new Project();other.setId(UUID.randomUUID());commit.getRepo().setProject(other);
  assertThatThrownBy(()->service().submitCommit(userId,projectId,commitId)).isInstanceOf(IntegrationException.class);
  verifyNoInteractions(github,runs,evidence,decisions,context);verifyNoInteractions(failover);verify(provider,never()).analyze(any());
 }

 @Test void identicalExactEvidenceReturnsTheCanonicalRun() {
  when(github.acquire(any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS,"github:commit:"+persistedSha,"{\"state\":\"AVAILABLE\"}",null)));
  Map<String,AiAnalysisRun> canonical=new HashMap<>(); when(runs.findByIdempotencyKey(anyString())).thenAnswer(invocation->Optional.ofNullable(canonical.get(invocation.getArgument(0)))); when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(invocation->{AiAnalysisRun run=invocation.getArgument(0);run.setId(UUID.randomUUID());canonical.put(run.getIdempotencyKey(),run);return run;});
  AiAnalysisRun first=service().submitCommit(userId,projectId,commitId);AiAnalysisRun second=service().submitCommit(userId,projectId,commitId);
  assertThat(second.getId()).isEqualTo(first.getId());verify(runs,times(1)).saveAndFlush(any());verify(evidence,times(1)).saveAll(any());verify(provider,never()).analyze(any());
 }

 @Test void changedGitHubEvidenceCreatesDifferentCanonicalIdentity() {
  when(github.acquire(any())).thenReturn(List.of(new AiEvidenceDraft(AiEvidenceType.PROVIDER_EVIDENCE_STATUS,"github:commit:"+persistedSha,"{\"state\":\"UNAVAILABLE\",\"codeDiffAvailable\":false}",null)),List.of(new AiEvidenceDraft(AiEvidenceType.DIFF_HUNK,"github:commit:"+persistedSha+":src/A.java:0","@@ -1 +1 @@\n-old\n+new",null)));
  Map<String,AiAnalysisRun> canonical=new HashMap<>();when(runs.findByIdempotencyKey(anyString())).thenAnswer(invocation->Optional.ofNullable(canonical.get(invocation.getArgument(0))));when(runs.saveAndFlush(any(AiAnalysisRun.class))).thenAnswer(invocation->{AiAnalysisRun run=invocation.getArgument(0);run.setId(UUID.randomUUID());canonical.put(run.getIdempotencyKey(),run);return run;});
  AiAnalysisRun unavailable=service().submitCommit(userId,projectId,commitId);AiAnalysisRun available=service().submitCommit(userId,projectId,commitId);
  assertThat(available.getId()).isNotEqualTo(unavailable.getId());assertThat(available.getEvidenceHash()).isNotEqualTo(unavailable.getEvidenceHash());assertThat(available.getIdempotencyKey()).isNotEqualTo(unavailable.getIdempotencyKey());verify(runs,times(2)).saveAndFlush(any());
 }

 @Test void controllerReturnsAcceptedForTheCanonicalQueuedCommitAcademicRun() {
  AiAcademicSubmissionService academic=mock(AiAcademicSubmissionService.class); AiAnalysisSubmissionService intelligence=mock(AiAnalysisSubmissionService.class); AiAnalysisReadService reads=mock(AiAnalysisReadService.class); AiAnalysisRun run=new AiAnalysisRun();run.setId(UUID.randomUUID()); AiAnalysisResponse body=mock(AiAnalysisResponse.class); SagaUserPrincipal principal=new SagaUserPrincipal(userId,"u@example.test","user","User",null,AccountRole.STUDENT,false);
  when(academic.submitCommit(userId,projectId,commitId)).thenReturn(run);when(reads.get(userId,projectId,run.getId())).thenReturn(body);
  var response=new ProjectAiAnalysisController(intelligence,academic,reads,mock(AiTaskIntelligenceSubmissionService.class),mock(AiRiskAnalysisSubmissionService.class),mock(AiProgressNarrativeSubmissionService.class),mock(AiProgressReportExportService.class)).submitCommitAcademic(principal,projectId,commitId);
  assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);assertThat(response.getBody()).isSameAs(body);verify(academic).submitCommit(userId,projectId,commitId);
 }

 private AiAcademicSubmissionService service() { AiConfirmedExampleEvidenceBuilder examples=mock(AiConfirmedExampleEvidenceBuilder.class); when(examples.build(any())).thenReturn(List.of()); return new AiAcademicSubmissionService(auth,mock(TaskRepository.class),commits,links,failover,context,mock(AiTaskAcademicSnapshotBuilder.class),snapshots,new AiAcademicCandidateEvidenceBuilder(new ObjectMapper()),examples,github,runs,evidence,decisions,executor,List.of(provider),transactions,credentialResolver); }
 private static SyllabusPhase phase(UUID id,String code,int order){SyllabusPhase phase=new SyllabusPhase();phase.setId(id);phase.setCode(code);phase.setName(code);phase.setOrderIndex(order);return phase;}
 private static SyllabusExpectedDeliverable deliverable(UUID id,UUID phaseId,String code,int order){SyllabusExpectedDeliverable deliverable=new SyllabusExpectedDeliverable();deliverable.setId(id);deliverable.setPhaseId(phaseId);deliverable.setCode(code);deliverable.setName(code);deliverable.setOrderIndex(order);return deliverable;}
}

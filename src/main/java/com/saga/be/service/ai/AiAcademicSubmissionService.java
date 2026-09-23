package com.saga.be.service.ai;

import com.saga.be.ai.AiModelProvider;
import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.*;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.*;

/**
 * Builds all immutable evidence, including GitHub evidence, before opening the short transaction
 * that creates or finds the canonical run and persists its evidence and provider decision.
 */
@Service @Profile("!test")
public class AiAcademicSubmissionService {
 public static final String POLICY="academic-classification-v1",PROMPT="academic-classification-v1",SCHEMA="academic-schema-v1",TAXONOMY="academic-taxonomy-v1";
 private final ProjectDataAuthorization auth; private final TaskRepository tasks; private final GitCommitRepository commits; private final TaskGitCommitLinkRepository links; private final JiraTaskFailoverItemRepository failover; private final AiAcademicContextService context; private final AiTaskAcademicSnapshotBuilder taskSnapshots; private final AiCommitEvidenceSnapshotBuilder commitSnapshots; private final AiAcademicCandidateEvidenceBuilder candidates; private final AiConfirmedExampleEvidenceBuilder confirmedExamples; private final AiGitHubCommitEvidenceAcquirer githubEvidence; private final AiAnalysisRunRepository runs; private final AiAnalysisEvidenceRepository evidence; private final AiAnalysisProviderDecisionRepository decisions; private final AiAnalysisExecutor executor; private final List<AiModelProvider> providers; private final TransactionTemplate tx;
 public AiAcademicSubmissionService(ProjectDataAuthorization auth, TaskRepository tasks, GitCommitRepository commits, TaskGitCommitLinkRepository links, JiraTaskFailoverItemRepository failover, AiAcademicContextService context, AiTaskAcademicSnapshotBuilder taskSnapshots, AiCommitEvidenceSnapshotBuilder commitSnapshots, AiAcademicCandidateEvidenceBuilder candidates, AiConfirmedExampleEvidenceBuilder confirmedExamples, AiGitHubCommitEvidenceAcquirer githubEvidence, AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions, AiAnalysisExecutor executor, List<AiModelProvider> providers, PlatformTransactionManager manager) { this.auth=auth;this.tasks=tasks;this.commits=commits;this.links=links;this.failover=failover;this.context=context;this.taskSnapshots=taskSnapshots;this.commitSnapshots=commitSnapshots;this.candidates=candidates;this.confirmedExamples=confirmedExamples;this.githubEvidence=githubEvidence;this.runs=runs;this.evidence=evidence;this.decisions=decisions;this.executor=executor;this.providers=providers;tx=new TransactionTemplate(manager); }

 public AiAnalysisRun submitTask(UUID user, UUID projectId, UUID taskId) {
  auth.requireReader(user,projectId);
  Task task=tasks.findActiveFetchedByIdAndProject_Id(taskId,projectId).orElseThrow(()->notFound("Task not found."));
  AiModelProvider provider=primaryProvider(); AiTaskAcademicSnapshotBuilder.Snapshot snapshot=taskSnapshots.build(task); AiAcademicContextService.Context academic=context.resolve(projectId);
  List<AiEvidenceDraft> draft=new ArrayList<>(); draft.add(new AiEvidenceDraft(AiEvidenceType.TASK_FIELD,"task:"+taskId,snapshot.payloadJson(),null)); draft.addAll(candidates.build(academic)); draft.addAll(confirmedExamples.build(academic.syllabusVersion().getId()));
  String evidenceHash=hashEvidence(draft); String key=idempotency(projectId,AiArtifactType.TASK,taskId,snapshot.hash(),evidenceHash,provider);
  return Objects.requireNonNull(tx.execute(status->persist(task.getProject(),AiArtifactType.TASK,taskId,snapshot.hash(),draft,evidenceHash,key,provider)));
 }

 /** GitHub HTTP is deliberately complete before {@link TransactionTemplate#execute}. */
 public AiAnalysisRun submitCommit(UUID user, UUID projectId, UUID gitCommitId) {
  auth.requireReader(user,projectId);
  AiModelProvider provider=primaryProvider();
  GitCommit commit=commits.findAnalysisTargetById(gitCommitId).orElseThrow(()->notFound("Commit was not found."));
  if (!commit.getRepo().getProject().getId().equals(projectId)) throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_PROJECT_MISMATCH,HttpStatus.NOT_FOUND,"Commit does not belong to this project.");
  String sha=commit.getShaHash(); if (sha==null || sha.isBlank()) throw notFound("Commit does not have a persisted SHA.");
  List<TaskGitCommitLink> taskLinks=links.findAnalysisEvidenceByGitCommitId(gitCommitId,projectId); List<UUID> taskIds=taskLinks.stream().map(link->link.getTask().getId()).toList(); List<JiraTaskFailoverItem> lineage=taskIds.isEmpty()?List.of():failover.findSuccessfulLineageByTaskIds(taskIds);
  List<AiEvidenceDraft> draft=new ArrayList<>(commitSnapshots.build(commit,taskLinks,lineage));
  draft.addAll(githubEvidence.acquire(new AiGitHubCommitEvidenceAcquirer.Target(commit.getRepo().getId(),commit.getRepo().getInstallation()==null?null:commit.getRepo().getInstallation().getInstallationId(),commit.getRepo().getOwnerLogin(),commit.getRepo().getName(),sha)));
  AiAcademicContextService.Context academic=context.resolve(projectId); draft.addAll(candidates.build(academic)); draft.addAll(confirmedExamples.build(academic.syllabusVersion().getId()));
  String evidenceHash=hashEvidence(draft); String key=idempotency(projectId,AiArtifactType.COMMIT,gitCommitId,sha,evidenceHash,provider);
  return Objects.requireNonNull(tx.execute(status->persist(commit.getRepo().getProject(),AiArtifactType.COMMIT,gitCommitId,sha,draft,evidenceHash,key,provider)));
 }

 private AiModelProvider primaryProvider() { return providers.stream().filter(p->p.role()==AiProviderRole.PRIMARY).findFirst().orElseThrow(()->new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_PROVIDER_FAILED,HttpStatus.SERVICE_UNAVAILABLE,"AI provider is not configured.")); }
 private IntegrationException notFound(String message) { return new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND,HttpStatus.NOT_FOUND,message); }
 private String idempotency(UUID projectId,AiArtifactType type,UUID artifactId,String revision,String evidenceHash,AiModelProvider provider) { return AiHashes.sha256(String.join("|",projectId.toString(),type.name(),artifactId.toString(),revision,AiAnalysisType.ACADEMIC_CLASSIFICATION.name(),evidenceHash,POLICY,PROMPT,SCHEMA,TAXONOMY,provider.providerConfigHash())); }
 private AiAnalysisRun persist(com.saga.be.entity.project.Project project,AiArtifactType artifactType,UUID artifactId,String revision,List<AiEvidenceDraft> draft,String evidenceHash,String key,AiModelProvider provider) {
  AiAnalysisRun old=runs.findByIdempotencyKey(key).orElse(null); if(old!=null){after(old.getId());return old;}
  AiAnalysisRun run=new AiAnalysisRun(); run.setProject(project);run.setArtifactType(artifactType);run.setArtifactId(artifactId);run.setArtifactRevision(revision);run.setAnalysisType(AiAnalysisType.ACADEMIC_CLASSIFICATION);run.setStatus(AiAnalysisStatus.QUEUED);run.setEvidenceHash(evidenceHash);run.setPolicyVersion(POLICY);run.setPromptVersion(PROMPT);run.setSchemaVersion(SCHEMA);run.setTaxonomyVersion(TAXONOMY);run.setProviderConfigHash(provider.providerConfigHash());run.setIdempotencyKey(key);
  try {run=runs.saveAndFlush(run);} catch(DataIntegrityViolationException ex) { AiAnalysisRun concurrent=runs.findByIdempotencyKey(key).orElseThrow(()->ex);after(concurrent.getId());return concurrent; }
  List<AiAnalysisEvidence> rows=new ArrayList<>();int index=0;for(AiEvidenceDraft item:draft){AiAnalysisEvidence row=new AiAnalysisEvidence();row.setAnalysisRun(run);row.setEvidenceType(item.type());row.setSourceRef(item.sourceRef());row.setContentHash(item.contentHash());row.setPayloadJson(item.payloadJson());row.setMetadataJson(item.metadataJson());row.setOrdinalIndex(index++);rows.add(row);} evidence.saveAll(rows);
  AiAnalysisProviderDecision decision=new AiAnalysisProviderDecision();decision.setAnalysisRun(run);decision.setProviderRole(AiProviderRole.PRIMARY);decision.setProviderKey(provider.providerKey());decision.setProviderConfigHash(provider.providerConfigHash());decision.setModelId(provider.modelId());decision.setRoute(AiProviderRoute.NORMAL);decision.setStatus(AiProviderDecisionStatus.PENDING);decisions.save(decision);after(run.getId());return run;
 }
 static String hashEvidence(List<AiEvidenceDraft> draft) { return AiHashes.sha256(draft.stream().map(row->row.type()+"|"+row.sourceRef()+"|"+row.contentHash()).reduce("",(a,b)->a+"\n"+b)); }
 private void after(UUID id){if(TransactionSynchronizationManager.isSynchronizationActive())TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){@Override public void afterCommit(){executor.enqueue(id);}});else executor.enqueue(id);}
}

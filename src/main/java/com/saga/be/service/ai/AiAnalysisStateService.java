package com.saga.be.service.ai;

import com.saga.be.entity.ai.*;
import com.saga.be.entity.enums.*;
import com.saga.be.repository.*;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class AiAnalysisStateService {
	private final AiAnalysisRunRepository runs;
	private final AiAnalysisEvidenceRepository evidence;
	private final AiAnalysisProviderDecisionRepository decisions;
	public AiAnalysisStateService(AiAnalysisRunRepository runs, AiAnalysisEvidenceRepository evidence, AiAnalysisProviderDecisionRepository decisions) { this.runs = runs; this.evidence = evidence; this.decisions = decisions; }

	@Transactional
	public boolean claim(UUID runId) {
		if (runs.claimQueued(runId, LocalDateTime.now()) != 1) return false;
		if (decisions.startPending(runId) != 1) throw new IllegalStateException("AI analysis claim is missing a pending provider decision");
		return true;
	}
	@Transactional(readOnly = true)
	public ExecutionInput loadExecution(UUID runId) {
		AiAnalysisRun run = runs.findFetchedById(runId).orElseThrow();
		if (run.getStatus() != AiAnalysisStatus.RUNNING) throw new IllegalStateException("AI analysis is no longer runnable");
		return new ExecutionInput(run, evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(runId), decisions.findByAnalysisRun_Id(runId).orElseThrow());
	}
	@Transactional
	public boolean complete(UUID runId, String resultJson, boolean schemaValid, Long latency, Long input, Long output, String modelRevision, String cost) {
		LocalDateTime now = LocalDateTime.now();
		if (runs.completeRunning(runId, now) != 1) return false;
		if (decisions.completeRunning(runId, resultJson, schemaValid, latency, input, output, modelRevision, cost, now) != 1) {
			throw new IllegalStateException("AI analysis completion is missing a running provider decision");
		}
		return true;
	}
	@Transactional
	public boolean fail(UUID runId, String code, boolean schemaValid) {
		LocalDateTime now = LocalDateTime.now();
		if (runs.failActive(runId, code, now) != 1) return false;
		if (decisions.failActive(runId, code, schemaValid, now) != 1) {
			throw new IllegalStateException("AI analysis failure is missing an active provider decision");
		}
		return true;
	}
	@Transactional
	public boolean failQueued(UUID runId, String code, boolean schemaValid) {
		LocalDateTime now = LocalDateTime.now();
		if (runs.failQueued(runId, code, now) != 1) return false;
		if (decisions.failActive(runId, code, schemaValid, now) != 1) {
			throw new IllegalStateException("queued AI analysis failure is missing a pending provider decision");
		}
		return true;
	}
	@Transactional
	public int failStaleRunning(LocalDateTime cutoff) {
		List<UUID> stale = runs.findStaleRunningIds(cutoff);
		int recovered = 0;
		for (UUID id : stale) {
			LocalDateTime now = LocalDateTime.now();
			if (runs.recoverStaleRunning(id, cutoff, "AI_RUNNING_STALE_RECOVERED", now) == 1) {
				if (decisions.failActive(id, "AI_RUNNING_STALE_RECOVERED", false, now) != 1) {
					throw new IllegalStateException("stale AI analysis is missing an active provider decision");
				}
				recovered++;
			}
		}
		return recovered;
	}
	@Transactional(readOnly = true)
	public List<UUID> queuedIds(int batchSize) { return runs.findIdsByStatus(AiAnalysisStatus.QUEUED, org.springframework.data.domain.PageRequest.of(0, batchSize)); }

	@Transactional
	public void recordPrimaryProvenance(UUID runId, AiProvider aiProvider, String modelId, UUID credentialId, String fingerprint, String attemptsJson) {
		decisions.recordPrimaryProvenance(runId, aiProvider, modelId, credentialId, fingerprint, attemptsJson);
	}

	public record ExecutionInput(AiAnalysisRun run, List<AiAnalysisEvidence> evidence, AiAnalysisProviderDecision decision) {}
}

package com.saga.be.repository;

import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAnalysisProviderDecisionRepository extends JpaRepository<AiAnalysisProviderDecision, UUID> {
	/**
	 * PRIMARY-only by design: every existing caller (state machine, all finalizers, reads) only
	 * ever wrote/expects PRIMARY rows, and this stays {@code Optional} (exactly one match). When
	 * the secondary brain is enabled a SECONDARY row can coexist on the same run; it is reached
	 * exclusively through the role-scoped methods below, never through this one.
	 */
	@Query("select d from AiAnalysisProviderDecision d where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY")
	Optional<AiAnalysisProviderDecision> findByAnalysisRun_Id(@Param("runId") UUID runId);

	@Query("select d from AiAnalysisProviderDecision d where d.analysisRun.id in :runIds and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY")
	List<AiAnalysisProviderDecision> findByAnalysisRun_IdIn(@Param("runIds") Collection<UUID> runIds);

	Optional<AiAnalysisProviderDecision> findByAnalysisRun_IdAndProviderRole(UUID runId, com.saga.be.entity.enums.AiProviderRole providerRole);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING")
	int startPending(@Param("runId") UUID runId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.structuredResultJson = :resultJson, d.schemaValid = :schemaValid, d.latencyMs = :latency, d.inputUnits = :input, d.outputUnits = :output, d.modelRevision = :modelRevision, d.costMetadataJson = :cost, d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.COMPLETED, d.completedAt = :now, d.safeErrorCode = null where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING")
	int completeRunning(@Param("runId") UUID runId, @Param("resultJson") String resultJson, @Param("schemaValid") boolean schemaValid, @Param("latency") Long latency, @Param("input") Long input, @Param("output") Long output, @Param("modelRevision") String modelRevision, @Param("cost") String cost, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.FAILED, d.schemaValid = :schemaValid, d.safeErrorCode = :code, d.completedAt = :now where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY and d.status in (com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING, com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING)")
	int failActive(@Param("runId") UUID runId, @Param("code") String code, @Param("schemaValid") boolean schemaValid, @Param("now") LocalDateTime now);

	/** Course multi-provider provenance for the PRIMARY row: which binding/credential actually
	 * served (or last failed) the run, plus the bounded, secret-free attempt list. */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.aiProvider = :aiProvider, d.modelId = :modelId, d.courseCredentialId = :credentialId, d.credentialFingerprint = :fingerprint, d.fallbackAttemptsJson = :attempts where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.PRIMARY")
	int recordPrimaryProvenance(@Param("runId") UUID runId, @Param("aiProvider") com.saga.be.entity.enums.AiProvider aiProvider, @Param("modelId") String modelId, @Param("credentialId") UUID credentialId, @Param("fingerprint") String fingerprint, @Param("attempts") String attempts);

	// --- Secondary brain: independent lifecycle, scoped by (run, SECONDARY role). Never touches
	// the run's own status/started_at/completed_at, and never touches the PRIMARY row above. ---

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING, d.startedAt = :now where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.SECONDARY and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING")
	int claimSecondaryPending(@Param("runId") UUID runId, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.structuredResultJson = :resultJson, d.schemaValid = :schemaValid, d.latencyMs = :latency, d.inputUnits = :input, d.outputUnits = :output, d.modelRevision = :modelRevision, d.costMetadataJson = :cost, d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.COMPLETED, d.completedAt = :now, d.safeErrorCode = null where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.SECONDARY and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING")
	int completeSecondaryRunning(@Param("runId") UUID runId, @Param("resultJson") String resultJson, @Param("schemaValid") boolean schemaValid, @Param("latency") Long latency, @Param("input") Long input, @Param("output") Long output, @Param("modelRevision") String modelRevision, @Param("cost") String cost, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.FAILED, d.schemaValid = :schemaValid, d.safeErrorCode = :code, d.completedAt = :now where d.analysisRun.id = :runId and d.providerRole = com.saga.be.entity.enums.AiProviderRole.SECONDARY and d.status in (com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING, com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING)")
	int failSecondaryActive(@Param("runId") UUID runId, @Param("code") String code, @Param("schemaValid") boolean schemaValid, @Param("now") LocalDateTime now);
}

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
	Optional<AiAnalysisProviderDecision> findByAnalysisRun_Id(UUID runId);
	@Query("select d from AiAnalysisProviderDecision d where d.analysisRun.id in :runIds")
	List<AiAnalysisProviderDecision> findByAnalysisRun_IdIn(@Param("runIds") Collection<UUID> runIds);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING where d.analysisRun.id = :runId and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING")
	int startPending(@Param("runId") UUID runId);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.structuredResultJson = :resultJson, d.schemaValid = :schemaValid, d.latencyMs = :latency, d.inputUnits = :input, d.outputUnits = :output, d.modelRevision = :modelRevision, d.costMetadataJson = :cost, d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.COMPLETED, d.completedAt = :now, d.safeErrorCode = null where d.analysisRun.id = :runId and d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING")
	int completeRunning(@Param("runId") UUID runId, @Param("resultJson") String resultJson, @Param("schemaValid") boolean schemaValid, @Param("latency") Long latency, @Param("input") Long input, @Param("output") Long output, @Param("modelRevision") String modelRevision, @Param("cost") String cost, @Param("now") LocalDateTime now);

	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AiAnalysisProviderDecision d set d.status = com.saga.be.entity.enums.AiProviderDecisionStatus.FAILED, d.schemaValid = :schemaValid, d.safeErrorCode = :code, d.completedAt = :now where d.analysisRun.id = :runId and d.status in (com.saga.be.entity.enums.AiProviderDecisionStatus.PENDING, com.saga.be.entity.enums.AiProviderDecisionStatus.RUNNING)")
	int failActive(@Param("runId") UUID runId, @Param("code") String code, @Param("schemaValid") boolean schemaValid, @Param("now") LocalDateTime now);
}

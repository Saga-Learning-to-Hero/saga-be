package com.saga.be.repository;

import com.saga.be.entity.ai.AiAnalysisEvidence;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiAnalysisEvidenceRepository extends JpaRepository<AiAnalysisEvidence, UUID> {
	List<AiAnalysisEvidence> findByAnalysisRun_IdOrderByOrdinalIndexAsc(UUID runId);
	@Query("select e from AiAnalysisEvidence e where e.analysisRun.id in :runIds order by e.analysisRun.id, e.ordinalIndex")
	List<AiAnalysisEvidence> findByAnalysisRun_IdInOrderByAnalysisRun_IdAscOrdinalIndexAsc(@Param("runIds") Collection<UUID> runIds);
}

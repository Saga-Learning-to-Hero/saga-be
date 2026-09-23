package com.saga.be.repository;

import com.saga.be.entity.ai.AiAnalysisAdjudication;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiAnalysisAdjudicationRepository extends JpaRepository<AiAnalysisAdjudication, UUID> {
	Optional<AiAnalysisAdjudication> findByAnalysisRun_Id(UUID analysisRunId);
}

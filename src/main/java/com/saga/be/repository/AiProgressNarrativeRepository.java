package com.saga.be.repository;

import com.saga.be.entity.ai.AiProgressNarrative;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AiProgressNarrativeRepository extends JpaRepository<AiProgressNarrative, UUID> {
	Optional<AiProgressNarrative> findByAnalysisRun_Id(UUID analysisRunId);
}

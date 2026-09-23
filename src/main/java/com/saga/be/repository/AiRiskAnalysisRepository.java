package com.saga.be.repository;

import com.saga.be.entity.ai.AiRiskAnalysis;
import com.saga.be.entity.enums.AiArtifactType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiRiskAnalysisRepository extends JpaRepository<AiRiskAnalysis, UUID> {
	Optional<AiRiskAnalysis> findByAnalysisRun_Id(UUID analysisRunId);
	Optional<AiRiskAnalysis> findTopByAnalysisRun_ArtifactTypeAndAnalysisRun_ArtifactIdOrderByCreatedAtDesc(AiArtifactType artifactType, UUID artifactId);

	/** Latest risk row per task, bounded to the supplied task IDs (progress facts aggregation). */
	@Query("""
			select r from AiRiskAnalysis r
			where r.analysisRun.artifactType = com.saga.be.entity.enums.AiArtifactType.TASK
			  and r.analysisRun.artifactId in :taskIds
			order by r.createdAt desc
			""")
	List<AiRiskAnalysis> findLatestByTaskIds(@Param("taskIds") List<UUID> taskIds);
}

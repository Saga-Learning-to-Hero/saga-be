package com.saga.be.repository;

import com.saga.be.entity.ai.AiTaskIntelligence;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiTaskIntelligenceRepository extends JpaRepository<AiTaskIntelligence, UUID> {
	Optional<AiTaskIntelligence> findByAnalysisRun_Id(UUID analysisRunId);
	Optional<AiTaskIntelligence> findTopByTask_IdOrderByCreatedAtDesc(UUID taskId);

	/** Bulk, one query: latest row per task, bounded to the supplied task IDs (no N+1 loop). */
	@Query("select t from AiTaskIntelligence t where t.task.id in :taskIds order by t.createdAt desc")
	List<AiTaskIntelligence> findLatestByTaskIds(@Param("taskIds") List<UUID> taskIds);
}

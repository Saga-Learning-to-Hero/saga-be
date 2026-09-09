package com.saga.be.repository;

import com.saga.be.entity.assessment.ProjectGroupWeightConfig;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectGroupWeightConfigRepository extends JpaRepository<ProjectGroupWeightConfig, UUID> {

	Optional<ProjectGroupWeightConfig> findByProject_Id(UUID projectId);

	@Query(
			"""
			SELECT c FROM ProjectGroupWeightConfig c
			JOIN FETCH c.project
			JOIN FETCH c.team
			WHERE c.project.id = :projectId
			""")
	Optional<ProjectGroupWeightConfig> findFetchedByProject_Id(@Param("projectId") UUID projectId);

	List<ProjectGroupWeightConfig> findByTeam_Course_Id(UUID courseId);
}

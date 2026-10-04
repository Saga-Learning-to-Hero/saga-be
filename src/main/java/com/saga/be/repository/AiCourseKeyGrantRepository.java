package com.saga.be.repository;

import com.saga.be.entity.ai.AiCourseKeyGrant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AiCourseKeyGrantRepository extends JpaRepository<AiCourseKeyGrant, UUID> {
	boolean existsByProject_Id(UUID projectId);

	Optional<AiCourseKeyGrant> findByProject_Id(UUID projectId);

	@Query("select g.project.id from AiCourseKeyGrant g where g.project.id in :projectIds")
	List<UUID> findGrantedProjectIds(@Param("projectIds") Collection<UUID> projectIds);
}

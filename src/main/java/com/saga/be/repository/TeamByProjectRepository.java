package com.saga.be.repository;

import com.saga.be.entity.project.Team;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TeamByProjectRepository extends JpaRepository<Team, UUID> {

	@Query(
			"""
			SELECT t FROM Team t
			JOIN FETCH t.course
			WHERE t.project.id = :projectId
			""")
	Optional<Team> findByProject_Id(@Param("projectId") UUID projectId);
}

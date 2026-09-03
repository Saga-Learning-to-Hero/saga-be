package com.saga.be.repository;

import com.saga.be.entity.project.Project;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, UUID> {

	boolean existsByCourse_Id(UUID courseId);

	@Query(
			"""
			SELECT p FROM Project p
			JOIN FETCH p.course c
			JOIN FETCH c.academicClass
			WHERE p.id = :id
			""")
	Optional<Project> findFetchedById(@Param("id") UUID id);
}

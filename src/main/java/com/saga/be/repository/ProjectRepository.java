package com.saga.be.repository;

import com.saga.be.entity.project.Project;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

	/**
	 * Serializes native hierarchy mutations for one Project (assign/change/clear parent, delete
	 * child-check). Hold only for the short JDBC validation/write; never across Jira HTTP.
	 */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from Project p where p.id = :id")
	Optional<Project> lockById(@Param("id") UUID id);

	@Query(
			"""
			SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END
			FROM Project p
			JOIN p.course c
			JOIN c.instructor i
			JOIN i.userAccount u
			WHERE p.id = :projectId AND u.id = :lecturerUserId
			""")
	boolean existsAssignedToLecturerUser(
			@Param("projectId") UUID projectId, @Param("lecturerUserId") UUID lecturerUserId);
}

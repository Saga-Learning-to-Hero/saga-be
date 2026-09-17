package com.saga.be.repository;

import com.saga.be.entity.assessment.PeerReview;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeerReviewRepository extends JpaRepository<PeerReview, UUID> {

	@Query(
			"""
			SELECT pr FROM PeerReview pr
			JOIN FETCH pr.sprint s
			JOIN FETCH s.jiraIntegration j
			WHERE j.project.id = :projectId
			AND pr.revieweeStudent.id IN :studentIds
			""")
	List<PeerReview> findFetchedByProjectAndReviewees(
			@Param("projectId") UUID projectId, @Param("studentIds") Collection<UUID> studentIds);

	Optional<PeerReview> findBySprint_IdAndReviewerStudent_IdAndRevieweeStudent_Id(
			UUID sprintId, UUID reviewerStudentId, UUID revieweeStudentId);

	List<PeerReview> findBySprint_IdAndReviewerStudent_Id(UUID sprintId, UUID reviewerStudentId);

	@Query(
			"""
			SELECT pr FROM PeerReview pr
			JOIN FETCH pr.sprint s
			JOIN FETCH s.jiraIntegration j
			JOIN FETCH pr.reviewerStudent rs
			JOIN FETCH rs.userAccount
			JOIN FETCH pr.revieweeStudent es
			JOIN FETCH es.userAccount
			WHERE j.project.id = :projectId
			  AND s.id = :sprintId
			  AND s.deletedAt IS NULL
			""")
	List<PeerReview> findFetchedByProjectAndSprint(
			@Param("projectId") UUID projectId, @Param("sprintId") UUID sprintId);

	/** Heatmap: {@code Object[]{UUID reviewerStudentId, LocalDateTime createdAt}} for submitted reviews. */
	@Query(
			"""
			select pr.reviewerStudent.id, pr.createdAt
			from PeerReview pr
			join pr.sprint s
			join s.jiraIntegration j
			where j.project.id = :projectId
			  and pr.starRating is not null
			""")
	List<Object[]> findReviewerAndCreatedAtByProject(@Param("projectId") UUID projectId);
}

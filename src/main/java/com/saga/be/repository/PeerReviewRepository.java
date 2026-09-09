package com.saga.be.repository;

import com.saga.be.entity.assessment.PeerReview;
import java.util.Collection;
import java.util.List;
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
}

package com.saga.be.repository;

import com.saga.be.entity.assessment.PeerReviewDetail;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PeerReviewDetailRepository extends JpaRepository<PeerReviewDetail, UUID> {

	List<PeerReviewDetail> findByPeerReview_IdOrderByCriteriaOrderAsc(UUID peerReviewId);

	@Query(
			"""
			select d from PeerReviewDetail d
			join fetch d.peerReview
			join fetch d.rubric
			where d.peerReview.id in :reviewIds
			order by d.peerReview.id, d.criteriaOrder
			""")
	List<PeerReviewDetail> findFetchedByPeerReview_IdIn(@Param("reviewIds") Collection<UUID> reviewIds);
}

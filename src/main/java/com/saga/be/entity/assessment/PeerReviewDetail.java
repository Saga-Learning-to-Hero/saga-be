package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.assessment.RubricTemplate;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "peer_review_detail",
	indexes = {
		@Index(name = "ix_peer_review_detail_review", columnList = "peer_review_id")
	}
)
public class PeerReviewDetail extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "peer_review_id", nullable = false)
	private PeerReview peerReview;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "rubric_id", nullable = false)
	private RubricTemplate rubric;

	@Column(name = "criteria_name", length = 255, nullable = false)
	private String criteriaName;

	@Column(name = "criteria_order", nullable = false)
	private Integer criteriaOrder;

	@Column(name = "star_rating", nullable = false)
	private Integer starRating;
}

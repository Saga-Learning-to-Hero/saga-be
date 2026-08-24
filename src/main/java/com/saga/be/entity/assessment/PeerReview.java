package com.saga.be.entity.assessment;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.jira.Sprint;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "peer_review",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_peer_review_sprint_pair", columnNames = {"sprint_id", "reviewer_student_id", "reviewee_student_id"})
	}
)
public class PeerReview extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sprint_id", nullable = false)
	private Sprint sprint;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reviewer_student_id", nullable = false)
	private StudentProfile reviewerStudent;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "reviewee_student_id", nullable = false)
	private StudentProfile revieweeStudent;

	@Column(name = "star_rating")
	private Integer starRating;

	@Column(name = "comment", columnDefinition = "TEXT")
	private String comment;
}

package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.PrReviewStatus;
import com.saga.be.entity.github.PullRequest;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "pr_review",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_pr_review_github_id", columnNames = {"pull_request_id", "github_review_id"})
	}
)
public class PrReview extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pull_request_id", nullable = false)
	private PullRequest pullRequest;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "reviewer_student_id", nullable = true)
	private StudentProfile reviewerStudent;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32)
	private PrReviewStatus status;

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Column(name = "github_review_id")
	private Long githubReviewId;

	@Column(name = "reviewer_external_id", length = 128)
	private String reviewerExternalId;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;
}

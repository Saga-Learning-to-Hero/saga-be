package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.PullRequestStatus;
import com.saga.be.entity.github.GitRepo;
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
	name = "pull_request",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_pr_github_id", columnNames = {"repo_id", "github_pull_request_id"}),
		@UniqueConstraint(name = "uk_pr_number", columnNames = {"repo_id", "pull_number"})
	}
)
public class PullRequest extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "repo_id", nullable = false)
	private GitRepo repo;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "author_student_id", nullable = true)
	private StudentProfile authorStudent;

	@Column(name = "author_external_id", length = 128)
	private String authorExternalId;

	@Column(name = "title", length = 500)
	private String title;

	@Column(name = "github_pull_request_id")
	private Long githubPullRequestId;

	@Column(name = "node_id", length = 128)
	private String nodeId;

	@Column(name = "pull_number")
	private Integer pullNumber;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32)
	private PullRequestStatus status;

	@Column(name = "merged_at")
	private LocalDateTime mergedAt;

	@Column(name = "review_count")
	private Integer reviewCount;

	@Column(name = "comment_count")
	private Integer commentCount;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;
}

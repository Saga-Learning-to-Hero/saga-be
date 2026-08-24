package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.CommitReviewIntentStatus;
import com.saga.be.entity.enums.CommitReviewMode;
import com.saga.be.entity.enums.CommitReviewPriority;
import com.saga.be.entity.github.GitCommit;
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
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "commit_review_intent",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_commit_review_intent_repo_sha", columnNames = {"git_repo_id", "sha_hash"})
	}
)
public class CommitReviewIntent extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_repo_id", nullable = false)
	private GitRepo gitRepo;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_commit_id", nullable = false)
	private GitCommit gitCommit;

	@Column(name = "sha_hash", length = 64, nullable = false)
	private String shaHash;

	@Enumerated(EnumType.STRING)
	@Column(name = "review_mode", length = 32, nullable = false)
	private CommitReviewMode reviewMode;

	@Enumerated(EnumType.STRING)
	@Column(name = "priority", length = 16, nullable = false)
	private CommitReviewPriority priority;

	@Column(name = "priority_rank", nullable = false)
	private Integer priorityRank;

	@Enumerated(EnumType.STRING)
	@Column(name = "intent_status", length = 32, nullable = false)
	private CommitReviewIntentStatus intentStatus;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "ai_job_id", columnDefinition = "char(36)")
	private UUID aiJobId;

	@Column(name = "review_policy_version", length = 64)
	private String reviewPolicyVersion;

	@Column(name = "last_job_status", length = 32)
	private String lastJobStatus;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "safe_error_code", length = 64)
	private String safeErrorCode;
}

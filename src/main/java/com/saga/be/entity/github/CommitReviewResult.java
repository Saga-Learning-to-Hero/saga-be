package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.CommitReviewMode;
import com.saga.be.entity.github.CommitReviewIntent;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
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
	name = "commit_review_result",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_commit_review_result_intent", columnNames = {"intent_id"}),
		@UniqueConstraint(name = "uk_commit_review_result_job", columnNames = {"ai_job_id"})
	}
)
public class CommitReviewResult extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "intent_id", nullable = false)
	private CommitReviewIntent intent;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "ai_job_id", columnDefinition = "char(36)", nullable = false)
	private UUID aiJobId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_repo_id", nullable = false)
	private GitRepo gitRepo;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_commit_id", nullable = false)
	private GitCommit gitCommit;

	@Column(name = "sha_hash", length = 64, nullable = false)
	private String shaHash;

	@Column(name = "policy_version", length = 64, nullable = false)
	private String policyVersion;

	@Enumerated(EnumType.STRING)
	@Column(name = "review_mode", length = 32, nullable = false)
	private CommitReviewMode reviewMode;

	@Column(name = "traceability_status", length = 32, nullable = false)
	private String traceabilityStatus;

	@Column(name = "message_quality", length = 16, nullable = false)
	private String messageQuality;

	@Column(name = "code_quality", length = 32, nullable = false)
	private String codeQuality;

	@Column(name = "inferred_function_label", length = 32)
	private String inferredFunctionLabel;

	@Column(name = "inferred_function_confidence", length = 16)
	private String inferredFunctionConfidence;

	@Column(name = "task_alignment", length = 32, nullable = false)
	private String taskAlignment;

	@Column(name = "verdict_eligible", nullable = false)
	private Boolean verdictEligible;

	@Column(name = "verdict", length = 32, nullable = false)
	private String verdict;

	@Column(name = "overall_status", length = 32, nullable = false)
	private String overallStatus;

	@Column(name = "schema_version", length = 64, nullable = false)
	private String schemaVersion;

	@Column(name = "findings_json", columnDefinition = "MEDIUMTEXT")
	private String findingsJson;

	@Column(name = "evidence_refs_json", columnDefinition = "MEDIUMTEXT")
	private String evidenceRefsJson;

	@Column(name = "completed_at", nullable = false)
	private LocalDateTime completedAt;
}

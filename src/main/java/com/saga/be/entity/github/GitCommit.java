package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.github.GitRepo;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
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
	name = "git_commit",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_commit_repo_sha", columnNames = {"repo_id", "sha_hash"})
	},
	indexes = {
		@Index(name = "ix_git_commit_sha", columnList = "sha_hash")
	}
)
public class GitCommit extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "repo_id", nullable = false)
	private GitRepo repo;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "author_student_id", nullable = true)
	private StudentProfile authorStudent;

	@Column(name = "sha_hash", length = 64, nullable = false)
	private String shaHash;

	@Column(name = "github_commit_id", length = 64)
	private String githubCommitId;

	@Column(name = "author_external_id", length = 128)
	private String authorExternalId;

	@Column(name = "message", columnDefinition = "MEDIUMTEXT")
	private String message;

	@Column(name = "committed_at")
	private LocalDateTime committedAt;

	@Column(name = "additions")
	private Integer additions;

	@Column(name = "deletions")
	private Integer deletions;

	@Column(name = "files_changed")
	private Integer filesChanged;

	@Column(name = "signature_verified")
	private Boolean signatureVerified;

	@Column(name = "verification_reason", length = 64)
	private String verificationReason;

	@Column(name = "head_ref", length = 255)
	private String headRef;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;
}

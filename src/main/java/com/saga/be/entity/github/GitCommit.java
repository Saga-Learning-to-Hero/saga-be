package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

	/** GitHub login (e.g. "trungne08"); author_external_id may be the numeric GitHub user id. */
	@Column(name = "author_login", length = 128)
	private String authorLogin;

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

	/** Null = UNKNOWN. 0 = root, 1 = normal, &gt;1 = merge. Never inferred from message. */
	@Column(name = "parent_count")
	private Integer parentCount;

	@Transient
	public Boolean isMerge() {
		return parentCount == null ? null : parentCount > 1;
	}

	private static final java.util.regex.Pattern GIT_MERGE_MESSAGE = java.util.regex.Pattern.compile(
			"^(Merge pull request #\\d+|Merge branch '|Merge remote-tracking branch '|Merge tag '|Merge commit ')");

	/**
	 * For AI review only (never for {@link #isMerge()}, which stays parent-count based): a commit with
	 * two parents, or, while the parent count is still unknown (a push webhook does not carry it), one
	 * whose message is Git's/GitHub's own merge message. A merge only joins existing work.
	 */
	@Transient
	public boolean looksLikeMerge() {
		if (parentCount != null) return parentCount > 1;
		return message != null && GIT_MERGE_MESSAGE.matcher(message.strip()).find();
	}
}

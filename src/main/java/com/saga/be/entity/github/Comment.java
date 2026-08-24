package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.TargetType;
import com.saga.be.entity.github.GitIssue;
import com.saga.be.entity.github.PullRequest;
import com.saga.be.entity.jira.Task;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "comment",
	indexes = {
		@Index(name = "ix_comment_issue", columnList = "git_issue_id"),
		@Index(name = "ix_comment_pr", columnList = "pull_request_id"),
		@Index(name = "ix_comment_task", columnList = "task_id")
	}
)
public class Comment extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "author_student_id", nullable = true)
	private StudentProfile authorStudent;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "git_issue_id", nullable = true)
	private GitIssue gitIssue;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "pull_request_id", nullable = true)
	private PullRequest pullRequest;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "parent_comment_id", nullable = true)
	private Comment parentComment;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "task_id", nullable = true)
	private Task task;

	@Column(name = "body", columnDefinition = "TEXT")
	private String body;

	@Column(name = "source_system", length = 32)
	private String sourceSystem;

	@Column(name = "external_comment_id", length = 128)
	private String externalCommentId;

	@Column(name = "author_external_id", length = 128)
	private String authorExternalId;

	@Enumerated(EnumType.STRING)
	@Column(name = "target_type", length = 32)
	private TargetType targetType;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;
}

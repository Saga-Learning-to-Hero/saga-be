package com.saga.be.entity.github;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.IssueState;
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
	name = "git_issue",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_issue_github_id", columnNames = {"repo_id", "github_issue_id"}),
		@UniqueConstraint(name = "uk_git_issue_number", columnNames = {"repo_id", "issue_number"})
	}
)
public class GitIssue extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "repo_id", nullable = false)
	private GitRepo repo;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "author_student_id", nullable = true)
	private StudentProfile authorStudent;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "assignee_student_id", nullable = true)
	private StudentProfile assigneeStudent;

	@Column(name = "author_external_id", length = 128)
	private String authorExternalId;

	@Column(name = "assignee_external_id", length = 128)
	private String assigneeExternalId;

	@Column(name = "issue_number")
	private Integer issueNumber;

	@Column(name = "github_issue_id")
	private Long githubIssueId;

	@Column(name = "node_id", length = 128)
	private String nodeId;

	@Column(name = "title", length = 500)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "state", length = 32)
	private IssueState state;

	@Column(name = "closed_at")
	private LocalDateTime closedAt;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;
}

package com.saga.be.entity.traceability;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.TraceabilityRelationType;
import com.saga.be.entity.github.GitIssue;
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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "git_issue_pull_request_link",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_issue_pr_link_pair", columnNames = {"git_issue_id", "pull_request_id"})
	}
)
public class GitIssuePullRequestLink extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_issue_id", nullable = false)
	private GitIssue gitIssue;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "pull_request_id", nullable = false)
	private PullRequest pullRequest;

	@Enumerated(EnumType.STRING)
	@Column(name = "relation_type", length = 32, nullable = false)
	private TraceabilityRelationType relationType;
}

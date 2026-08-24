package com.saga.be.entity.traceability;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.TraceabilityRelationType;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitIssue;
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
	name = "git_issue_commit_link",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_git_issue_commit_link_pair", columnNames = {"git_issue_id", "git_commit_id"})
	}
)
public class GitIssueCommitLink extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_issue_id", nullable = false)
	private GitIssue gitIssue;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_commit_id", nullable = false)
	private GitCommit gitCommit;

	@Enumerated(EnumType.STRING)
	@Column(name = "relation_type", length = 32, nullable = false)
	private TraceabilityRelationType relationType;
}

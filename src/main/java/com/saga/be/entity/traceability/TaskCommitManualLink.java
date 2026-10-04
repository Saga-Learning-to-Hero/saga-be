package com.saga.be.entity.traceability;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.account.UserAccount;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A commit attached to a task by hand (the commit message forgot the Jira key). Display and AI
 * review context only: deliberately NOT a {@link TaskGitCommitLink}, so no contribution, evidence
 * or dashboard query can ever count it.
 */
@Getter @Setter @NoArgsConstructor @Entity
@Table(
	name = "task_commit_manual_link",
	uniqueConstraints = @UniqueConstraint(name = "uk_task_commit_manual_link", columnNames = {"task_id", "git_commit_id"}),
	indexes = @Index(name = "ix_task_commit_manual_link_commit", columnList = "git_commit_id")
)
public class TaskCommitManualLink extends BaseEntity {
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "project_id", nullable = false) private Project project;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "task_id", nullable = false) private Task task;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "git_commit_id", nullable = false) private GitCommit gitCommit;
	@ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "created_by_user_id", nullable = false) private UserAccount createdBy;
}

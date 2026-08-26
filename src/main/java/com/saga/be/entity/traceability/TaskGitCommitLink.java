package com.saga.be.entity.traceability;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
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
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "task_git_commit_link",
	uniqueConstraints = {@UniqueConstraint(name = "uk_task_git_commit_link", columnNames = {"task_id", "git_commit_id"})},
	indexes = {@Index(name = "ix_task_commit_commit", columnList = "git_commit_id")}
)
public class TaskGitCommitLink extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "git_commit_id", nullable = false)
	private GitCommit gitCommit;

	@Enumerated(EnumType.STRING)
	@Column(name = "link_source", length = 32, nullable = false)
	private TraceLinkSource linkSource;

	@Column(name = "jira_key_snapshot", length = 64)
	private String jiraKeySnapshot;

	@Column(name = "confidence", length = 16)
	private String confidence;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "metadata_json", columnDefinition = "json")
	private String metadataJson;
}

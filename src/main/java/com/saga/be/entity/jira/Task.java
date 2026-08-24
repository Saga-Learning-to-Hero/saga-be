package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
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
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "task",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_task_project_external_id", columnNames = {"project_id", "external_id"})
	},
	indexes = {
		@Index(name = "ix_task_project_sprint", columnList = "project_id, sprint_id"),
		@Index(name = "ix_task_assignee", columnList = "assignee_student_id"),
		@Index(name = "ix_task_due_date", columnList = "due_date"),
		@Index(name = "ix_task_external_key", columnList = "external_key")
	}
)
public class Task extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "sprint_id", nullable = true)
	private Sprint sprint;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "assignee_student_id", nullable = true)
	private StudentProfile assigneeStudent;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "reporter_student_id", nullable = true)
	private StudentProfile reporterStudent;

	@Column(name = "assignee_external_id", length = 128)
	private String assigneeExternalId;

	@Column(name = "reporter_external_id", length = 128)
	private String reporterExternalId;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "blocks_task_id", nullable = true)
	private Task blocksTask;

	@Column(name = "external_key", length = 64)
	private String externalKey;

	@Column(name = "external_id", length = 64)
	private String externalId;

	@Column(name = "title", length = 500)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "task_type", length = 32)
	private TaskType taskType;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32)
	private TaskStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "priority", length = 32)
	private Priority priority;

	@Column(name = "story_point")
	private Integer storyPoint;

	@Column(name = "due_date")
	private LocalDateTime dueDate;

	@Column(name = "external_updated_at")
	private LocalDateTime externalUpdatedAt;

	@Column(name = "resolved_at")
	private LocalDateTime resolvedAt;

	@Column(name = "resolution", length = 128)
	private String resolution;

	@Column(name = "description", columnDefinition = "TEXT")
	private String description;

	@Column(name = "labels_json", columnDefinition = "TEXT")
	private String labelsJson;

	@Column(name = "components_json", columnDefinition = "TEXT")
	private String componentsJson;

	@Column(name = "deleted_at")
	private LocalDateTime deletedAt;
}

package com.saga.be.entity.delay;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.DelayCaseEnums.TaskChangeField;
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

/**
 * A change to a task's due date, story points or assignee seen by Jira sync. Recorded from V38 on;
 * used to check delay explanations (schedule moved, scope grew, reassigned close to the deadline).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "task_change_log", indexes = @Index(name = "ix_task_change_log_task", columnList = "task_id, field"))
public class TaskChangeLog extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	@Enumerated(EnumType.STRING)
	@Column(name = "field", length = 16, nullable = false)
	private TaskChangeField field;

	@Column(name = "old_value", length = 128)
	private String oldValue;

	@Column(name = "new_value", length = 128)
	private String newValue;

	@Column(name = "changed_at", nullable = false)
	private LocalDateTime changedAt;
}

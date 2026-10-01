package com.saga.be.entity.delay;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.enums.DelayCaseEnums.CloseReason;
import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Outcome;
import com.saga.be.entity.enums.DelayCaseEnums.Verification;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.DelayCauseCategory;
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
import jakarta.persistence.Version;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;

/**
 * One missed deadline of one task: the system's evidence, the assignee's explanation, the team
 * leader's confirmation and the lecturer's decision. A task whose due date moves and is missed
 * again gets a second case (one per task and due date).
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
		name = "task_delay_case",
		uniqueConstraints = @UniqueConstraint(name = "uk_task_delay_case_task_due", columnNames = {"task_id", "due_date"}),
		indexes = {
			@Index(name = "ix_task_delay_case_project_status", columnList = "project_id, status"),
			@Index(name = "ix_task_delay_case_status_due", columnList = "status, explanation_due_at")
		})
public class TaskDelayCase extends BaseEntity {

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "project_id", nullable = false)
	private Project project;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "task_id", nullable = false)
	private Task task;

	/** The assignee when the deadline was missed: the person who explains. */
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "student_profile_id", nullable = false)
	private StudentProfile studentProfile;

	/** The missed due date (as stored on the task). */
	@Column(name = "due_date", nullable = false)
	private LocalDateTime dueDate;

	@Column(name = "opened_at", nullable = false)
	private LocalDateTime openedAt;

	@Column(name = "explanation_due_at", nullable = false)
	private LocalDateTime explanationDueAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 24, nullable = false)
	private DelayCaseStatus status;

	@Enumerated(EnumType.STRING)
	@Column(name = "category", length = 32)
	private DelayCauseCategory category;

	@Column(name = "explanation_note", length = 1000)
	private String explanationNote;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "blocking_task_id")
	private Task blockingTask;

	@Column(name = "evidence_url", length = 2048)
	private String evidenceUrl;

	@Column(name = "explained_at")
	private LocalDateTime explainedAt;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "explained_by_user_id", columnDefinition = "char(36)")
	private UUID explainedByUserId;

	/** System evidence (JSON of {@code DelaySignals}), captured at opening and refreshed on explanation. */
	@Column(name = "signals_json", columnDefinition = "TEXT")
	private String signalsJson;

	@Enumerated(EnumType.STRING)
	@Column(name = "verification", length = 16)
	private Verification verification;

	@Column(name = "verification_note", length = 500)
	private String verificationNote;

	@Enumerated(EnumType.STRING)
	@Column(name = "leader_decision", length = 16)
	private LeaderDecision leaderDecision;

	@Column(name = "leader_comment", length = 1000)
	private String leaderComment;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "leader_reviewed_by_user_id", columnDefinition = "char(36)")
	private UUID leaderReviewedByUserId;

	@Column(name = "leader_reviewed_at")
	private LocalDateTime leaderReviewedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "lecturer_outcome", length = 16)
	private Outcome lecturerOutcome;

	@Column(name = "lecturer_comment", length = 1000)
	private String lecturerComment;

	@JdbcTypeCode(Types.CHAR)
	@Column(name = "lecturer_reviewed_by_user_id", columnDefinition = "char(36)")
	private UUID lecturerReviewedByUserId;

	@Column(name = "lecturer_reviewed_at")
	private LocalDateTime lecturerReviewedAt;

	@Column(name = "closed_at")
	private LocalDateTime closedAt;

	@Enumerated(EnumType.STRING)
	@Column(name = "close_reason", length = 32)
	private CloseReason closeReason;

	/** Optimistic lock: two reviewers deciding the same case at once cannot both win. */
	@Version
	@Column(name = "version", nullable = false)
	private Long version;
}

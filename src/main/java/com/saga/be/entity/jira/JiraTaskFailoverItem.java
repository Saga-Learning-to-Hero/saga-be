package com.saga.be.entity.jira;

import com.saga.be.entity.BaseEntity;
import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.enums.TaskStatus;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.Generated;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(
	name = "jira_task_failover_item",
	uniqueConstraints = {
		@UniqueConstraint(name = "uk_jira_failover_item_run_source", columnNames = {"run_id", "source_task_id"}),
		@UniqueConstraint(name = "uk_jira_failover_item_outbound_claim", columnNames = {"outbound_claim_task_id"})
	},
	indexes = {
		@Index(name = "ix_jira_failover_item_source_task", columnList = "source_task_id"),
		@Index(name = "ix_jira_failover_item_target_task", columnList = "target_task_id"),
		@Index(name = "ix_jira_failover_item_status", columnList = "status")
	}
)
public class JiraTaskFailoverItem extends BaseEntity {

	/**
	 * Statuses that hold the durable outbound claim for {@code source_task_id}. Mirrored by V25
	 * generated column {@code outbound_claim_task_id}.
	 */
	public static final Set<JiraFailoverItemStatus> CLAIM_HOLDING_STATUSES = EnumSet.of(
			JiraFailoverItemStatus.PENDING,
			JiraFailoverItemStatus.CREATING,
			JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN,
			JiraFailoverItemStatus.REMOTE_BOUND,
			JiraFailoverItemStatus.SUCCEEDED);

	/** Statuses that release the claim (retryable if eligibility otherwise holds). */
	public static final Set<JiraFailoverItemStatus> CLAIM_RELEASING_STATUSES = EnumSet.of(
			JiraFailoverItemStatus.FAILED,
			JiraFailoverItemStatus.SKIPPED,
			JiraFailoverItemStatus.ABANDONED);

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "run_id", nullable = false)
	private JiraTaskFailoverRun run;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "source_task_id", nullable = false)
	private Task sourceTask;

	@ManyToOne(fetch = FetchType.LAZY, optional = true)
	@JoinColumn(name = "target_task_id", nullable = true)
	private Task targetTask;

	@Enumerated(EnumType.STRING)
	@Column(name = "source_status_snapshot", length = 32, nullable = false)
	private TaskStatus sourceStatusSnapshot;

	@Column(name = "source_external_key_snapshot", length = 64)
	private String sourceExternalKeySnapshot;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", length = 32, nullable = false)
	private JiraFailoverItemStatus status;

	@Column(name = "remote_issue_id", length = 128)
	private String remoteIssueId;

	@Column(name = "remote_issue_key", length = 128)
	private String remoteIssueKey;

	@Column(name = "error_code", length = 64)
	private String errorCode;

	/**
	 * DB-generated claim key (Flyway V25). Null when status releases the claim. Not written by JPA.
	 */
	@Generated
	@Column(name = "outbound_claim_task_id", length = 36, insertable = false, updatable = false)
	private UUID outboundClaimTaskId;

	@Column(name = "started_at")
	private LocalDateTime startedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	/** Target B is run-level SoT — never persisted on the item. */
	public JiraIntegration getTargetJiraIntegration() {
		return run != null ? run.getTargetJiraIntegration() : null;
	}

	public boolean holdsOutboundClaim() {
		return status != null && CLAIM_HOLDING_STATUSES.contains(status);
	}

	/**
	 * Legal forward transitions for Phase 4B+. {@code REMOTE_OUTCOME_UNKNOWN} cannot return to
	 * {@code PENDING}/{@code CREATING}, cannot {@code ABANDONED}/{@code FAILED} (would release the
	 * claim while an unresolved remote issue may exist). {@code REMOTE_BOUND} only completes to
	 * {@code SUCCEEDED}. Run cancellation before create: {@code PENDING} → {@code SKIPPED} or
	 * {@code ABANDONED} releases the claim.
	 */
	boolean canTransitionTo(JiraFailoverItemStatus next) {
		if (next == null || next == status) {
			return false;
		}
		return switch (status) {
			case PENDING -> next == JiraFailoverItemStatus.CREATING
					|| next == JiraFailoverItemStatus.SKIPPED
					|| next == JiraFailoverItemStatus.ABANDONED;
			case CREATING -> next == JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN
					|| next == JiraFailoverItemStatus.REMOTE_BOUND
					|| next == JiraFailoverItemStatus.FAILED
					|| next == JiraFailoverItemStatus.SUCCEEDED;
			case REMOTE_OUTCOME_UNKNOWN -> next == JiraFailoverItemStatus.REMOTE_BOUND
					|| next == JiraFailoverItemStatus.SUCCEEDED;
			case REMOTE_BOUND -> next == JiraFailoverItemStatus.SUCCEEDED;
			case SUCCEEDED, FAILED, SKIPPED, ABANDONED -> false;
		};
	}

	void transitionTo(JiraFailoverItemStatus next) {
		if (!canTransitionTo(next)) {
			throw new IllegalStateException(
					"Illegal failover item transition from " + status + " to " + next);
		}
		this.status = next;
	}
}

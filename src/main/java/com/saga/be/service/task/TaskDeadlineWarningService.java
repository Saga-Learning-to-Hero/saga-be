package com.saga.be.service.task;

import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.WarningCategory;
import com.saga.be.entity.enums.WarningSeverity;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.warning.BusinessWarning;
import com.saga.be.repository.BusinessWarningRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.notification.NotificationService;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic, non-AI deadline scan. Classifies each non-DONE task with a due date via
 * {@link TaskDeadlinePolicy} (which reuses the exact overdue definition already shown on the
 * lecturer dashboard) and, for OVERDUE/DUE_SOON tasks, records an audit-trail {@link BusinessWarning}
 * (category {@link WarningCategory#DEADLINE}, previously unused) and notifies the task's assignee
 * directly. Idempotent per (status, task) — NOT per day: the requirement never specified a repeat
 * cadence, so this deliberately does not invent one. Each task gets at most one notification per
 * status, ever, via the existing {@code business_warning.event_key} uniqueness — re-running the
 * scan (or the scan seeing the same still-overdue task again later) never sends a second
 * notification for the same status.
 */
@Service
@Profile("!test")
public class TaskDeadlineWarningService {
	private static final Logger log = LoggerFactory.getLogger(TaskDeadlineWarningService.class);

	private final TaskRepository tasks;
	private final BusinessWarningRepository warnings;
	private final NotificationService notifications;
	private final TaskDeadlineProperties properties;
	private final Clock clock;

	public TaskDeadlineWarningService(TaskRepository tasks, BusinessWarningRepository warnings, NotificationService notifications, TaskDeadlineProperties properties, Clock clock) {
		this.tasks = tasks;
		this.warnings = warnings;
		this.notifications = notifications;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(fixedDelayString = "${saga.tasks.scan-interval:6h}")
	public void scan() {
		if (!properties.isDeadlineWarningsEnabled()) return;
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		LocalDateTime dueSoonCutoff = now.plus(properties.getDueSoonWindow());
		int page = 0;
		Slice<Task> slice;
		int notified = 0;
		do {
			slice = tasks.findDeadlineScanCandidates(dueSoonCutoff, PageRequest.of(page++, properties.getScanBatchSize()));
			for (Task task : slice.getContent()) {
				TaskDeadlinePolicy.Status status = TaskDeadlinePolicy.classify(task.getStatus(), task.getDueDate(), now, properties.getDueSoonWindow());
				if (status == TaskDeadlinePolicy.Status.NONE) continue;
				if (raiseIfNew(task, status, now)) notified++;
			}
		} while (slice.hasNext());
		if (notified > 0) log.info("task deadline scan notified={} asOf={}", notified, now);
	}

	@Transactional
	boolean raiseIfNew(Task task, TaskDeadlinePolicy.Status status, LocalDateTime now) {
		// No date in the key: the requirement specifies no repeat cadence, so a task gets at most
		// one notification per status ever, not one per day it remains in that status.
		String eventKey = "task-deadline:" + status + ":" + task.getId();
		if (warnings.findByEventKey(eventKey).isPresent()) return false;
		BusinessWarning warning = new BusinessWarning();
		warning.setWarningType(status == TaskDeadlinePolicy.Status.OVERDUE ? "TASK_OVERDUE" : "TASK_DUE_SOON");
		warning.setCategory(WarningCategory.DEADLINE);
		warning.setEventKey(eventKey);
		warning.setSeverity(status == TaskDeadlinePolicy.Status.OVERDUE ? WarningSeverity.HIGH : WarningSeverity.MEDIUM);
		warning.setProject(task.getProject());
		if (task.getProject() != null) warning.setCourse(task.getProject().getCourse());
		String taskLabel = task.getExternalKey() != null ? task.getExternalKey() : task.getId().toString();
		String summary = status == TaskDeadlinePolicy.Status.OVERDUE
				? "Task " + taskLabel + " is overdue (due " + task.getDueDate() + ")."
				: "Task " + taskLabel + " is due soon (due " + task.getDueDate() + ").";
		warning.setEvidenceSummary(summary);
		warnings.save(warning);
		UserAccount assignee = task.getAssigneeStudent() == null ? null : task.getAssigneeStudent().getUserAccount();
		if (assignee != null && assignee.getId() != null) {
			notifications.createNotification(
					assignee.getId(),
					NotificationType.TASK,
					status == TaskDeadlinePolicy.Status.OVERDUE ? "Task overdue" : "Task due soon",
					summary,
					null,
					eventKey);
		}
		return true;
	}
}

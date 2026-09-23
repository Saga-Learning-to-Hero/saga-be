package com.saga.be.service.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.warning.BusinessWarning;
import com.saga.be.repository.BusinessWarningRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskDeadlineWarningServiceTest {
	private TaskRepository tasks;
	private BusinessWarningRepository warnings;
	private NotificationService notifications;
	private TaskDeadlineProperties properties;
	private final Map<String, BusinessWarning> savedByKey = new HashMap<>();

	@BeforeEach
	void setUp() {
		tasks = mock(TaskRepository.class);
		warnings = mock(BusinessWarningRepository.class);
		notifications = mock(NotificationService.class);
		properties = new TaskDeadlineProperties();
		savedByKey.clear();
		when(warnings.findByEventKey(anyString())).thenAnswer(inv -> Optional.ofNullable(savedByKey.get(inv.getArgument(0))));
		when(warnings.save(any(BusinessWarning.class))).thenAnswer(inv -> { BusinessWarning w = inv.getArgument(0); savedByKey.put(w.getEventKey(), w); return w; });
	}

	private TaskDeadlineWarningService service() { return new TaskDeadlineWarningService(tasks, warnings, notifications, properties, Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC)); }

	private Task task(TaskStatus status, LocalDateTime dueDate, UUID assigneeUserId) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setStatus(status);
		task.setDueDate(dueDate);
		task.setExternalKey("SAGA-1");
		if (assigneeUserId != null) {
			UserAccount account = new UserAccount();
			account.setId(assigneeUserId);
			StudentProfile profile = new StudentProfile();
			profile.setUserAccount(account);
			task.setAssigneeStudent(profile);
		}
		return task;
	}

	@Test
	void firstOverdueNotificationCreatesWarningAndNotifiesAssignee() {
		UUID assignee = UUID.randomUUID();
		Task task = task(TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 1, 9, 0, 0), assignee);

		boolean raised = service().raiseIfNew(task, TaskDeadlinePolicy.Status.OVERDUE, LocalDateTime.of(2026, 1, 10, 12, 0));

		assertThat(raised).isTrue();
		verify(warnings).save(any(BusinessWarning.class));
		verify(notifications).createNotification(eq(assignee), eq(NotificationType.TASK), anyString(), anyString(), isNull(), anyString());
	}

	@Test
	void secondCallForTheSameStatusOnALaterDayDoesNotNotifyAgain() {
		UUID assignee = UUID.randomUUID();
		Task task = task(TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 1, 9, 0, 0), assignee);
		TaskDeadlineWarningService service = service();

		boolean first = service.raiseIfNew(task, TaskDeadlinePolicy.Status.OVERDUE, LocalDateTime.of(2026, 1, 10, 12, 0));
		// Same task, same OVERDUE status, but "now" has moved forward several days: must NOT
		// re-notify, since no repeat cadence was ever specified for this feature.
		boolean second = service.raiseIfNew(task, TaskDeadlinePolicy.Status.OVERDUE, LocalDateTime.of(2026, 1, 15, 12, 0));

		assertThat(first).isTrue();
		assertThat(second).isFalse();
		verify(warnings, times(1)).save(any(BusinessWarning.class));
		verify(notifications, times(1)).createNotification(eq(assignee), any(), anyString(), anyString(), isNull(), anyString());
	}

	@Test
	void dueSoonThenOverdueAreTwoDistinctNotificationsNotOne() {
		UUID assignee = UUID.randomUUID();
		Task task = task(TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 1, 12, 0, 0), assignee);
		TaskDeadlineWarningService service = service();

		boolean dueSoon = service.raiseIfNew(task, TaskDeadlinePolicy.Status.DUE_SOON, LocalDateTime.of(2026, 1, 10, 12, 0));
		boolean overdue = service.raiseIfNew(task, TaskDeadlinePolicy.Status.OVERDUE, LocalDateTime.of(2026, 1, 13, 12, 0));

		assertThat(dueSoon).isTrue();
		assertThat(overdue).isTrue();
		verify(warnings, times(2)).save(any(BusinessWarning.class));
		verify(notifications, times(2)).createNotification(eq(assignee), any(), anyString(), anyString(), isNull(), anyString());
	}

	@Test
	void unassignedTaskStillRecordsTheAuditWarningButSkipsNotificationSafely() {
		Task task = task(TaskStatus.TODO, LocalDateTime.of(2026, 1, 9, 0, 0), null);

		boolean raised = service().raiseIfNew(task, TaskDeadlinePolicy.Status.OVERDUE, LocalDateTime.of(2026, 1, 10, 12, 0));

		assertThat(raised).isTrue();
		verify(warnings).save(any(BusinessWarning.class));
		verifyNoInteractions(notifications);
	}

	@Test
	void scanDoesNothingWhenDeadlineWarningsAreDisabled() {
		properties.setDeadlineWarningsEnabled(false);
		service().scan();
		verifyNoInteractions(tasks, warnings, notifications);
	}
}

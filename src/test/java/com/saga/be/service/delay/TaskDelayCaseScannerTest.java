package com.saga.be.service.delay;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.repository.TaskRepository;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class TaskDelayCaseScannerTest {

	private static final LocalDate TODAY = LocalDate.of(2026, 10, 6);

	@Test
	void opensCasesForLateTasksKeepsGoingAfterAFailureAndExpiresWindows() {
		TaskRepository tasks = mock(TaskRepository.class);
		TaskDelayCaseService service = mock(TaskDelayCaseService.class);
		when(service.today()).thenReturn(TODAY);
		Task late = task(TaskStatus.IN_PROGRESS, TODAY.minusDays(2).atStartOfDay(), null);
		Task failing = task(TaskStatus.IN_PROGRESS, TODAY.minusDays(1).atStartOfDay(), null);
		Task doneSameDay = task(TaskStatus.DONE, TODAY.minusDays(3).atStartOfDay(), TODAY.minusDays(3).atTime(20, 0));
		Task doneLate = task(TaskStatus.DONE, TODAY.minusDays(3).atStartOfDay(), TODAY.minusDays(2).atTime(1, 0));
		when(tasks.findDelayCaseOverdueCandidates(eq(TODAY.atStartOfDay()), eq(TODAY.atStartOfDay().minusDays(7)), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(late, failing)));
		when(tasks.findDelayCaseCompletedLateCandidates(eq(TODAY.atStartOfDay().minusDays(7)), any(Pageable.class)))
				.thenReturn(new PageImpl<>(List.of(doneSameDay, doneLate)));
		when(service.openIfAbsent(late.getId())).thenReturn(true);
		when(service.openIfAbsent(failing.getId())).thenThrow(new IllegalStateException("boom"));
		when(service.openIfAbsent(doneLate.getId())).thenReturn(true);

		new TaskDelayCaseScanner(tasks, service, true, Duration.ofDays(7)).scan();

		verify(service).openIfAbsent(late.getId());
		verify(service).openIfAbsent(failing.getId());
		verify(service).openIfAbsent(doneLate.getId());
		// finished within its due day: not late, no case
		verify(service, never()).openIfAbsent(doneSameDay.getId());
		verify(service).expireOverdueExplanations();
	}

	@Test
	void doesNothingWhenSwitchedOff() {
		TaskRepository tasks = mock(TaskRepository.class);
		TaskDelayCaseService service = mock(TaskDelayCaseService.class);

		new TaskDelayCaseScanner(tasks, service, false, Duration.ofDays(7)).scan();

		verifyNoInteractions(tasks, service);
	}

	private static Task task(TaskStatus status, LocalDateTime due, LocalDateTime completedAt) {
		Task task = new Task();
		task.setId(UUID.randomUUID());
		task.setStatus(status);
		task.setDueDate(due);
		task.setCompletedAt(completedAt);
		return task;
	}
}

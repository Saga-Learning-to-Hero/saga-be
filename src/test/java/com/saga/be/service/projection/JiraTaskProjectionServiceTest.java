package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JiraTaskProjectionServiceTest {

	@Mock
	private TaskRepository tasks;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private StudentProfileRepository students;
	@Mock
	private CommitTaskAutoLinkService autoLink;

	private JiraTaskProjectionService service;
	private Project project;

	@BeforeEach
	void setUp() {
		service = new JiraTaskProjectionService(tasks, identities, students, autoLink);
		project = new Project();
		project.setId(UUID.randomUUID());
	}

	@Test
	void upsertBatch_idempotentOnExternalId() {
		IssueSummary issue = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.upsertBatch(project, "SAGA", List.of(issue))).isEqualTo(1);

		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of(existing));
		assertThat(service.upsertBatch(project, "SAGA", List.of(issue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks, times(2)).saveAll(captor.capture());
		assertThat(captor.getAllValues().get(1)).hasSize(1);
		assertThat(captor.getAllValues().get(1).getFirst().getId()).isEqualTo(existing.getId());
		verify(autoLink, times(1)).linkTasks(eq(project.getId()), eq("SAGA"), any());
	}

	@Test
	void ordinaryMetadataUpdate_doesNotTriggerReverseCommitScan() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setProject(project);
		existing.setTitle("Old title");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary updated = issue("10001", "SAGA-1", "New title", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(project, "SAGA", List.of(updated))).isEqualTo(1);
		verify(tasks, times(1)).saveAll(any());
		verify(autoLink, never()).linkTasks(any(), any(), any());
	}

	@Test
	void newlyCreatedTask_triggersReverseCommitScan() {
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary created = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(project, "SAGA", List.of(created))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> linked = ArgumentCaptor.forClass(List.class);
		verify(autoLink).linkTasks(eq(project.getId()), eq("SAGA"), linked.capture());
		assertThat(linked.getValue()).hasSize(1);
		assertThat(linked.getValue().getFirst().getExternalKey()).isEqualTo("SAGA-1");
	}

	@Test
	void externalKeyChange_triggersReverseCommitScan() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary renamed = issue("10001", "SAGA-99", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(project, "SAGA", List.of(renamed))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> linked = ArgumentCaptor.forClass(List.class);
		verify(autoLink).linkTasks(eq(project.getId()), eq("SAGA"), linked.capture());
		assertThat(linked.getValue()).hasSize(1);
		assertThat(linked.getValue().getFirst().getExternalKey()).isEqualTo("SAGA-99");
	}

	@Test
	void upsertBatch_usesSingleExternalIdLookup_forLargeBatches() {
		List<IssueSummary> batch10 = new ArrayList<>();
		List<IssueSummary> batch100 = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			IssueSummary issue = issue(String.valueOf(i), "SAGA-" + i, "T" + i, null);
			batch100.add(issue);
			if (i < 10) {
				batch10.add(issue);
			}
		}
		AtomicInteger lookups = new AtomicInteger();
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenAnswer(inv -> {
			lookups.incrementAndGet();
			return List.of();
		});
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		service.upsertBatch(project, "SAGA", batch10);
		int after10 = lookups.get();
		service.upsertBatch(project, "SAGA", batch100);
		int after100 = lookups.get();

		assertThat(after10).isEqualTo(1);
		assertThat(after100 - after10).isEqualTo(1);
		verify(tasks, never()).findByProject_IdAndExternalId(any(), any());
	}

	@Test
	void upsertBatch_rejectsStaleProviderUpdatedAt() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setProject(project);
		existing.setStatus(TaskStatus.DONE);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 5));
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of(existing));

		IssueSummary stale =
				issue("10001", "SAGA-1", "Login", "2026-01-02T10:03:00Z");
		assertThat(service.upsertBatch(project, "SAGA", List.of(stale))).isEqualTo(0);
		verify(tasks, never()).saveAll(any());
		verify(autoLink, never()).linkTasks(any(), any(), any());
	}

	@Test
	void upsertBatch_doesNotResurrectWithStaleUpdateAfterDelete() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setProject(project);
		existing.setDeletedAt(LocalDateTime.of(2026, 1, 2, 10, 10));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 5));
		when(tasks.findByProject_IdAndExternalIdIn(eq(project.getId()), any())).thenReturn(List.of(existing));

		IssueSummary stale =
				issue("10001", "SAGA-1", "Login", "2026-01-02T10:03:00Z");
		assertThat(service.upsertBatch(project, "SAGA", List.of(stale))).isEqualTo(0);
		verify(tasks, never()).saveAll(any());
	}

	@Test
	void shouldApply_staticOrderingRules() {
		Task fresh = new Task();
		assertThat(JiraTaskProjectionService.shouldApply(fresh, LocalDateTime.now())).isTrue();

		Task newer = new Task();
		newer.setId(UUID.randomUUID());
		newer.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 5));
		assertThat(JiraTaskProjectionService.shouldApply(newer, LocalDateTime.of(2026, 1, 2, 10, 3))).isFalse();
		assertThat(JiraTaskProjectionService.shouldApply(newer, LocalDateTime.of(2026, 1, 2, 10, 6))).isTrue();
	}

	private static IssueSummary issue(String id, String key, String summary, String updated) {
		return new IssueSummary(id, key, summary, "1", "To Do", "new", "Task", null, null, updated);
	}
}

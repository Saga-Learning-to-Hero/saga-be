package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
	@Mock
	private SprintRepository sprints;

	private JiraTaskProjectionService service;
	private Project project;
	private JiraIntegration integration;

	@BeforeEach
	void setUp() {
		service = new JiraTaskProjectionService(
				tasks, identities, students, autoLink, sprints, new com.fasterxml.jackson.databind.ObjectMapper(), org.mockito.Mockito.mock(com.saga.be.service.ai.AiTaskAutomationTrigger.class));
		project = new Project();
		project.setId(UUID.randomUUID());
		integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
	}

	@Test
	void upsertBatch_idempotentOnExternalId() {
		IssueSummary issue = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		assertThat(service.upsertBatch(integration, "SAGA", List.of(issue))).isEqualTo(1);

		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		assertThat(service.upsertBatch(integration, "SAGA", List.of(issue))).isEqualTo(1);

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
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setTitle("Old title");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary updated = issue("10001", "SAGA-1", "New title", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(updated))).isEqualTo(1);
		verify(tasks, times(1)).saveAll(any());
		verify(autoLink, never()).linkTasks(any(), any(), any());
	}

	@Test
	void newlyCreatedTask_triggersReverseCommitScan() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary created = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(created))).isEqualTo(1);

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
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary renamed = issue("10001", "SAGA-99", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(renamed))).isEqualTo(1);

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
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenAnswer(inv -> {
			lookups.incrementAndGet();
			return List.of();
		});
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		service.upsertBatch(integration, "SAGA", batch10);
		int after10 = lookups.get();
		service.upsertBatch(integration, "SAGA", batch100);
		int after100 = lookups.get();

		assertThat(after10).isEqualTo(1);
		assertThat(after100 - after10).isEqualTo(1);
		verify(tasks, never()).findByJiraIntegration_IdAndExternalId(any(), any());
	}

	@Test
	void upsertBatch_rejectsStaleProviderUpdatedAt() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setStatus(TaskStatus.DONE);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 5));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));

		IssueSummary stale =
				issue("10001", "SAGA-1", "Login", "2026-01-02T10:03:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(stale))).isEqualTo(0);
		verify(tasks, never()).saveAll(any());
		verify(autoLink, never()).linkTasks(any(), any(), any());
	}

	@Test
	void upsertBatch_doesNotResurrectWithStaleUpdateAfterDelete() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setDeletedAt(LocalDateTime.of(2026, 1, 2, 10, 10));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 10, 5));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));

		IssueSummary stale =
				issue("10001", "SAGA-1", "Login", "2026-01-02T10:03:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(stale))).isEqualTo(0);
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

	@Test
	void upsertBatch_persistsPriorityStoryPointAndSprintRelation() {
		when(sprints.findByJiraIntegration_IdAndExternalSprintIdIn(eq(integration.getId()), any()))
				.thenReturn(List.of());
		when(sprints.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), "31")).thenReturn(Optional.empty());
		when(sprints.save(any())).thenAnswer(inv -> {
			com.saga.be.entity.jira.Sprint sprint = inv.getArgument(0);
			if (sprint.getId() == null) {
				sprint.setId(UUID.randomUUID());
			}
			return sprint;
		});
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary issue = new IssueSummary(
				"10001",
				"SAGA-1",
				"Login",
				"1",
				"To Do",
				"new",
				"Story",
				"10001",
				"acc-1",
				"Alice",
				"2",
				"High",
				8,
				"body",
				"31",
				"Sprint 1",
				"active",
				null,
				"2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(issue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getPriority()).isEqualTo(com.saga.be.entity.enums.Priority.HIGH);
		assertThat(saved.getStoryPoint()).isEqualTo(8);
		assertThat(saved.getDescription()).isEqualTo("body");
		assertThat(saved.getSprint()).isNotNull();
		assertThat(saved.getSprint().getExternalSprintId()).isEqualTo("31");
	}

	@Test
	void upsertBatch_ordinaryTask_parentStaysNull() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary issue = issue("10001", "SAGA-1", "Ordinary task", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(issue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isNull();
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isNull();
	}

	@Test
	void upsertBatch_subtask_persistsParentExternalIdAndKey() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary subtask = new IssueSummary(
				"10050", "SAGA-50", "Implement login form", "1", "To Do", "new", "Subtask", "10003", null, null,
				null, null, null, null, null, null, null, null, "2026-01-02T10:00:00Z", true, true, "10049",
				"SAGA-49", true, List.of(), true, null, true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(subtask))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getParentExternalId()).isEqualTo("10049");
		assertThat(saved.getParentExternalKey()).isEqualTo("SAGA-49");
	}

	@Test
	void upsertBatch_childSyncedBeforeParent_parentIdentityStillPersists() {
		// No FK to a local parent Task row -- the parent Task need not exist (or ever exist)
		// locally for the child's provider-identity fields to persist correctly.
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));
		// The parent ("SAGA-49") is deliberately never looked up or referenced by id anywhere --
		// no sprints/tasks interaction beyond the child's own row is required.

		IssueSummary childBeforeParent = new IssueSummary(
				"10050", "SAGA-50", "Child synced first", "1", "To Do", "new", "Subtask", "10003", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:00:00Z", true, true, "10049", "SAGA-49",
				true, List.of(), true, null, true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(childBeforeParent))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isEqualTo("10049");
	}

	@Test
	void upsertBatch_parentRowLaterSyncing_doesNotMutateAlreadySyncedChild() {
		// The "parent" is just another Task row synced independently/later -- syncing it must never
		// touch any other Task's fields (no backfill/reconciliation pass exists or is needed).
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary parentIssue = issue("10049", "SAGA-49", "Parent story", "2026-01-02T11:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(parentIssue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task savedParent = captor.getValue().getFirst();
		assertThat(savedParent.getExternalId()).isEqualTo("10049");
		assertThat(savedParent.getParentExternalId()).isNull();
	}

	@Test
	void upsertBatch_authoritativeSyncRemovesParent_clearsStoredParent() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10050");
		existing.setExternalKey("SAGA-50");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setParentExternalId("10049");
		existing.setParentExternalKey("SAGA-49");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Authoritative (full sync) fetch: Jira no longer reports a parent (e.g. converted from
		// Subtask to a standalone Task) -- fields.parent absent, authoritative=true means the
		// legacy 19-arg constructor's parentProvided=true default, parentExternalId=null clears it.
		IssueSummary noLongerASubtask = issue("10050", "SAGA-50", "No longer a subtask", "2026-01-02T10:05:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(noLongerASubtask))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isNull();
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isNull();
	}

	@Test
	void upsertBatch_nonAuthoritativeWebhookOmittingParent_preservesExisting() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10050");
		existing.setExternalKey("SAGA-50");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setParentExternalId("10049");
		existing.setParentExternalKey("SAGA-49");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Non-authoritative (webhook) payload that never touched the parent field at all
		// (parentProvided=false) -- must preserve, not clear.
		IssueSummary webhookNoParentInfo = new IssueSummary(
				"10050", "SAGA-50", "Title only change", "1", "To Do", "new", "Subtask", "10003", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookNoParentInfo))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isEqualTo("10049");
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isEqualTo("SAGA-49");
	}

	@Test
	void upsertBatch_webhookParentChanged_reflectsNewParent() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10050");
		existing.setExternalKey("SAGA-50");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setParentExternalId("10049");
		existing.setParentExternalKey("SAGA-49");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary webhookParentMoved = new IssueSummary(
				"10050", "SAGA-50", "Moved to another parent", "1", "To Do", "new", "Subtask", "10003", null, null,
				null, null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, "10060",
				"SAGA-60", true, List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookParentMoved))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isEqualTo("10060");
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isEqualTo("SAGA-60");
	}

	@Test
	void upsertBatch_webhookParentRemoved_clearsParentThroughProvidedFlag() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10050");
		existing.setExternalKey("SAGA-50");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setParentExternalId("10049");
		existing.setParentExternalKey("SAGA-49");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Webhook explicitly reports parent removed (parentProvided=true, ids null) -- distinct
		// from "omitted" (parentProvided=false, tested above).
		IssueSummary webhookParentRemoved = new IssueSummary(
				"10050", "SAGA-50", "Detached from parent", "1", "To Do", "new", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, true,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookParentRemoved))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isNull();
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isNull();
	}

	@Test
	void upsertBatch_softDeletedParentTask_doesNotCorruptChildParentFields() {
		// No relationship traversal exists between a child's parentExternalId/Key and any local
		// parent Task row, so soft-deleting the parent Task (a separate, independent row) has zero
		// effect on the child's own stored parent identity fields.
		Task softDeletedParent = new Task();
		softDeletedParent.setId(UUID.randomUUID());
		softDeletedParent.setExternalId("10049");
		softDeletedParent.setExternalKey("SAGA-49");
		softDeletedParent.setJiraIntegration(integration);
		softDeletedParent.setProject(project);
		softDeletedParent.setDeletedAt(LocalDateTime.of(2026, 1, 1, 0, 0));

		Task child = new Task();
		child.setId(UUID.randomUUID());
		child.setExternalId("10050");
		child.setExternalKey("SAGA-50");
		child.setJiraIntegration(integration);
		child.setProject(project);
		child.setParentExternalId("10049");
		child.setParentExternalKey("SAGA-49");
		child.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(child));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary childUnrelatedUpdate = new IssueSummary(
				"10050", "SAGA-50", "Unrelated title change", "1", "To Do", "new", "Subtask", "10003", null, null,
				null, null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null,
				false, List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(childUnrelatedUpdate))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getParentExternalId()).isEqualTo("10049");
		assertThat(captor.getValue().getFirst().getParentExternalKey()).isEqualTo("SAGA-49");
	}

	@Test
	void upsertBatch_doesNotModifyNativeParentTask() {
		Task nativeParent = new Task();
		nativeParent.setId(UUID.randomUUID());
		nativeParent.setTitle("SAGA parent");
		Task child = new Task();
		child.setId(UUID.randomUUID());
		child.setExternalId("10050");
		child.setExternalKey("SAGA-50");
		child.setJiraIntegration(integration);
		child.setProject(project);
		child.setParentTask(nativeParent);
		child.setParentExternalId("10049");
		child.setParentExternalKey("SAGA-49");
		child.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(child));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary updated = new IssueSummary(
				"10050", "SAGA-50", "Still a subtask", "1", "To Do", "new", "Subtask", "10003", null, null, null, null,
				null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, "10099", "SAGA-99", true,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(updated))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> nativeCaptor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(nativeCaptor.capture());
		Task saved = nativeCaptor.getValue().getFirst();
		assertThat(saved.getParentTask()).isSameAs(nativeParent);
		assertThat(saved.getParentExternalId()).isEqualTo("10099");
		assertThat(saved.getParentExternalKey()).isEqualTo("SAGA-99");
	}

	@Test
	void upsertBatch_webhookPayloadOmittingFields_preservesExistingStoryPointAndSprint() {
		// Regression: a full sync sets storyPoint=5 and Sprint X; a later partial webhook that
		// never carries those custom fields (storyPointsProvided/sprintProvided=false) must leave
		// both alone rather than nulling them out.
		com.saga.be.entity.jira.Sprint sprintX = new com.saga.be.entity.jira.Sprint();
		sprintX.setId(UUID.randomUUID());
		sprintX.setExternalSprintId("31");
		sprintX.setJiraIntegration(integration);

		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		existing.setStoryPoint(5);
		existing.setSprint(sprintX);
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary webhookIssue = new IssueSummary(
				"10001", "SAGA-1", "Login", "1", "In Progress", "indeterminate", "Story", "10001", "acc-1", "Alice",
				"2", "High", null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null,
				false, List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookIssue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getStoryPoint()).isEqualTo(5);
		assertThat(saved.getSprint()).isSameAs(sprintX);
	}

	@Test
	void upsertBatch_authoritativeExplicitClear_nullsOutStoryPointAndSprint() {
		// Contrast case: an authoritative (bulk/full sync or single-issue fetch) payload that
		// genuinely reports storyPoint/sprint as absent DOES clear them -- distinguishing a real
		// Jira-side clear from a webhook simply not carrying the field (tested above).
		com.saga.be.entity.jira.Sprint sprintX = new com.saga.be.entity.jira.Sprint();
		sprintX.setId(UUID.randomUUID());
		sprintX.setExternalSprintId("31");
		sprintX.setJiraIntegration(integration);

		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		existing.setStoryPoint(5);
		existing.setSprint(sprintX);
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary authoritativeIssue = issue("10001", "SAGA-1", "Login", "2026-01-02T10:05:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(authoritativeIssue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getStoryPoint()).isNull();
		assertThat(saved.getSprint()).isNull();
	}

	@Test
	void issueUpdatedStatusChange_updatesTaskStatusAndJiraStatusFields() {
		// Regression (webhook realtime audit): a status-only change delivered via issue_updated
		// must update Task.status, Task.jiraStatusId, and Task.jiraStatusName -- status is a base
		// Jira field (always present in the payload, never gated behind dynamic field discovery
		// the way Story Points/Sprint are), so this must always apply, not just on full sync.
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setTitle("Login");
		existing.setStatus(com.saga.be.entity.enums.TaskStatus.TODO);
		existing.setJiraStatusId("1");
		existing.setJiraStatusName("To Do");
		existing.setJiraStatusCategory("new");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary statusChanged = new IssueSummary(
				"10001", "SAGA-1", "Login", "3", "In Progress", "indeterminate", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(statusChanged))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getStatus()).isEqualTo(com.saga.be.entity.enums.TaskStatus.IN_PROGRESS);
		assertThat(saved.getJiraStatusId()).isEqualTo("3");
		assertThat(saved.getJiraStatusName()).isEqualTo("In Progress");
		assertThat(saved.getJiraStatusCategory()).isEqualTo("indeterminate");
	}

	// ==================== LABELS PROJECTION (full sync + webhook) ====================

	@Test
	void upsertBatch_authoritativeSync_persistsLabels() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary labelled = new IssueSummary(
				"10001", "SAGA-1", "Login", "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null,
				null, null, null, null, "2026-01-02T10:00:00Z", true, true, null, null, true,
				List.of("backend", "urgent"), true, null, true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(labelled))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(com.saga.be.service.contribution.TaskLabelParser.parse(saved.getLabelsJson()))
				.containsExactly("backend", "urgent");
	}

	@Test
	void upsertBatch_webhookOmittingLabels_preservesExistingLabels() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setLabelsJson("[\"backend\",\"urgent\"]");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Non-authoritative (webhook) payload that never carries "labels" at all -- must preserve,
		// not clear.
		IssueSummary webhookNoLabelInfo = new IssueSummary(
				"10001", "SAGA-1", "Title only change", "1", "To Do", "new", "Task", "10001", null, null, null, null,
				null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookNoLabelInfo))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getLabelsJson()).isEqualTo("[\"backend\",\"urgent\"]");
	}

	@Test
	void upsertBatch_webhookExplicitEmptyLabels_clearsAllLabels() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setLabelsJson("[\"backend\",\"urgent\"]");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Webhook reports "labels":[] explicitly -- the true current value (all cleared in Jira),
		// distinct from "omitted" (tested above).
		IssueSummary webhookLabelsCleared = new IssueSummary(
				"10001", "SAGA-1", "Labels cleared in Jira", "1", "To Do", "new", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), true, null, true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookLabelsCleared))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(com.saga.be.service.contribution.TaskLabelParser.parse(captor.getValue().getFirst().getLabelsJson()))
				.isEmpty();
	}

	@Test
	void upsertBatch_webhookLabelsChanged_reflectsNewLabelSet() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setLabelsJson("[\"backend\"]");
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary webhookLabelsUpdated = new IssueSummary(
				"10001", "SAGA-1", "Labels updated in Jira", "1", "To Do", "new", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of("frontend", "urgent"), true, null, true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookLabelsUpdated))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(com.saga.be.service.contribution.TaskLabelParser.parse(captor.getValue().getFirst().getLabelsJson()))
				.containsExactly("frontend", "urgent");
	}

	// ==================== DUE DATE PROJECTION (full sync + webhook) ====================

	@Test
	void upsertBatch_authoritativeSync_persistsDueDate() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary withDueDate = new IssueSummary(
				"10001", "SAGA-1", "Login", "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null,
				null, null, null, null, "2026-01-02T10:00:00Z", true, true, null, null, true, List.of(), true,
				java.time.LocalDate.of(2026, 9, 18), true, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(withDueDate))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getDueDate()).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
	}

	@Test
	void upsertBatch_authoritativeSync_noDueDate_staysNull() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary noDueDate = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(noDueDate))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getDueDate()).isNull();
	}

	@Test
	void upsertBatch_webhookOmittingDueDate_preservesExistingDueDate() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setDueDate(LocalDateTime.of(2026, 9, 18, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Non-authoritative (webhook) payload that never carries "duedate" at all -- must preserve.
		IssueSummary webhookNoDueDateInfo = new IssueSummary(
				"10001", "SAGA-1", "Title only change", "1", "To Do", "new", "Task", "10001", null, null, null, null,
				null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookNoDueDateInfo))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getDueDate()).isEqualTo(LocalDateTime.of(2026, 9, 18, 0, 0));
	}

	@Test
	void upsertBatch_webhookExplicitDueDateClear_clearsIt() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setDueDate(LocalDateTime.of(2026, 9, 18, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Webhook reports "duedate":null explicitly -- the true current value (cleared in Jira),
		// distinct from "omitted" (tested above).
		IssueSummary webhookDueDateCleared = new IssueSummary(
				"10001", "SAGA-1", "Due date cleared in Jira", "1", "To Do", "new", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, true, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookDueDateCleared))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getDueDate()).isNull();
	}

	@Test
	void upsertBatch_authoritativeSyncRemovesDueDate_clearsStoredDueDate() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setDueDate(LocalDateTime.of(2026, 9, 18, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		// Authoritative (full sync) fetch: Jira no longer reports a due date -- must clear it (a
		// full/authoritative fetch's absence IS the true current value).
		IssueSummary noLongerDue = issue("10001", "SAGA-1", "Due date removed", "2026-01-02T10:05:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(noLongerDue))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getDueDate()).isNull();
	}

	@Test
	void upsertBatch_authoritativeSync_persistsStartDate() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary withStartDate = new IssueSummary(
				"10001", "SAGA-1", "Login", "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null,
				null, null, null, null, "2026-01-02T10:00:00Z", true, true, null, null, true, List.of(), true, null,
				true, java.time.LocalDate.of(2026, 9, 14), true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(withStartDate))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		Task saved = captor.getValue().getFirst();
		assertThat(saved.getStartDate()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
	}

	@Test
	void upsertBatch_authoritativeSync_noStartDate_staysNull() {
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of());
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary noStartDate = issue("10001", "SAGA-1", "Login", "2026-01-02T10:00:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(noStartDate))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getStartDate()).isNull();
	}

	@Test
	void upsertBatch_webhookOmittingStartDate_preservesExistingStartDate() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setStartDate(LocalDateTime.of(2026, 9, 14, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary webhookNoStartDateInfo = new IssueSummary(
				"10001", "SAGA-1", "Title only change", "1", "To Do", "new", "Task", "10001", null, null, null, null,
				null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, false, null, false);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookNoStartDateInfo))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getStartDate()).isEqualTo(LocalDateTime.of(2026, 9, 14, 0, 0));
	}

	@Test
	void upsertBatch_webhookExplicitStartDateClear_clearsIt() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setStartDate(LocalDateTime.of(2026, 9, 14, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary webhookStartDateCleared = new IssueSummary(
				"10001", "SAGA-1", "Start date cleared in Jira", "1", "To Do", "new", "Task", "10001", null, null, null,
				null, null, null, null, null, null, null, "2026-01-02T10:05:00Z", false, false, null, null, false,
				List.of(), false, null, false, null, true);
		assertThat(service.upsertBatch(integration, "SAGA", List.of(webhookStartDateCleared))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getStartDate()).isNull();
	}

	@Test
	void upsertBatch_authoritativeSyncRemovesStartDate_clearsStoredStartDate() {
		Task existing = new Task();
		existing.setId(UUID.randomUUID());
		existing.setExternalId("10001");
		existing.setExternalKey("SAGA-1");
		existing.setJiraIntegration(integration);
		existing.setProject(project);
		existing.setStartDate(LocalDateTime.of(2026, 9, 14, 0, 0));
		existing.setExternalUpdatedAt(LocalDateTime.of(2026, 1, 2, 9, 0));
		when(tasks.findByJiraIntegration_IdAndExternalIdIn(eq(integration.getId()), any())).thenReturn(List.of(existing));
		when(tasks.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

		IssueSummary noLongerStarted = issue("10001", "SAGA-1", "Start date removed", "2026-01-02T10:05:00Z");
		assertThat(service.upsertBatch(integration, "SAGA", List.of(noLongerStarted))).isEqualTo(1);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Task>> captor = ArgumentCaptor.forClass(List.class);
		verify(tasks).saveAll(captor.capture());
		assertThat(captor.getValue().getFirst().getStartDate()).isNull();
	}

	private static IssueSummary issue(String id, String key, String summary, String updated) {
		return new IssueSummary(
				id, key, summary, "1", "To Do", "new", "Task", "10001", null, null, null, null, null, null, null, null,
				null, null, updated);
	}
}

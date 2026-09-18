package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.TaskParentOptionItem;
import com.saga.be.dto.project.TaskParentOptionsResponse;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskParentIdentity;
import com.saga.be.repository.TaskRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class TaskHierarchyServiceTest {

	@Mock
	private ProjectRepository projects;
	@Mock
	private TaskRepository tasks;
	@Mock
	private PlatformTransactionManager transactionManager;

	private TaskHierarchyService service;
	private UUID projectId;
	private UUID childId;
	private UUID parentId;

	@BeforeEach
	void setUp() {
		org.mockito.Mockito.lenient()
				.when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		org.mockito.Mockito.lenient().when(projects.lockById(any())).thenAnswer(inv -> {
			Project project = new Project();
			project.setId(inv.getArgument(0));
			return Optional.of(project);
		});
		org.mockito.Mockito.lenient().when(tasks.save(any())).thenAnswer(inv -> inv.getArgument(0));
		service = new TaskHierarchyService(projects, tasks, transactionManager);
		projectId = UUID.randomUUID();
		childId = UUID.randomUUID();
		parentId = UUID.randomUUID();
	}

	@Test
	void selfParentRejected() {
		assertThatThrownBy(() -> service.validateAssignable(projectId, childId, childId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
		verify(tasks, never()).findParentIdentity(any());
	}

	@Test
	void nullParentAllowed() {
		service.validateAssignable(projectId, childId, null);
		verify(tasks, never()).findParentIdentity(any());
	}

	@Test
	void deletedParentRejected() {
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, LocalDateTime.now())));
		assertThatThrownBy(() -> service.validateAssignable(projectId, childId, parentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
	}

	@Test
	void foreignProjectRejected() {
		when(tasks.findParentIdentity(parentId))
				.thenReturn(Optional.of(identity(parentId, UUID.randomUUID(), null)));
		assertThatThrownBy(() -> service.validateAssignable(projectId, null, parentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
	}

	@Test
	void missingParentRejected() {
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.validateAssignable(projectId, null, parentId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
	}

	@Test
	void sameProjectActiveParentAccepted() {
		when(tasks.findParentIdentity(parentId)).thenReturn(Optional.of(identity(parentId, projectId, null)));
		service.validateAssignable(projectId, null, parentId);
	}

	@Test
	void twoNodeCycleRejected() {
		UUID bId = parentId;
		when(tasks.findParentIdentity(bId)).thenReturn(Optional.of(identity(bId, projectId, null)));
		when(tasks.findParentIdentity(childId)).thenReturn(Optional.of(identity(childId, projectId, null)));
		when(tasks.findParentTaskIdById(bId)).thenReturn(Optional.of(childId));
		assertThatThrownBy(() -> service.validateAssignable(projectId, childId, bId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
	}

	@Test
	void threeNodeCycleRejected() {
		UUID bId = UUID.randomUUID();
		UUID cId = parentId;
		when(tasks.findParentIdentity(cId)).thenReturn(Optional.of(identity(cId, projectId, null)));
		when(tasks.findParentIdentity(childId)).thenReturn(Optional.of(identity(childId, projectId, null)));
		when(tasks.findParentTaskIdById(cId)).thenReturn(Optional.of(bId));
		when(tasks.findParentTaskIdById(bId)).thenReturn(Optional.of(childId));
		assertThatThrownBy(() -> service.validateAssignable(projectId, childId, cId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TASK_PARENT_INVALID);
	}

	@Test
	void mutateParentClearPersistsNull() {
		Task child = child(childId);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(childId, projectId)).thenReturn(Optional.of(child));
		child.setParentTask(parent(parentId));

		Task saved = service.mutateParent(projectId, childId, null);

		assertThat(saved.getParentTask()).isNull();
		verify(projects).lockById(projectId);
		verify(tasks).save(child);
	}

	@Test
	void parentOptionsCapsSizeAndExcludesSelfAndDescendants() {
		UUID grandchild = UUID.randomUUID();
		when(tasks.findActiveChildIdsByParentIds(any())).thenAnswer(inv -> {
			java.util.Collection<?> ids = inv.getArgument(0);
			if (ids.contains(childId)) {
				return List.of(parentId);
			}
			if (ids.contains(parentId)) {
				return List.of(grandchild);
			}
			return List.of();
		});
		when(tasks.findParentOptions(eq(projectId), any(), eq(true), eq(""), eq(PageRequest.of(0, 50))))
				.thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 50), 0));

		TaskParentOptionsResponse response = service.listParentOptions(projectId, "  ", 0, 99, childId);

		assertThat(response.size()).isEqualTo(50);
		assertThat(response.page()).isEqualTo(0);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Set<UUID>> excluded = ArgumentCaptor.forClass(Set.class);
		verify(tasks).findParentOptions(eq(projectId), excluded.capture(), eq(true), eq(""), eq(PageRequest.of(0, 50)));
		assertThat(excluded.getValue()).containsExactlyInAnyOrder(childId, parentId, grandchild);
	}

	@Test
	void parentOptionsMapsPagedRows() {
		UUID optionId = UUID.randomUUID();
		when(tasks.findParentOptions(eq(projectId), any(), eq(false), eq("log"), eq(PageRequest.of(1, 20))))
				.thenReturn(new PageImpl<Object[]>(
						List.<Object[]>of(new Object[] {optionId, "Login", com.saga.be.entity.enums.TaskStatus.TODO, parentId, "SAGA-1"}),
						PageRequest.of(1, 20),
						42));

		TaskParentOptionsResponse response = service.listParentOptions(projectId, "LOG", 1, 20, null);

		assertThat(response.total()).isEqualTo(42);
		assertThat(response.items()).containsExactly(new TaskParentOptionItem(optionId, "Login", "TODO", parentId, "SAGA-1"));
	}

	@Test
	void assertDeletable_blocksOnlyWhenActiveChildrenExist() {
		when(tasks.existsByParentTask_IdAndDeletedAtIsNull(childId)).thenReturn(true);
		assertThatThrownBy(() -> service.assertDeletableWithoutActiveChildren(projectId, childId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_SUBTASKS);
		verify(projects).lockById(projectId);
	}

	@Test
	void assertDeletable_allowsWhenOnlySoftDeletedChildrenExist() {
		when(tasks.existsByParentTask_IdAndDeletedAtIsNull(childId)).thenReturn(false);
		service.assertDeletableWithoutActiveChildren(projectId, childId);
		verify(tasks).existsByParentTask_IdAndDeletedAtIsNull(childId);
		verify(projects).lockById(projectId);
	}

	private Task child(UUID id) {
		Task task = new Task();
		task.setId(id);
		task.setTitle("Child");
		return task;
	}

	private Task parent(UUID id) {
		Task task = new Task();
		task.setId(id);
		task.setTitle("Parent");
		return task;
	}

	private static TaskParentIdentity identity(UUID id, UUID projectId, LocalDateTime deletedAt) {
		return new TaskParentIdentity() {
			@Override
			public UUID getId() {
				return id;
			}

			@Override
			public UUID getProjectId() {
				return projectId;
			}

			@Override
			public LocalDateTime getDeletedAt() {
				return deletedAt;
			}
		};
	}
}

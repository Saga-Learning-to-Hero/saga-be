package com.saga.be.service.projection;

import com.saga.be.dto.project.TaskParentOptionItem;
import com.saga.be.dto.project.TaskParentOptionsResponse;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TaskParentIdentity;
import com.saga.be.repository.TaskRepository;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Canonical short-transaction native hierarchy mutations. Serializes per Project via pessimistic
 * row lock; callers must not hold that lock across Jira HTTP.
 */
@Service
@Profile("!test")
public class TaskHierarchyService {

	/** Defensive walk ceiling against corrupt cycles — not a product-visible depth limit. */
	static final int CYCLE_WALK_CEILING = 10_000;

	static final int PARENT_OPTIONS_MAX_SIZE = 50;
	static final int PARENT_OPTIONS_DEFAULT_SIZE = 20;

	private static final UUID SENTINEL_EXCLUDE = UUID.fromString("00000000-0000-4000-8000-000000000000");

	private final ProjectRepository projects;
	private final TaskRepository tasks;
	private final TransactionTemplate writes;

	public TaskHierarchyService(
			ProjectRepository projects, TaskRepository tasks, PlatformTransactionManager transactionManager) {
		this.projects = projects;
		this.tasks = tasks;
		this.writes = new TransactionTemplate(transactionManager);
	}

	public void validateAssignable(UUID projectId, UUID childId, UUID parentId) {
		if (parentId == null) {
			return;
		}
		if (childId != null && childId.equals(parentId)) {
			throw parentInvalid("A task cannot be its own parent.");
		}
		TaskParentIdentity parent = tasks.findParentIdentity(parentId)
				.orElseThrow(() -> parentInvalid("Parent task was not found."));
		if (parent.getDeletedAt() != null) {
			throw parentInvalid("Parent task is not active.");
		}
		if (!projectId.equals(parent.getProjectId())) {
			throw parentInvalid("Parent task belongs to another project.");
		}
		if (childId != null) {
			TaskParentIdentity child = tasks.findParentIdentity(childId)
					.orElseThrow(() -> parentInvalid("Task was not found for this project."));
			if (child.getDeletedAt() != null) {
				throw parentInvalid("Task is not active.");
			}
			if (!projectId.equals(child.getProjectId())) {
				throw parentInvalid("Task belongs to another project.");
			}
			assertNoCycle(childId, parentId);
		}
	}

	/**
	 * Caller must already be inside a JDBC transaction. Holds {@code SELECT ... FOR UPDATE} on the
	 * Project row until that transaction commits.
	 */
	public void acquireHierarchyMutationLock(UUID projectId) {
		projects.lockById(projectId).orElseThrow(() -> new AcademicException(
				AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Project was not found."));
	}

	public Task applyParent(UUID projectId, UUID childId, UUID parentId) {
		Task child = tasks.findByIdAndProject_IdAndDeletedAtIsNull(childId, projectId)
				.orElseThrow(() -> parentInvalid("Task was not found for this project."));
		if (parentId == null) {
			child.setParentTask(null);
		} else {
			validateAssignable(projectId, childId, parentId);
			Task parent = tasks.findByIdAndProject_IdAndDeletedAtIsNull(parentId, projectId)
					.orElseThrow(() -> parentInvalid("Parent task was not found in this project."));
			child.setParentTask(parent);
		}
		return tasks.save(child);
	}

	public Task mutateParent(UUID projectId, UUID childId, UUID parentId) {
		return writes.execute(status -> {
			acquireHierarchyMutationLock(projectId);
			return applyParent(projectId, childId, parentId);
		});
	}

	public void assertDeletableWithoutActiveChildren(UUID projectId, UUID taskId) {
		writes.executeWithoutResult(status -> {
			acquireHierarchyMutationLock(projectId);
			if (tasks.existsByParentTask_IdAndDeletedAtIsNull(taskId)) {
				throw new IntegrationException(
						IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_SUBTASKS,
						HttpStatus.CONFLICT,
						"Cannot delete a task that still has active subtasks.");
			}
		});
	}

	public TaskParentOptionsResponse listParentOptions(
			UUID projectId, String q, int page, int size, UUID excludeTaskId) {
		if (page < 0 || size < 1 || size > PARENT_OPTIONS_MAX_SIZE) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"page must be >= 0 and size must be between 1 and " + PARENT_OPTIONS_MAX_SIZE + ".");
		}
		Set<UUID> excluded = descendantIdsIncludingSelf(excludeTaskId);
		if (excluded.isEmpty()) {
			excluded.add(SENTINEL_EXCLUDE);
		}
		boolean qBlank = q == null || q.isBlank();
		String qPrefix = qBlank ? "" : q.trim().toLowerCase();
		Page<Object[]> result = tasks.findParentOptions(
				projectId, excluded, qBlank, qPrefix, PageRequest.of(page, size));
		List<TaskParentOptionItem> items = new ArrayList<>(result.getNumberOfElements());
		for (Object[] row : result.getContent()) {
			TaskStatus status = (TaskStatus) row[2];
			items.add(new TaskParentOptionItem(
					(UUID) row[0],
					(String) row[1],
					status == null ? null : status.name(),
					(UUID) row[3],
					(String) row[4]));
		}
		return new TaskParentOptionsResponse(items, page, size, result.getTotalElements());
	}

	private Set<UUID> descendantIdsIncludingSelf(UUID excludeTaskId) {
		Set<UUID> excluded = new HashSet<>();
		if (excludeTaskId == null) {
			return excluded;
		}
		excluded.add(excludeTaskId);
		ArrayDeque<UUID> queue = new ArrayDeque<>();
		queue.add(excludeTaskId);
		int steps = 0;
		while (!queue.isEmpty()) {
			if (++steps > CYCLE_WALK_CEILING) {
				break;
			}
			List<UUID> batch = new ArrayList<>(queue);
			queue.clear();
			List<UUID> children = tasks.findActiveChildIdsByParentIds(batch);
			for (UUID childId : children) {
				if (excluded.add(childId)) {
					queue.add(childId);
				}
			}
		}
		return excluded;
	}

	private void assertNoCycle(UUID childId, UUID parentId) {
		UUID cursor = parentId;
		Set<UUID> seen = new HashSet<>();
		int steps = 0;
		while (cursor != null) {
			if (cursor.equals(childId)) {
				throw parentInvalid("Assigning this parent would create a cycle.");
			}
			if (!seen.add(cursor)) {
				throw parentInvalid("Assigning this parent would create a cycle.");
			}
			if (++steps > CYCLE_WALK_CEILING) {
				throw parentInvalid("Task hierarchy could not be validated.");
			}
			cursor = tasks.findParentTaskIdById(cursor).orElse(null);
		}
	}

	private static AcademicException parentInvalid(String message) {
		return new AcademicException(AcademicErrorCode.TASK_PARENT_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}

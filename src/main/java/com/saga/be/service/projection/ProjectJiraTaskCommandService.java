package com.saga.be.service.projection;

import com.saga.be.dto.JiraWriteIncompleteDetails;
import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.CreatedIssue;
import com.saga.be.integration.jira.JiraIssueWriteClient.EstimationInfo;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile("!test")
public class ProjectJiraTaskCommandService {

	private final ProjectDataAuthorization authorization;
	private final JiraIntegrationRepository jiraIntegrations;
	private final TaskRepository tasks;
	private final TaskWorkSessionRepository workSessions;
	private final ContributionConfirmationRepository confirmations;
	private final TaskGitCommitLinkRepository links;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraWrite;
	private final JiraTaskProjectionService projection;
	private final ProjectRealtimePublisher realtime;
	private final TaskHierarchyService hierarchy;
	private final TransactionTemplate writes;

	public ProjectJiraTaskCommandService(
			ProjectDataAuthorization authorization,
			JiraIntegrationRepository jiraIntegrations,
			TaskRepository tasks,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations,
			TaskGitCommitLinkRepository links,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraWrite,
			JiraTaskProjectionService projection,
			ProjectRealtimePublisher realtime,
			TaskHierarchyService hierarchy,
			PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.jiraIntegrations = jiraIntegrations;
		this.tasks = tasks;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
		this.links = links;
		this.tokens = tokens;
		this.jiraWrite = jiraWrite;
		this.projection = projection;
		this.realtime = realtime;
		this.hierarchy = hierarchy;
		this.writes = new TransactionTemplate(transactionManager);
	}

	public ProjectTaskOptionsResponse options(UUID userId, UUID projectId, UUID jiraIntegrationId) {
		authorization.requireReader(userId, projectId);
		JiraIntegration integration = resolveJiraForCreate(projectId, jiraIntegrationId);
		return optionsFor(integration);
	}

	/** Path-scoped options: always uses the named integration (no singular omission). */
	public ProjectTaskOptionsResponse optionsForIntegration(UUID userId, UUID projectId, UUID jiraIntegrationId) {
		authorization.requireReader(userId, projectId);
		JiraIntegration integration = jiraIntegrations
				.findByIdAndProject_Id(jiraIntegrationId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Jira source was not found for this project."));
		return optionsFor(requireUsableJira(integration));
	}

	private ProjectTaskOptionsResponse optionsFor(JiraIntegration integration) {
		String access = tokens.accessToken(integration);
		EstimationInfo estimation = jiraWrite.boardEstimationCapability(
				access, integration.getCloudId(), integration.getJiraBoardId());
		List<ProjectTaskOptionsResponse.IssueTypeOption> issueTypes = jiraWrite
				.listProjectIssueTypes(access, integration.getCloudId(), integration.getJiraProjectId())
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.IssueTypeOption(item.id(), item.name(), item.description()))
				.toList();
		List<ProjectTaskOptionsResponse.PriorityOption> priorities = jiraWrite
				.listPriorities(access, integration.getCloudId())
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.PriorityOption(item.id(), item.name()))
				.toList();
		String projectRef = integration.getProjectKey() != null ? integration.getProjectKey() : integration.getJiraProjectId();
		List<ProjectTaskOptionsResponse.AssignableUserOption> users = jiraWrite
				.listAssignableUsers(access, integration.getCloudId(), projectRef, 50)
				.stream()
				.map(item -> new ProjectTaskOptionsResponse.AssignableUserOption(item.accountId(), item.displayName()))
				.toList();
		List<ProjectTaskOptionsResponse.SprintOption> sprints = List.of();
		if (integration.getJiraBoardId() != null && !integration.getJiraBoardId().isBlank()) {
			sprints = jiraWrite.listBoardSprints(access, integration.getCloudId(), integration.getJiraBoardId()).stream()
					.filter(item -> item != null && item.id() != null)
					.map(item -> new ProjectTaskOptionsResponse.SprintOption(
							String.valueOf(item.id()), item.name(), item.state()))
					.toList();
		}
		return new ProjectTaskOptionsResponse(
				issueTypes,
				priorities,
				users,
				new ProjectTaskOptionsResponse.EstimationOption(
						estimation.supported(), estimation.fieldId(), estimation.fieldName()),
				sprints);
	}

	public ProjectTaskResponse create(UUID userId, UUID projectId, CreateProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		JiraIntegration integration = resolveJiraForCreate(projectId, request.jiraIntegrationId());
		UUID nativeParentId = request.parentTaskId();
		if (nativeParentId != null) {
			hierarchy.validateAssignable(projectId, null, nativeParentId);
		}
		String jiraParentIssueId = resolveJiraParentIssueId(projectId, integration, request.jiraParentTaskId());
		String access = tokens.accessToken(integration);

		CreatedIssue created = jiraParentIssueId == null ? jiraWrite.createIssue(
				access,
				integration.getCloudId(),
				integration.getJiraProjectId(),
				request.summary(),
				request.description(),
				request.issueTypeId(),
				request.assigneeAccountId(),
				request.priorityId(),
				null,
				request.labels(),
				request.dueDate(),
				request.startDate()) : jiraWrite.createIssue(
				access, integration.getCloudId(), integration.getJiraProjectId(), request.summary(), request.description(),
				request.issueTypeId(), request.assigneeAccountId(), request.priorityId(), null, request.labels(),
				request.dueDate(), request.startDate(), jiraParentIssueId);

		boolean secondaryFailed = false;
		if (request.storyPoints() != null) {
			try {
				jiraWrite.setIssueEstimation(
						access,
						integration.getCloudId(),
						integration.getJiraBoardId(),
						created.id(),
						request.storyPoints());
			} catch (IntegrationException ex) {
				secondaryFailed = true;
			}
		}
		String sprintId = request.resolvedSprintId();
		if (sprintId != null) {
			try {
				jiraWrite.moveIssuesToSprint(access, integration.getCloudId(), sprintId, List.of(created.id()));
			} catch (IntegrationException ex) {
				secondaryFailed = true;
			}
		}

		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), created.id());
		AtomicBoolean nativeParentFailed = new AtomicBoolean(false);
		Task saved = writes.execute(status -> {
			if (nativeParentId != null) {
				hierarchy.acquireHierarchyMutationLock(projectId);
			}
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			if (nativeParentId != null) {
				row = applyParentAfterProviderSuccess(projectId, row, nativeParentId, nativeParentFailed);
			}
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintId != null) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		if (nativeParentFailed.get()) {
			throw nativeParentIncomplete(true, saved);
		}
		if (secondaryFailed) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
					HttpStatus.BAD_GATEWAY,
					"Jira issue was created but a secondary field update failed. Local state reflects provider truth; retry the failed field.");
		}
		return ProjectProjectionReadService.toTask(saved, 0L);
	}

	public ProjectTaskResponse patch(UUID userId, UUID projectId, UUID taskId, PatchProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		if (Boolean.TRUE.equals(request.clearParent()) && request.parentTaskId() != null) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"clearParent cannot be combined with parentTaskId.");
		}
		boolean nativeTouched = request.touchesNativeParent();
		boolean providerTouched = request.touchesProviderFields();
		UUID nativeParentId = Boolean.TRUE.equals(request.clearParent()) ? null : request.parentTaskId();
		if (nativeTouched && !providerTouched) {
			Task task = requireTask(projectId, taskId);
			Task saved = hierarchy.mutateParent(projectId, task.getId(), nativeParentId);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, saved.getId().toString());
			return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
		}
		if (nativeTouched) {
			requireTask(projectId, taskId);
			hierarchy.validateAssignable(projectId, taskId, nativeParentId);
		}
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);

		Map<String, Object> fields = new HashMap<>();
		if (request.summary() != null) {
			fields.put("summary", request.summary());
		}
		if (request.description() != null) {
			Map<String, Object> doc = Map.of(
					"type",
					"doc",
					"version",
					1,
					"content",
					List.of(Map.of(
							"type",
							"paragraph",
							"content",
							List.of(Map.of("type", "text", "text", request.description())))));
			fields.put("description", doc);
		}
		if (request.issueTypeId() != null && !request.issueTypeId().isBlank()) {
			fields.put("issuetype", Map.of("id", request.issueTypeId()));
		}
		if (Boolean.TRUE.equals(request.clearAssignee())) {
			fields.put("assignee", null);
		} else if (request.assigneeAccountId() != null) {
			fields.put(
					"assignee",
					request.assigneeAccountId().isBlank()
							? null
							: Map.of("accountId", request.assigneeAccountId()));
		}
		if (request.priorityId() != null && !request.priorityId().isBlank()) {
			fields.put("priority", Map.of("id", request.priorityId()));
		}
		// PATCH semantics: labels omitted (null) -> preserve Jira's current labels (do not send the
		// field at all). Explicitly [] -> clears all labels. A non-empty list -> replaces with
		// exactly that list. "labels" is a standard Jira system field, always on the edit screen
		// when present, so no dynamic field id resolution is needed here.
		if (request.labels() != null) {
			fields.put("labels", normalizedLabels(request.labels()));
		}
		// PATCH semantics (mirrors assignee/clearAssignee above): omitted (dueDate=null,
		// clearDueDate not true) -> preserve. clearDueDate=true -> explicit clear (Jira standard
		// "duedate" field accepts null to clear it). A value -> set to exactly that date. A bare
		// null dueDate is never treated as an accidental clear.
		if (Boolean.TRUE.equals(request.clearDueDate())) {
			fields.put("duedate", null);
		} else if (request.dueDate() != null) {
			fields.put("duedate", request.dueDate().toString());
		}
		// Same PATCH semantics as dueDate/clearDueDate, but Start Date has no standard field key --
		// the custom field id is resolved dynamically (cached per cloud), and only when this PATCH
		// actually touches Start Date, never on every patch call. Undiscoverable/ambiguous on this
		// site -> controlled JIRA_FIELD_INVALID, never a guessed id. clearStartDate=true wins over
		// a simultaneous startDate value, matching clearDueDate/dueDate above.
		boolean touchesStartDate = Boolean.TRUE.equals(request.clearStartDate()) || request.startDate() != null;
		if (touchesStartDate) {
			String startField = jiraWrite.requireStartDateFieldId(access, integration.getCloudId());
			if (Boolean.TRUE.equals(request.clearStartDate())) {
				fields.put(startField, null);
			} else {
				fields.put(startField, request.startDate().toString());
			}
		}
		if (!fields.isEmpty()) {
			jiraWrite.updateIssueFields(access, integration.getCloudId(), issueRef, fields);
		}
		if (request.storyPoints() != null) {
			jiraWrite.setIssueEstimation(
					access, integration.getCloudId(), integration.getJiraBoardId(), issueRef, request.storyPoints());
		}

		boolean sprintChanged = false;
		if (Boolean.TRUE.equals(request.moveToBacklog())) {
			requireBoard(integration);
			jiraWrite.moveIssuesToBacklog(
					access, integration.getCloudId(), integration.getJiraBoardId(), List.of(issueRef));
			sprintChanged = true;
		} else if (request.sprintExternalId() != null && !request.sprintExternalId().isBlank()) {
			jiraWrite.moveIssuesToSprint(
					access, integration.getCloudId(), request.sprintExternalId(), List.of(issueRef));
			sprintChanged = true;
		}

		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		boolean sprintMembershipChanged = sprintChanged;
		boolean persistNativeParent = nativeTouched;
		UUID persistParentId = nativeParentId;
		AtomicBoolean nativeParentFailed = new AtomicBoolean(false);
		Task saved = writes.execute(status -> {
			if (persistNativeParent) {
				hierarchy.acquireHierarchyMutationLock(projectId);
			}
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			if (persistNativeParent) {
				row = applyParentAfterProviderSuccess(projectId, row, persistParentId, nativeParentFailed);
			}
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintMembershipChanged) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		if (nativeParentFailed.get()) {
			throw nativeParentIncomplete(false, saved);
		}
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public ProjectTaskResponse moveSprint(
			UUID userId, UUID projectId, UUID taskId, PutProjectTaskSprintRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);
		if (request == null || request.sprintId() == null) {
			requireBoard(integration);
			jiraWrite.moveIssuesToBacklog(
					access, integration.getCloudId(), integration.getJiraBoardId(), List.of(issueRef));
		} else {
			jiraWrite.moveIssuesToSprint(
					access, integration.getCloudId(), String.valueOf(request.sprintId()), List.of(issueRef));
		}
		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			return row;
		});
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public ProjectTaskResponse transition(
			UUID userId, UUID projectId, UUID taskId, TransitionProjectTaskRequest request) {
		authorization.requireStudentLeader(userId, projectId);
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		String access = tokens.accessToken(integration);
		String issueRef = issueRef(task);
		transitionInternal(
				access, integration.getCloudId(), issueRef, request.transitionId(), request.targetStatusId());
		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), issueRef);
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			return row;
		});
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()));
	}

	public void delete(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireStudentLeader(userId, projectId);
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		if (workSessions.existsByTask_Id(taskId) || confirmations.existsByTask_Id(taskId)) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_EVIDENCE,
					HttpStatus.CONFLICT,
					"Cannot delete Jira issue while work sessions or contribution confirmations exist for this task.");
		}
		hierarchy.assertDeletableWithoutActiveChildren(projectId, taskId);
		String access = tokens.accessToken(integration);
		String externalId = task.getExternalId();
		jiraWrite.deleteIssue(access, integration.getCloudId(), issueRef(task));
		writes.executeWithoutResult(status -> {
			projection.softDelete(integration, externalId, LocalDateTime.now());
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, taskId.toString());
			realtime.publish(ProjectRealtimeEventType.TASK_LINKS_CHANGED, projectId, taskId.toString());
		});
	}

	public List<TransitionOption> listTransitions(UUID userId, UUID projectId, UUID taskId) {
		authorization.requireStudentLeader(userId, projectId);
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		String access = tokens.accessToken(integration);
		return jiraWrite.listTransitions(access, integration.getCloudId(), issueRef(task));
	}

	private void transitionInternal(
			String access, String cloudId, String issueRef, String transitionId, String targetStatusId) {
		String resolved = transitionId;
		if (resolved == null || resolved.isBlank()) {
			if (targetStatusId == null || targetStatusId.isBlank()) {
				throw new IntegrationException(
						IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE,
						HttpStatus.BAD_REQUEST,
						"transitionId or targetStatusId is required.");
			}
			resolved = jiraWrite.listTransitions(access, cloudId, issueRef).stream()
					.filter(item -> targetStatusId.equals(item.toStatusId()))
					.map(TransitionOption::id)
					.findFirst()
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.JIRA_TRANSITION_UNAVAILABLE,
							HttpStatus.CONFLICT,
							"No Jira transition is available for the requested status."));
		}
		jiraWrite.transitionIssue(access, cloudId, issueRef, resolved);
	}

	/**
	 * Create/options source selection: explicit id when provided; otherwise singular project source
	 * only. Never picks among multiple sources.
	 */
	private JiraIntegration resolveJiraForCreate(UUID projectId, UUID jiraIntegrationId) {
		if (jiraIntegrationId != null) {
			JiraIntegration integration = jiraIntegrations
					.findByIdAndProject_Id(jiraIntegrationId, projectId)
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
							HttpStatus.NOT_FOUND,
							"Jira source was not found for this project."));
			return requireUsableJira(integration);
		}
		List<JiraIntegration> sources = jiraIntegrations.findAllByProject_Id(projectId);
		if (sources == null || sources.isEmpty()) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_REVOKED, HttpStatus.BAD_REQUEST, "Jira is not connected.");
		}
		if (sources.size() > 1) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SOURCE_REQUIRED,
					HttpStatus.CONFLICT,
					"jiraIntegrationId is required when the project has multiple Jira sources.");
		}
		return requireUsableJira(sources.get(0));
	}

	/**
	 * Existing-task mutations always use the task's own provenance. REVOKED sources fail closed —
	 * never redirect to another project Jira.
	 */
	private JiraIntegration requireJiraForTask(UUID projectId, Task task) {
		JiraIntegration linked = task.getJiraIntegration();
		if (linked == null || linked.getId() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Task has no Jira source provenance.");
		}
		JiraIntegration integration = jiraIntegrations
				.findByIdAndProject_Id(linked.getId(), projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Jira source was not found for this project."));
		return requireUsableJira(integration);
	}

	private JiraIntegration requireUsableJira(JiraIntegration integration) {
		if (integration.getConnectionStatus() != IntegrationStatus.ACTIVE) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SOURCE_NOT_ACTIVE,
					HttpStatus.BAD_REQUEST,
					"Jira source is not active.");
		}
		if (integration.getCloudId() == null
				|| integration.getJiraProjectId() == null
				|| integration.getProjectKey() == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_UNAVAILABLE,
					HttpStatus.BAD_REQUEST,
					"Jira integration is incomplete.");
		}
		return integration;
	}

	private void requireBoard(JiraIntegration integration) {
		if (integration.getJiraBoardId() == null || integration.getJiraBoardId().isBlank()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_SPRINT_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira board is required for backlog moves.");
		}
	}

	private Task requireTask(UUID projectId, UUID taskId) {
		return tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
	}

	private String resolveJiraParentIssueId(UUID projectId, JiraIntegration target, UUID jiraParentTaskId) {
		if (jiraParentTaskId == null) return null;
		Task parent = tasks.findByIdAndProject_IdAndDeletedAtIsNull(jiraParentTaskId, projectId)
				.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.JIRA_PARENT_TASK_NOT_FOUND,
						HttpStatus.NOT_FOUND, "Jira provider parent task was not found for this project."));
		if (parent.getJiraIntegration() == null || parent.getJiraIntegration().getId() == null
				|| !target.getId().equals(parent.getJiraIntegration().getId())) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_PARENT_SOURCE_MISMATCH, HttpStatus.BAD_REQUEST,
					"Jira provider parent must belong to the selected Jira source.");
		}
		if (parent.getExternalId() == null || parent.getExternalId().isBlank()) {
			throw new IntegrationException(IntegrationErrorCode.JIRA_PARENT_PROVIDER_ID_MISSING, HttpStatus.BAD_REQUEST,
					"Jira provider parent has no canonical Jira issue id.");
		}
		return parent.getExternalId();
	}

	private long linkedCount(UUID projectId, UUID taskId) {
		for (Object[] row : links.countLinksByProjectGrouped(projectId)) {
			if (taskId.equals(row[0])) {
				return (Long) row[1];
			}
		}
		return 0L;
	}

	private static String issueRef(Task task) {
		if (task.getExternalId() != null && !task.getExternalId().isBlank()) {
			return task.getExternalId();
		}
		return task.getExternalKey();
	}

	/**
	 * Conservative, Jira-compatible normalization: drop null/blank entries only. Does not dedupe or
	 * otherwise restrict label values -- Jira itself is authoritative on what a valid label is, and
	 * no other list-valued field in this DTO layer normalizes beyond null-safety.
	 */
	private static List<String> normalizedLabels(List<String> labels) {
		return labels.stream().filter(label -> label != null && !label.isBlank()).toList();
	}

	/**
	 * Jira already succeeded; persist provider truth even if native parent cannot be assigned.
	 * Catching {@code TASK_PARENT_INVALID} inside the transaction lets {@code upsertOne} commit.
	 * Callers then surface {@link IntegrationErrorCode#JIRA_WRITE_INCOMPLETE} (no Jira retry).
	 */
	private Task applyParentAfterProviderSuccess(
			UUID projectId, Task row, UUID parentId, AtomicBoolean nativeParentFailed) {
		try {
			return hierarchy.applyParent(projectId, row.getId(), parentId);
		} catch (AcademicException ex) {
			if (ex.getCode() != AcademicErrorCode.TASK_PARENT_INVALID) {
				throw ex;
			}
			nativeParentFailed.set(true);
			return row;
		}
	}

	private static IntegrationException nativeParentIncomplete(boolean created, Task saved) {
		return new IntegrationException(
				IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
				HttpStatus.BAD_GATEWAY,
				created
						? "Jira issue was created but native parent could not be assigned. Local Task already exists and reflects provider truth; do not retry create. Retry the parent assignment with PATCH."
						: "Jira issue was updated but native parent could not be assigned. Local state reflects provider truth; retry the parent assignment with PATCH.",
				JiraWriteIncompleteDetails.nativeParentNotApplied(saved.getId()));
	}
}

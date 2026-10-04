package com.saga.be.service.projection;

import com.saga.be.dto.integration.failover.TaskMigrationSummary;
import com.saga.be.dto.project.CreateProjectTaskRequest;
import com.saga.be.dto.project.PatchProjectTaskRequest;
import com.saga.be.dto.project.ProjectTaskOptionsResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.PutProjectTaskSprintRequest;
import com.saga.be.dto.project.TransitionProjectTaskRequest;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.CreatedIssue;
import com.saga.be.integration.jira.JiraIssueWriteClient.EstimationInfo;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.integration.jira.JiraIssueWriteClient.TransitionOption;
import com.saga.be.integration.jira.JiraOAuthClient.IssueSummary;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.ContributionConfirmationRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.service.contribution.SagaTaskLabelPolicy;
import com.saga.be.service.contribution.TaskEvidencePolicy;
import com.saga.be.service.contribution.TaskLabelParser;
import com.saga.be.service.task.TaskSchedulePolicy;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraWrite;
	private final JiraTaskProjectionService projection;
	private final ProjectRealtimePublisher realtime;
	private final TaskHierarchyService hierarchy;
	private final IdentityMapRepository identities;
	private final SprintRepository sprints;
	private final TransactionTemplate writes;

	public ProjectJiraTaskCommandService(
			ProjectDataAuthorization authorization,
			JiraIntegrationRepository jiraIntegrations,
			TaskRepository tasks,
			TaskWorkSessionRepository workSessions,
			ContributionConfirmationRepository confirmations,
			TaskGitCommitLinkRepository links,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraWrite,
			JiraTaskProjectionService projection,
			ProjectRealtimePublisher realtime,
			TaskHierarchyService hierarchy,
			IdentityMapRepository identities,
			SprintRepository sprints,
			PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.jiraIntegrations = jiraIntegrations;
		this.tasks = tasks;
		this.workSessions = workSessions;
		this.confirmations = confirmations;
		this.links = links;
		this.files = files;
		this.webLinks = webLinks;
		this.tokens = tokens;
		this.jiraWrite = jiraWrite;
		this.projection = projection;
		this.realtime = realtime;
		this.hierarchy = hierarchy;
		this.identities = identities;
		this.sprints = sprints;
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
				.map(item -> new ProjectTaskOptionsResponse.IssueTypeOption(
						item.id(),
						item.name(),
						item.description(),
						item.subtask(),
						item.hierarchyLevel(),
						TaskIssueTypePolicy.level(item).name()))
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
				sprints,
				SagaTaskLabelPolicy.ALLOWED);
	}

	/**
	 * Jira parent candidates for an item of type {@code childIssueTypeId} (the type being created, or
	 * the current type of the item being edited). The level comes from Jira's own issue-type metadata
	 * -- a client-declared level is never trusted: STANDARD -> Epics, SUBTASK -> standard items,
	 * EPIC / ABOVE_EPIC -> none. Same Jira source only.
	 */
	public com.saga.be.dto.project.TaskParentOptionsResponse jiraParentOptions(
			UUID userId,
			UUID projectId,
			UUID jiraIntegrationId,
			String childIssueTypeId,
			String q,
			int page,
			int size,
			UUID excludeTaskId) {
		authorization.requireReader(userId, projectId);
		if (jiraIntegrationId == null || childIssueTypeId == null || childIssueTypeId.isBlank()) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"childIssueTypeId and jiraIntegrationId are required together.");
		}
		JiraIntegration integration = requireUsableJira(jiraIntegrations
				.findByIdAndProject_Id(jiraIntegrationId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.JIRA_SOURCE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Jira source was not found for this project.")));
		String access = tokens.accessToken(integration);
		TaskIssueTypePolicy.Level level =
				TaskIssueTypePolicy.level(requireProjectIssueType(access, integration, childIssueTypeId.trim()));
		if (level == TaskIssueTypePolicy.Level.UNKNOWN) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_ISSUE_TYPE_INVALID,
					HttpStatus.BAD_REQUEST,
					"Jira did not report the hierarchy level of this issue type.");
		}
		return hierarchy.listJiraParentOptions(projectId, integration.getId(), level.name(), q, page, size, excludeTaskId);
	}

	public ProjectTaskResponse create(UUID userId, UUID projectId, CreateProjectTaskRequest request) {
		RoleInTeam role = authorization.requireStudentTeamMember(userId, projectId);
		List<String> labels = SagaTaskLabelPolicy.forCreate(request.labels());
		requirePersonalIntegrations(userId, labels);
		String assigneeAccountId = role == RoleInTeam.LEADER
				? request.assigneeAccountId()
				: memberSelfAssignee(userId, request.assigneeAccountId());
		JiraIntegration integration = resolveJiraForCreate(projectId, request.jiraIntegrationId());
		// One parent relation only: the Jira parent. parentTaskId is the deprecated name of the same
		// picker. Local checks first: a wrong-source parent is refused before any Jira call.
		UUID parentRef = DeprecatedParentFields.resolve(
				request.jiraParentTaskId(), request.parentTaskId(), false, false, "POST /tasks");
		Task jiraParent = parentRef == null ? null : requireJiraParent(projectId, integration, parentRef);
		boolean typeChosen = request.issueTypeId() != null && !request.issueTypeId().isBlank();
		String access = typeChosen || jiraParent != null ? tokens.accessToken(integration) : null;
		TaskIssueTypePolicy.Level level = typeChosen
				? TaskIssueTypePolicy.level(requireProjectIssueType(access, integration, request.issueTypeId()))
				: TaskIssueTypePolicy.Level.STANDARD;
		boolean subtask = level == TaskIssueTypePolicy.Level.SUBTASK;
		TaskIssueTypePolicy.requireParent(
				level,
				jiraParent == null
						? null
						: TaskIssueTypePolicy.level(jiraWrite.getIssueType(access, integration.getCloudId(), jiraParent.getExternalId())));
		if (subtask) {
			requireSubtaskPercent(integration.getId(), jiraParent.getExternalId(), null, request.storyPoints());
		}
		// A Subtask always runs in its parent's sprint (Jira does not move subtasks on their own), so
		// a sprint picked in the form is not applied to it and its dates are checked against the
		// parent's sprint instead.
		String sprintId = subtask ? null : request.resolvedSprintId();
		if (request.startDate() != null || request.dueDate() != null) {
			Sprint targetSprint = subtask
					? parentSprint(jiraParent)
					: sprintId == null
							? null
							: sprints.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), sprintId).orElse(null);
			requireValidSchedule(request.startDate(), request.dueDate(), targetSprint, true, true);
		}
		String jiraParentIssueId = jiraParent == null ? null : jiraParent.getExternalId();
		if (access == null) {
			access = tokens.accessToken(integration);
		}

		CreatedIssue created = jiraParentIssueId == null ? jiraWrite.createIssue(
				access,
				integration.getCloudId(),
				integration.getJiraProjectId(),
				request.summary(),
				request.description(),
				request.issueTypeId(),
				assigneeAccountId,
				request.priorityId(),
				null,
				labels,
				request.dueDate(),
				request.startDate()) : jiraWrite.createIssue(
				access, integration.getCloudId(), integration.getJiraProjectId(), request.summary(), request.description(),
				request.issueTypeId(), assigneeAccountId, request.priorityId(), null, labels,
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
		if (sprintId != null) {
			try {
				jiraWrite.moveIssuesToSprint(access, integration.getCloudId(), sprintId, List.of(created.id()));
			} catch (IntegrationException ex) {
				secondaryFailed = true;
			}
		}

		IssueSummary canonical = jiraWrite.getIssue(access, integration.getCloudId(), created.id());
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintId != null) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		if (secondaryFailed) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_WRITE_INCOMPLETE,
					HttpStatus.BAD_GATEWAY,
					"Jira issue was created but a secondary field update failed. Local state reflects provider truth; retry the failed field.");
		}
		return toProjectedTask(projectId, saved);
	}

	public ProjectTaskResponse patch(UUID userId, UUID projectId, UUID taskId, PatchProjectTaskRequest request) {
		RoleInTeam role = authorization.requireStudentTeamMember(userId, projectId);
		if (role != RoleInTeam.LEADER) {
			requireMemberKeepsOwnership(userId, request);
		}
		requireLeaderOrAssignee(role, userId, projectId, taskId);
		DeprecatedParentFields.resolve(
				request.jiraParentTaskId(),
				request.parentTaskId(),
				Boolean.TRUE.equals(request.clearJiraParent()),
				Boolean.TRUE.equals(request.clearParent()),
				"PATCH /tasks/{taskId}");
		Task task = requireTask(projectId, taskId);
		// Local checks first: a parent of another project/source is refused before any Jira call.
		UUID parentRef = request.requestedJiraParentId();
		Task newParent = parentRef == null ? null : requireJiraParent(projectId, task.getJiraIntegration(), parentRef);
		if (newParent != null && newParent.getId().equals(task.getId())) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_PARENT_TYPE_INVALID, HttpStatus.BAD_REQUEST, "A task cannot be its own parent.");
		}
		List<String> labels = SagaTaskLabelPolicy.forPatch(TaskLabelParser.parse(task.getLabelsJson()), request.labels());
		JiraIntegration integration = requireJiraForTask(projectId, task);
		requirePatchKeepsValidSchedule(task, integration, request);
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
		IssueTypeOption current = null;
		if (request.issueTypeId() != null && !request.issueTypeId().isBlank()) {
			current = jiraWrite.getIssueType(access, integration.getCloudId(), issueRef);
			// Re-sending the current type is not a change (the edit form may send it back unchanged).
			if (!request.issueTypeId().equals(current.id())) {
				TaskIssueTypePolicy.requireEditable(
						current, requireProjectIssueType(access, integration, request.issueTypeId()));
				fields.put("issuetype", Map.of("id", request.issueTypeId()));
			}
		}
		if (request.touchesJiraParent()) {
			if (current == null) {
				current = jiraWrite.getIssueType(access, integration.getCloudId(), issueRef);
			}
			if (TaskIssueTypePolicy.level(current) != TaskIssueTypePolicy.Level.STANDARD) {
				throw new IntegrationException(
						IntegrationErrorCode.TASK_PARENT_TYPE_INVALID,
						HttpStatus.BAD_REQUEST,
						"Only a Task, Story, Feature or Bug can change its Epic in SAGA. An Epic or a Subtask keeps its parent "
								+ "here; change it in Jira and sync.");
			}
			if (newParent == null) {
				// Removes the Epic: the item becomes a top-level work item of the project.
				fields.put("parent", null);
			} else {
				TaskIssueTypePolicy.requireParent(
						TaskIssueTypePolicy.Level.STANDARD,
						TaskIssueTypePolicy.level(
								jiraWrite.getIssueType(access, integration.getCloudId(), newParent.getExternalId())));
				fields.put("parent", Map.of("id", newParent.getExternalId()));
			}
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
		// exactly that list (already vetted by SagaTaskLabelPolicy: existing labels kept, new ones
		// must be SAGA markers). "labels" is a standard Jira system field, always on the edit screen
		// when present, so no dynamic field id resolution is needed here.
		if (labels != null) {
			fields.put("labels", labels);
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
			if ("SUBTASK".equals(task.getIssueTypeLevel())) {
				requireSubtaskPercent(
						integration.getId(), task.getParentExternalId(), task.getId(), request.storyPoints());
			}
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
		Task saved = writes.execute(status -> {
			Task row = projection.upsertOne(integration, integration.getProjectKey(), canonical);
			realtime.publish(ProjectRealtimeEventType.TASKS_CHANGED, projectId, row.getId().toString());
			if (sprintMembershipChanged) {
				realtime.publish(ProjectRealtimeEventType.SPRINTS_CHANGED, projectId);
			}
			return row;
		});
		return toProjectedTask(projectId, saved);
	}

	public ProjectTaskResponse moveSprint(
			UUID userId, UUID projectId, UUID taskId, PutProjectTaskSprintRequest request) {
		requireLeaderOrAssignee(authorization.requireStudentTeamMember(userId, projectId), userId, projectId, taskId);
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
		return toProjectedTask(projectId, saved);
	}

	public ProjectTaskResponse transition(
			UUID userId, UUID projectId, UUID taskId, TransitionProjectTaskRequest request) {
		requireLeaderOrAssignee(authorization.requireStudentTeamMember(userId, projectId), userId, projectId, taskId);
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
		return toProjectedTask(projectId, saved);
	}

	public void delete(UUID userId, UUID projectId, UUID taskId) {
		requireLeaderOrAssignee(authorization.requireStudentTeamMember(userId, projectId), userId, projectId, taskId);
		Task task = requireTask(projectId, taskId);
		JiraIntegration integration = requireJiraForTask(projectId, task);
		if (workSessions.existsByTask_Id(taskId) || confirmations.existsByTask_Id(taskId)) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_EVIDENCE,
					HttpStatus.CONFLICT,
					"Cannot delete Jira issue while work sessions or contribution confirmations exist for this task.");
		}
		if (task.getExternalId() != null
				&& tasks.existsByJiraIntegration_IdAndParentExternalIdAndDeletedAtIsNull(integration.getId(), task.getExternalId())) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_DELETE_BLOCKED_BY_SUBTASKS,
					HttpStatus.CONFLICT,
					"Cannot delete a task that still has child work items in Jira (subtasks, or items under this Epic).");
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
		requireLeaderOrAssignee(authorization.requireStudentTeamMember(userId, projectId), userId, projectId, taskId);
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

	/**
	 * Leader may change any task; a Member only a task whose assignee is their own SAGA account.
	 * Checked with a scalar query (not the lazy assignee association) since open-in-view is off.
	 */
	private void requireLeaderOrAssignee(RoleInTeam role, UUID userId, UUID projectId, UUID taskId) {
		if (role == RoleInTeam.LEADER) {
			return;
		}
		requireTask(projectId, taskId);
		if (!tasks.existsByIdAndAssigneeStudent_UserAccount_Id(taskId, userId)) {
			throw new IntegrationException(
					IntegrationErrorCode.TASK_NOT_ASSIGNED_TO_YOU,
					HttpStatus.FORBIDDEN,
					"Members can only change tasks assigned to them.");
		}
	}

	/** A Member owns their task fully, except handing it away: unassign/reassign stays with the Leader. */
	private void requireMemberKeepsOwnership(UUID userId, PatchProjectTaskRequest request) {
		String assignee = request.assigneeAccountId();
		boolean handsAway = Boolean.TRUE.equals(request.clearAssignee())
				|| (assignee != null && (assignee.isBlank() || !memberJiraAccountIds(userId).contains(assignee)));
		if (handsAway) {
			throw new IntegrationException(
					IntegrationErrorCode.NOT_TEAM_LEADER,
					HttpStatus.FORBIDDEN,
					"Only the Team Leader can unassign a task or assign it to someone else.");
		}
	}

	private void requireSubtaskPercent(
			UUID jiraIntegrationId, String parentExternalId, UUID excludeTaskId, Integer storyPoints) {
		int used = 0;
		if (jiraIntegrationId != null && parentExternalId != null && !parentExternalId.isBlank()) {
			for (Object[] row : tasks.findSubtaskStoryPoints(jiraIntegrationId, parentExternalId)) {
				UUID id = (UUID) row[0];
				if (excludeTaskId != null && excludeTaskId.equals(id)) {
					continue;
				}
				Integer points = (Integer) row[1];
				if (com.saga.be.service.contribution.SubtaskPercentPolicy.inRange(points)) {
					used += com.saga.be.service.contribution.SubtaskPercentPolicy.percent(points);
				}
			}
		}
		com.saga.be.service.contribution.SubtaskPercentPolicy.requireRoom(storyPoints, used);
	}

	/**
	 * A Member's new task is always theirs. The server owns that rule: a requested assignee that is
	 * one of the Member's own linked Jira accounts is kept, anything else (blank, or a client-side
	 * guess that picked someone else's account) is replaced by their own account rather than failing
	 * the create.
	 */
	private String memberSelfAssignee(UUID userId, String requestedAssignee) {
		List<String> own = memberJiraAccountIds(userId);
		if (own.isEmpty()) {
			throw new IntegrationException(
					IntegrationErrorCode.JIRA_ACCOUNT_NOT_LINKED_TO_CURRENT_USER,
					HttpStatus.FORBIDDEN,
					"Link your Jira account before creating tasks.");
		}
		if (requestedAssignee != null && own.contains(requestedAssignee)) {
			return requestedAssignee;
		}
		return own.get(0);
	}

	/**
	 * Any student (Leader or Member) creating a task must have linked their own Jira and GitHub
	 * accounts: without them the task cannot be attributed to them, nor their commits to the task.
	 * "Linked" is the same ACTIVE/VERIFIED/PENDING set GET /api/integrations/me reports.
	 */
	private void requirePersonalIntegrations(UUID userId, List<String> labels) {
		List<String> missing = new ArrayList<>();
		List<IntegrationProvider> required = TaskEvidencePolicy.requiresCommit(labels)
				? List.of(IntegrationProvider.JIRA, IntegrationProvider.GITHUB)
				: List.of(IntegrationProvider.JIRA);
		for (IntegrationProvider provider : required) {
			boolean linked = !identities
					.findFetchedByUserAccount_IdInAndProviderAndMappingStatusIn(
							List.of(userId), provider, JiraTaskProjectionService.ACTIVE_STATUSES)
					.isEmpty();
			if (!linked) {
				missing.add(provider.name());
			}
		}
		if (!missing.isEmpty()) {
			throw new IntegrationException(
					IntegrationErrorCode.PERSONAL_INTEGRATION_REQUIRED,
					HttpStatus.FORBIDDEN,
					"Link your " + String.join(" and ", missing) + " account(s) before creating tasks.",
					Map.of("missingProviders", missing));
		}
	}

	/**
	 * Only an edit that sets or clears a date is checked, against the sprint the task will be in after
	 * this edit, and only the date being set is held to the sprint: a task carried over from an earlier
	 * sprint keeps its old start date, and changing its due date must still work. Moving a task between
	 * sprints alone, or editing other fields of a task whose dates already drifted, is never blocked --
	 * those show up as {@code scheduleCheck} warnings instead.
	 */
	private void requirePatchKeepsValidSchedule(Task task, JiraIntegration integration, PatchProjectTaskRequest request) {
		boolean touchesDates = request.startDate() != null
				|| request.dueDate() != null
				|| Boolean.TRUE.equals(request.clearStartDate())
				|| Boolean.TRUE.equals(request.clearDueDate());
		if (!touchesDates) {
			return;
		}
		java.time.LocalDate start = Boolean.TRUE.equals(request.clearStartDate())
				? null
				: request.startDate() != null ? request.startDate() : localDate(task.getStartDate());
		java.time.LocalDate due = Boolean.TRUE.equals(request.clearDueDate())
				? null
				: request.dueDate() != null ? request.dueDate() : localDate(task.getDueDate());
		Sprint targetSprint;
		if (Boolean.TRUE.equals(request.moveToBacklog())) {
			targetSprint = null;
		} else if (request.sprintExternalId() != null && !request.sprintExternalId().isBlank()) {
			targetSprint = sprints
					.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), request.sprintExternalId().trim())
					.orElse(null);
		} else {
			// task.getSprint() is a lazy proxy and the session is already closed here: load it by id
			// (same as parentSprint) instead of touching the proxy.
			targetSprint = parentSprint(task);
		}
		requireValidSchedule(start, due, targetSprint, request.startDate() != null, request.dueDate() != null);
	}

	private static final java.time.format.DateTimeFormatter VI_DATE = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy");

	/**
	 * Start <= due always; against the sprint only the dates flagged as being set are checked: the start
	 * date must fall inside the sprint, the due date may run past its end but not end before it starts.
	 * A backlog task (no sprint) is only held to start <= due.
	 */
	private static void requireValidSchedule(
			java.time.LocalDate start, java.time.LocalDate due, Sprint sprint, boolean checkStart, boolean checkDue) {
		TaskSchedulePolicy.SprintWindow window = TaskSchedulePolicy.windowOf(sprint);
		List<TaskSchedulePolicy.Issue> issues = new java.util.ArrayList<>(TaskSchedulePolicy.issues(start, due, window));
		if (!checkStart) {
			issues.remove(TaskSchedulePolicy.Issue.START_BEFORE_SPRINT);
			issues.remove(TaskSchedulePolicy.Issue.START_AFTER_SPRINT);
		}
		if (!checkDue) {
			issues.remove(TaskSchedulePolicy.Issue.DUE_BEFORE_SPRINT);
		}
		if (issues.isEmpty()) {
			return;
		}
		if (issues.contains(TaskSchedulePolicy.Issue.START_AFTER_DUE)) {
			Map<String, Object> details = new java.util.LinkedHashMap<>();
			details.put("startDate", start);
			details.put("dueDate", due);
			throw new IntegrationException(
					IntegrationErrorCode.TASK_DATE_RANGE_INVALID,
					HttpStatus.BAD_REQUEST,
					"Ngày bắt đầu (" + start.format(VI_DATE) + ") không được sau hạn hoàn thành (" + due.format(VI_DATE) + ").",
					details);
		}
		Map<String, Object> details = new java.util.LinkedHashMap<>();
		details.put("issues", issues.stream().map(Enum::name).toList());
		details.put("sprintId", sprint.getId());
		details.put("sprintName", sprint.getName());
		details.put("sprintStartDate", window.start());
		details.put("sprintEndDate", window.end());
		String message;
		if (issues.contains(TaskSchedulePolicy.Issue.START_BEFORE_SPRINT) || issues.contains(TaskSchedulePolicy.Issue.START_AFTER_SPRINT)) {
			message = "Ngày bắt đầu phải nằm trong " + sprint.getName() + " (" + range(window)
					+ "). Hạn hoàn thành thì có thể sau ngày kết thúc sprint.";
		} else {
			message = "Hạn hoàn thành không được trước ngày bắt đầu của " + sprint.getName() + " ("
					+ window.start().format(VI_DATE) + ").";
		}
		throw new IntegrationException(IntegrationErrorCode.TASK_OUTSIDE_SPRINT, HttpStatus.BAD_REQUEST, message, details);
	}

	private static String range(TaskSchedulePolicy.SprintWindow window) {
		if (window.start() != null && window.end() != null) {
			return "từ " + window.start().format(VI_DATE) + " đến " + window.end().format(VI_DATE);
		}
		return window.start() != null ? "từ " + window.start().format(VI_DATE) : "đến " + window.end().format(VI_DATE);
	}

	private static java.time.LocalDate localDate(java.time.LocalDateTime value) {
		return value == null ? null : value.toLocalDate();
	}

	/** Same identity statuses the task projection uses to resolve a Jira assignee to a student. */
	private List<String> memberJiraAccountIds(UUID userId) {
		return identities
				.findFetchedByUserAccount_IdInAndProviderAndMappingStatusIn(
						List.of(userId), IntegrationProvider.JIRA, JiraTaskProjectionService.ACTIVE_STATUSES)
				.stream()
				.map(IdentityMap::getExternalAccountId)
				.filter(id -> id != null && !id.isBlank())
				.distinct()
				.toList();
	}

	private Task requireTask(UUID projectId, UUID taskId) {
		return tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Task was not found for this project."));
	}

	/** Loaded by id: the parent's sprint is a lazy association and may be read outside a transaction. */
	private Sprint parentSprint(Task parent) {
		Sprint sprint = parent.getSprint();
		return sprint == null || sprint.getId() == null ? null : sprints.findById(sprint.getId()).orElse(null);
	}

	/** The type from this Jira project's own issue types, else 400 TASK_ISSUE_TYPE_INVALID. */
	private IssueTypeOption requireProjectIssueType(String access, JiraIntegration integration, String issueTypeId) {
		return jiraWrite.listProjectIssueTypes(access, integration.getCloudId(), integration.getJiraProjectId()).stream()
				.filter(type -> type != null && issueTypeId.equals(type.id()))
				.findFirst()
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.TASK_ISSUE_TYPE_INVALID,
						HttpStatus.BAD_REQUEST,
						"This issue type is not available in the task's Jira project."));
	}

	private Task requireJiraParent(UUID projectId, JiraIntegration target, UUID jiraParentTaskId) {
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
		return parent;
	}

	private ProjectTaskResponse toProjectedTask(UUID projectId, Task saved) {
		long evidence = evidenceCount(saved.getId());
		long linked = linkedCount(projectId, saved.getId());
		return ProjectProjectionReadService.toTask(
				saved, linked, evidence, linked, evidence, null, TaskMigrationSummary.none(),
				ProjectProjectionReadService.repositoryParentLookup(tasks, projectId));
	}

	private long linkedCount(UUID projectId, UUID taskId) {
		for (Object[] row : links.countLinksByProjectGrouped(projectId)) {
			if (taskId.equals(row[0])) {
				return (Long) row[1];
			}
		}
		return 0L;
	}

	private long evidenceCount(UUID taskId) {
		return files.countByTask_Id(taskId) + webLinks.countByTask_Id(taskId);
	}

	private static String issueRef(Task task) {
		if (task.getExternalId() != null && !task.getExternalId().isBlank()) {
			return task.getExternalId();
		}
		return task.getExternalKey();
	}
}

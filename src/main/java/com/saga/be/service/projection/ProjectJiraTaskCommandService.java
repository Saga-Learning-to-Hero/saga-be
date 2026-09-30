package com.saga.be.service.projection;

import com.saga.be.dto.JiraWriteIncompleteDetails;
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
				sprints,
				SagaTaskLabelPolicy.ALLOWED);
	}

	public ProjectTaskResponse create(UUID userId, UUID projectId, CreateProjectTaskRequest request) {
		RoleInTeam role = authorization.requireStudentTeamMember(userId, projectId);
		List<String> labels = SagaTaskLabelPolicy.forCreate(request.labels());
		requirePersonalIntegrations(userId, labels);
		String assigneeAccountId = role == RoleInTeam.LEADER
				? request.assigneeAccountId()
				: memberSelfAssignee(userId, request.assigneeAccountId());
		JiraIntegration integration = resolveJiraForCreate(projectId, request.jiraIntegrationId());
		if (request.startDate() != null || request.dueDate() != null) {
			String sprintExternalId = request.resolvedSprintId();
			Sprint targetSprint = sprintExternalId == null
					? null
					: sprints.findByJiraIntegration_IdAndExternalSprintId(integration.getId(), sprintExternalId).orElse(null);
			requireValidSchedule(request.startDate(), request.dueDate(), targetSprint);
		}
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
		return ProjectProjectionReadService.toTask(saved, 0L, 0L);
	}

	public ProjectTaskResponse patch(UUID userId, UUID projectId, UUID taskId, PatchProjectTaskRequest request) {
		RoleInTeam role = authorization.requireStudentTeamMember(userId, projectId);
		if (role != RoleInTeam.LEADER) {
			requireMemberKeepsOwnership(userId, request);
		}
		requireLeaderOrAssignee(role, userId, projectId, taskId);
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
			return toProjectedTask(projectId, saved);
		}
		if (nativeTouched) {
			requireTask(projectId, taskId);
			hierarchy.validateAssignable(projectId, taskId, nativeParentId);
		}
		Task task = requireTask(projectId, taskId);
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
	 * this edit. Moving a task between sprints alone, or editing other fields of a task whose dates
	 * already drifted, is never blocked -- those show up as {@code scheduleCheck} warnings instead.
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
			targetSprint = task.getSprint();
		}
		requireValidSchedule(start, due, targetSprint);
	}

	private static void requireValidSchedule(java.time.LocalDate start, java.time.LocalDate due, Sprint sprint) {
		TaskSchedulePolicy.SprintWindow window = TaskSchedulePolicy.windowOf(sprint);
		List<TaskSchedulePolicy.Issue> issues = TaskSchedulePolicy.issues(start, due, window);
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
					"Task start date must not be after its due date.",
					details);
		}
		Map<String, Object> details = new java.util.LinkedHashMap<>();
		details.put("issues", issues.stream().map(Enum::name).toList());
		details.put("sprintId", sprint.getId());
		details.put("sprintName", sprint.getName());
		details.put("sprintStartDate", window.start());
		details.put("sprintEndDate", window.end());
		throw new IntegrationException(
				IntegrationErrorCode.TASK_OUTSIDE_SPRINT,
				HttpStatus.BAD_REQUEST,
				"Task dates must fall inside sprint \"" + sprint.getName() + "\" (" + window.start() + " to "
						+ window.end() + ").",
				details);
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

	private ProjectTaskResponse toProjectedTask(UUID projectId, Task saved) {
		return ProjectProjectionReadService.toTask(saved, linkedCount(projectId, saved.getId()), evidenceCount(saved.getId()));
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

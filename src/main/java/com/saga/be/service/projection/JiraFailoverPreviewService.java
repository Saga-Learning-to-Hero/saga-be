package com.saga.be.service.projection;

import com.saga.be.dto.integration.failover.JiraFailoverPreviewRequest;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.Counts;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.FieldMappingPreview;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.ItemClassification;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.ParentPreview;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.PlannedCopyPreview;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.PlannedSprintPlacement;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.PlannedStatusHandling;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.PreviewItem;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.Readiness;
import com.saga.be.dto.integration.failover.JiraFailoverPreviewResponse.TargetOptionsSummary;
import com.saga.be.entity.enums.IdentityMappingStatus;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.JiraFailoverItemStatus;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.JiraTaskFailoverItem;
import com.saga.be.entity.jira.Task;
import com.saga.be.integration.jira.JiraIssueWriteClient;
import com.saga.be.integration.jira.JiraIssueWriteClient.AssignableUserOption;
import com.saga.be.integration.jira.JiraIssueWriteClient.IssueTypeOption;
import com.saga.be.integration.jira.JiraIssueWriteClient.PriorityOption;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraFailoverSupersedeQueries;
import com.saga.be.repository.JiraTaskFailoverItemRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.service.contribution.TaskLabelParser;
import com.saga.be.service.projection.JiraFailoverValidationService.ValidatedFailoverTargets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Phase 4A unfinished-task failover preview. Loads source tasks and target provider metadata only.
 * Never calls {@code createIssue}/{@code updateIssue}/transition/delete and never uses source
 * credentials for HTTP. Paged inspection is not an execution snapshot.
 */
@Service
@Profile("!test")
public class JiraFailoverPreviewService {

	static final String BLOCKER_PROVIDER_SUBTASK_UNSUPPORTED = "PROVIDER_SUBTASK_UNSUPPORTED";
	static final String BLOCKER_ISSUE_TYPE_UNMAPPED = "ISSUE_TYPE_UNMAPPED";
	static final String WARNING_PRIORITY_UNMAPPED = "PRIORITY_UNMAPPED";
	static final String WARNING_ASSIGNEE_UNMAPPED = "ASSIGNEE_UNMAPPED";
	static final String WARNING_CONTRIBUTION_REQUIRES_SPRINT = "CONTRIBUTION_REQUIRES_SPRINT";
	static final String WARNING_PARENT_DONE = "PARENT_DONE";
	static final String ORDER_PARENT_ALSO_CANDIDATE = "ORDER_PARENT_BEFORE_CHILD";

	private static final Collection<IdentityMappingStatus> ACTIVE_IDENTITY_STATUSES =
			EnumSet.of(IdentityMappingStatus.ACTIVE, IdentityMappingStatus.VERIFIED, IdentityMappingStatus.PENDING);

	private static final int ASSIGNABLE_USER_CAP = 50;

	private final JiraFailoverValidationService validation;
	private final TaskRepository tasks;
	private final JiraTaskFailoverItemRepository failoverItems;
	private final IdentityMapRepository identities;
	private final JiraTeamTokenService tokens;
	private final JiraIssueWriteClient jiraWrite;

	public JiraFailoverPreviewService(
			JiraFailoverValidationService validation,
			TaskRepository tasks,
			JiraTaskFailoverItemRepository failoverItems,
			IdentityMapRepository identities,
			JiraTeamTokenService tokens,
			JiraIssueWriteClient jiraWrite) {
		this.validation = validation;
		this.tasks = tasks;
		this.failoverItems = failoverItems;
		this.identities = identities;
		this.tokens = tokens;
		this.jiraWrite = jiraWrite;
	}

	public JiraFailoverPreviewResponse preview(
			UUID userId, UUID projectId, UUID sourceIntegrationId, JiraFailoverPreviewRequest request) {
		ValidatedFailoverTargets validated =
				validation.validatePreview(userId, projectId, sourceIntegrationId, request);
		JiraIntegration source = validated.source();
		JiraIntegration target = validated.target();

		int page = request.resolvedPage();
		int size = request.resolvedSize();

		List<Task> sourceTasks =
				tasks.findActiveFetchedByProjectAndJiraIntegration(projectId, source.getId());
		List<UUID> sourceTaskIds = sourceTasks.stream().map(Task::getId).toList();

		Map<UUID, JiraTaskFailoverItem> claimBySource = loadClaims(sourceTaskIds);

		Set<UUID> unfinishedCandidateIds = sourceTasks.stream()
				.filter(task -> task.getStatus() != TaskStatus.DONE)
				.filter(task -> {
					JiraTaskFailoverItem claim = claimBySource.get(task.getId());
					return claim == null || !claim.holdsOutboundClaim();
				})
				.filter(task -> !isSubtask(task))
				.map(Task::getId)
				.collect(Collectors.toCollection(HashSet::new));

		Map<UUID, String> jiraAccountByUserId = loadJiraAccountsForAssignees(sourceTasks);

		// Provider metadata for target B — outside DB work; never uses source credentials.
		TargetProviderOptions options = loadTargetOptions(target);

		List<PreviewItem> allItems = new ArrayList<>(sourceTasks.size());
		int eligible = 0;
		int blocked = 0;
		int alreadySuperseded = 0;
		int alreadyInFailover = 0;
		int reconciliationRequired = 0;
		int skippedDone = 0;
		int ready = 0;
		int withWarnings = 0;

		UUID plannedSprintId =
				validated.targetSprint() != null ? validated.targetSprint().getId() : null;

		for (Task task : sourceTasks) {
			PreviewItem item = classify(
					task,
					claimBySource,
					unfinishedCandidateIds,
					options,
					jiraAccountByUserId,
					request.defaultIssueTypeId(),
					validated.targetSprint() != null,
					plannedSprintId);
			allItems.add(item);
			switch (item.classification()) {
				case ELIGIBLE -> {
					eligible++;
					if (item.readiness() == Readiness.READY) {
						ready++;
					}
				}
				case BLOCKED -> blocked++;
				case ALREADY_SUPERSEDED -> alreadySuperseded++;
				case ALREADY_IN_FAILOVER -> alreadyInFailover++;
				case RECONCILIATION_REQUIRED -> reconciliationRequired++;
				case SKIP_DONE -> skippedDone++;
			}
			if (!item.warnings().isEmpty()) {
				withWarnings++;
			}
		}

		int totalItems = allItems.size();
		int totalPages = totalItems == 0 ? 0 : (int) Math.ceil(totalItems / (double) size);
		int from = Math.min(page * size, totalItems);
		int to = Math.min(from + size, totalItems);
		List<PreviewItem> pageItems = List.copyOf(allItems.subList(from, to));
		boolean hasNext = page + 1 < totalPages;

		return new JiraFailoverPreviewResponse(
				projectId,
				source.getId(),
				target.getId(),
				plannedSprintId,
				request.defaultIssueTypeId(),
				source.getLastSuccessfulSyncAt(),
				JiraFailoverPreviewResponse.DEPENDENCY_REMAP_DEFERRED,
				new Counts(
						sourceTasks.size(),
						eligible,
						blocked,
						alreadySuperseded,
						alreadyInFailover,
						reconciliationRequired,
						skippedDone,
						ready,
						withWarnings),
				pageItems,
				page,
				size,
				totalItems,
				totalPages,
				hasNext,
				eligible,
				new TargetOptionsSummary(
						options.issueTypes().size(),
						options.priorities().size(),
						options.assignableUsers().size(),
						ASSIGNABLE_USER_CAP));
	}

	private Map<UUID, JiraTaskFailoverItem> loadClaims(List<UUID> sourceTaskIds) {
		if (sourceTaskIds.isEmpty()) {
			return Map.of();
		}
		return failoverItems
				.findFetchedBySourceTask_IdInAndStatusIn(
						sourceTaskIds, JiraFailoverSupersedeQueries.CLAIM_HOLDING_STATUSES)
				.stream()
				.collect(Collectors.toMap(
						item -> item.getSourceTask().getId(),
						Function.identity(),
						(a, b) -> a.getCreatedAt() != null
										&& b.getCreatedAt() != null
										&& a.getCreatedAt().isAfter(b.getCreatedAt())
								? a
								: b));
	}

	private PreviewItem classify(
			Task task,
			Map<UUID, JiraTaskFailoverItem> claimBySource,
			Set<UUID> unfinishedCandidateIds,
			TargetProviderOptions options,
			Map<UUID, String> jiraAccountByUserId,
			String defaultIssueTypeId,
			boolean targetSprintProvided,
			UUID targetSprintId) {
		JiraTaskFailoverItem claim = claimBySource.get(task.getId());
		if (claim != null && claim.getStatus() == JiraFailoverItemStatus.SUCCEEDED && claim.getTargetTask() != null) {
			Task targetTask = claim.getTargetTask();
			return claimItem(
					task,
					ItemClassification.ALREADY_SUPERSEDED,
					null,
					claim,
					targetTask.getId(),
					targetTask.getExternalKey(),
					claim.getRun() != null ? claim.getRun().getId() : null);
		}
		if (claim != null && claim.getStatus() == JiraFailoverItemStatus.REMOTE_OUTCOME_UNKNOWN) {
			return claimItem(
					task,
					ItemClassification.RECONCILIATION_REQUIRED,
					Readiness.BLOCKED,
					claim,
					null,
					null,
					null);
		}
		if (claim != null
				&& (claim.getStatus() == JiraFailoverItemStatus.PENDING
						|| claim.getStatus() == JiraFailoverItemStatus.CREATING
						|| claim.getStatus() == JiraFailoverItemStatus.REMOTE_BOUND
						|| claim.getStatus() == JiraFailoverItemStatus.SUCCEEDED)) {
			// SUCCEEDED without target is still claimed / not READY
			return claimItem(
					task,
					ItemClassification.ALREADY_IN_FAILOVER,
					Readiness.BLOCKED,
					claim,
					null,
					null,
					null);
		}

		if (task.getStatus() == TaskStatus.DONE) {
			return new PreviewItem(
					task.getId(),
					task.getExternalKey(),
					task.getTitle(),
					task.getStatus(),
					task.getTaskType(),
					task.getIssueTypeName(),
					ItemClassification.SKIP_DONE,
					null,
					List.of(),
					List.of(),
					emptyMapping(),
					plannedCopy(task),
					PlannedStatusHandling.PROVIDER_DEFAULT,
					null,
					null,
					task.getParentTask() != null ? task.getParentTask().getId() : null,
					null,
					null,
					null,
					null,
					null,
					null);
		}

		List<String> blockers = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		if (isSubtask(task)) {
			blockers.add(BLOCKER_PROVIDER_SUBTASK_UNSUPPORTED);
		}

		FieldMappingPreview mapping =
				mapFields(task, options, jiraAccountByUserId, defaultIssueTypeId, blockers, warnings);

		if (!targetSprintProvided && task.getSprint() == null) {
			warnings.add(WARNING_CONTRIBUTION_REQUIRES_SPRINT);
		}

		ParentPreview parent = buildParentPreview(task, unfinishedCandidateIds, claimBySource);
		if (parent != null && parent.parentDone()) {
			warnings.add(WARNING_PARENT_DONE);
		}

		ItemClassification classification =
				blockers.isEmpty() ? ItemClassification.ELIGIBLE : ItemClassification.BLOCKED;
		Readiness readiness = blockers.isEmpty()
				? (warnings.isEmpty() ? Readiness.READY : Readiness.WARNING)
				: Readiness.BLOCKED;
		PlannedSprintPlacement sprintPlacement =
				targetSprintProvided ? PlannedSprintPlacement.TARGET_SPRINT : PlannedSprintPlacement.BACKLOG;

		return new PreviewItem(
				task.getId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus(),
				task.getTaskType(),
				task.getIssueTypeName(),
				classification,
				readiness,
				List.copyOf(blockers),
				List.copyOf(warnings),
				mapping,
				plannedCopy(task),
				PlannedStatusHandling.PROVIDER_DEFAULT,
				sprintPlacement,
				targetSprintProvided ? targetSprintId : null,
				task.getParentTask() != null ? task.getParentTask().getId() : null,
				parent,
				null,
				null,
				null,
				null,
				null);
	}

	private PreviewItem claimItem(
			Task task,
			ItemClassification classification,
			Readiness readiness,
			JiraTaskFailoverItem claim,
			UUID supersededTargetTaskId,
			String supersededTargetExternalKey,
			UUID supersededRunId) {
		return new PreviewItem(
				task.getId(),
				task.getExternalKey(),
				task.getTitle(),
				task.getStatus(),
				task.getTaskType(),
				task.getIssueTypeName(),
				classification,
				readiness,
				List.of(),
				List.of(),
				emptyMapping(),
				plannedCopy(task),
				PlannedStatusHandling.PROVIDER_DEFAULT,
				null,
				null,
				task.getParentTask() != null ? task.getParentTask().getId() : null,
				null,
				claim.getRun() != null ? claim.getRun().getId() : null,
				claim.getId(),
				supersededTargetTaskId,
				supersededTargetExternalKey,
				supersededRunId != null
						? supersededRunId
						: (classification == ItemClassification.ALREADY_SUPERSEDED && claim.getRun() != null
								? claim.getRun().getId()
								: null));
	}

	private static PlannedCopyPreview plannedCopy(Task task) {
		return new PlannedCopyPreview(
				task.getTitle(),
				task.getDescription(),
				task.getStoryPoint(),
				task.getDueDate(),
				task.getStartDate(),
				List.copyOf(TaskLabelParser.parse(task.getLabelsJson())));
	}

	private ParentPreview buildParentPreview(
			Task task, Set<UUID> unfinishedCandidateIds, Map<UUID, JiraTaskFailoverItem> claimBySource) {
		Task parent = task.getParentTask();
		if (parent == null) {
			return null;
		}
		boolean alsoCandidate = unfinishedCandidateIds.contains(parent.getId());
		boolean parentDone = parent.getStatus() == TaskStatus.DONE;
		JiraTaskFailoverItem parentClaim = claimBySource.get(parent.getId());
		boolean parentSuperseded = parentClaim != null
				&& parentClaim.getStatus() == JiraFailoverItemStatus.SUCCEEDED
				&& parentClaim.getTargetTask() != null;
		UUID parentTargetId =
				parentSuperseded ? parentClaim.getTargetTask().getId() : null;
		String orderNote = alsoCandidate ? ORDER_PARENT_ALSO_CANDIDATE : null;
		return new ParentPreview(
				parent.getId(),
				parent.getExternalKey(),
				parent.getStatus(),
				alsoCandidate,
				parentDone,
				parentSuperseded,
				parentTargetId,
				orderNote);
	}

	private FieldMappingPreview mapFields(
			Task task,
			TargetProviderOptions options,
			Map<UUID, String> jiraAccountByUserId,
			String defaultIssueTypeId,
			List<String> blockers,
			List<String> warnings) {
		IssueTypeOption issueType = matchIssueType(task.getIssueTypeName(), options.issueTypes());
		boolean matchedByName = issueType != null;
		if (issueType == null && defaultIssueTypeId != null && !defaultIssueTypeId.isBlank()) {
			issueType = options.issueTypes().stream()
					.filter(opt -> defaultIssueTypeId.equals(opt.id()))
					.findFirst()
					.orElse(null);
		}
		if (issueType == null && blockers.stream().noneMatch(BLOCKER_PROVIDER_SUBTASK_UNSUPPORTED::equals)) {
			blockers.add(BLOCKER_ISSUE_TYPE_UNMAPPED);
		}

		PriorityOption priority = matchPriority(task.getPriority(), options.priorities());
		if (task.getPriority() != null && priority == null) {
			warnings.add(WARNING_PRIORITY_UNMAPPED);
		}

		String assigneeAccountId = null;
		String assigneeDisplayName = null;
		if (task.getAssigneeStudent() != null && task.getAssigneeStudent().getUserAccount() != null) {
			UUID userId = task.getAssigneeStudent().getUserAccount().getId();
			assigneeAccountId = jiraAccountByUserId.get(userId);
			if (assigneeAccountId != null) {
				AssignableUserOption matched = options.assignableByAccountId().get(assigneeAccountId);
				if (matched != null) {
					assigneeDisplayName = matched.displayName();
				} else {
					warnings.add(WARNING_ASSIGNEE_UNMAPPED);
					assigneeAccountId = null;
				}
			} else {
				warnings.add(WARNING_ASSIGNEE_UNMAPPED);
			}
		}

		return new FieldMappingPreview(
				issueType != null ? issueType.id() : null,
				issueType != null ? issueType.name() : null,
				matchedByName,
				priority != null ? priority.id() : null,
				priority != null ? priority.name() : null,
				assigneeAccountId,
				assigneeDisplayName);
	}

	private Map<UUID, String> loadJiraAccountsForAssignees(List<Task> sourceTasks) {
		Set<UUID> userIds = new HashSet<>();
		for (Task task : sourceTasks) {
			if (task.getAssigneeStudent() != null && task.getAssigneeStudent().getUserAccount() != null) {
				userIds.add(task.getAssigneeStudent().getUserAccount().getId());
			}
		}
		if (userIds.isEmpty()) {
			return Map.of();
		}
		List<IdentityMap> maps = identities.findFetchedByUserAccount_IdInAndProviderAndMappingStatusIn(
				userIds, IntegrationProvider.JIRA, ACTIVE_IDENTITY_STATUSES);
		Map<UUID, String> byUser = new HashMap<>();
		for (IdentityMap map : maps) {
			if (map.getExternalAccountId() == null || map.getExternalAccountId().isBlank()) {
				continue;
			}
			UUID userId = map.getUserAccount().getId();
			byUser.putIfAbsent(userId, map.getExternalAccountId());
		}
		return byUser;
	}

	private TargetProviderOptions loadTargetOptions(JiraIntegration target) {
		String access = tokens.accessToken(target);
		List<IssueTypeOption> issueTypes =
				jiraWrite.listProjectIssueTypes(access, target.getCloudId(), target.getJiraProjectId());
		List<PriorityOption> priorities = jiraWrite.listPriorities(access, target.getCloudId());
		String projectRef =
				target.getProjectKey() != null && !target.getProjectKey().isBlank()
						? target.getProjectKey()
						: target.getJiraProjectId();
		List<AssignableUserOption> users =
				jiraWrite.listAssignableUsers(access, target.getCloudId(), projectRef, ASSIGNABLE_USER_CAP);
		Map<String, AssignableUserOption> byAccountId = users.stream()
				.filter(u -> u.accountId() != null)
				.collect(Collectors.toMap(AssignableUserOption::accountId, Function.identity(), (a, b) -> a));
		return new TargetProviderOptions(issueTypes, priorities, users, byAccountId);
	}

	static boolean isSubtask(Task task) {
		if (task.getTaskType() == TaskType.SUBTASK) {
			return true;
		}
		String name = task.getIssueTypeName();
		if (name == null || name.isBlank()) {
			return false;
		}
		return "sub-task".equalsIgnoreCase(name.trim()) || "subtask".equalsIgnoreCase(name.trim());
	}

	private static IssueTypeOption matchIssueType(String issueTypeName, List<IssueTypeOption> options) {
		if (issueTypeName == null || issueTypeName.isBlank()) {
			return null;
		}
		String needle = issueTypeName.trim();
		for (IssueTypeOption option : options) {
			if (option.name() != null && option.name().equalsIgnoreCase(needle)) {
				return option;
			}
		}
		return null;
	}

	private static PriorityOption matchPriority(Priority priority, List<PriorityOption> options) {
		if (priority == null) {
			return null;
		}
		String needle = priority.name();
		for (PriorityOption option : options) {
			if (option.name() != null && option.name().trim().equalsIgnoreCase(needle)) {
				return option;
			}
		}
		for (PriorityOption option : options) {
			if (option.name() == null) {
				continue;
			}
			Priority mapped = ProjectionMappings.priority(option.name());
			if (mapped == priority) {
				return option;
			}
		}
		return null;
	}

	private static FieldMappingPreview emptyMapping() {
		return new FieldMappingPreview(null, null, false, null, null, null, null);
	}

	private record TargetProviderOptions(
			List<IssueTypeOption> issueTypes,
			List<PriorityOption> priorities,
			List<AssignableUserOption> assignableUsers,
			Map<String, AssignableUserOption> assignableByAccountId) {}
}

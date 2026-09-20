package com.saga.be.dto.integration.failover;

import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Paged failover preview. Global counts cover the full eligible/candidate set; {@code items} is
 * one page only. Preview is never an execution snapshot — Phase 4B must persist an explicit run
 * item set before remote create.
 */
public record JiraFailoverPreviewResponse(
		UUID projectId,
		UUID sourceIntegrationId,
		UUID targetIntegrationId,
		UUID targetSprintId,
		String defaultIssueTypeId,
		LocalDateTime sourceLastSuccessfulSyncAt,
		String dependencyRemap,
		Counts counts,
		List<PreviewItem> items,
		int page,
		int size,
		int totalItems,
		int totalPages,
		boolean hasNext,
		int totalEligible,
		TargetOptionsSummary targetOptions) {

	public static final int ITEM_PAGE_DEFAULT = 50;
	public static final int ITEM_PAGE_MAX = 200;
	public static final String DEPENDENCY_REMAP_DEFERRED = "DEFERRED";

	public record Counts(
			int totalActive,
			int eligible,
			int blocked,
			int alreadySuperseded,
			int alreadyInFailover,
			int reconciliationRequired,
			int skippedDone,
			int ready,
			int withWarnings) {}

	public enum ItemClassification {
		ELIGIBLE,
		BLOCKED,
		ALREADY_SUPERSEDED,
		ALREADY_IN_FAILOVER,
		RECONCILIATION_REQUIRED,
		SKIP_DONE
	}

	public enum Readiness {
		READY,
		WARNING,
		BLOCKED
	}

	public enum PlannedStatusHandling {
		PROVIDER_DEFAULT,
		OPTIONAL_TRANSITION_IF_UNAMBIGUOUS
	}

	public enum PlannedSprintPlacement {
		TARGET_SPRINT,
		BACKLOG
	}

	public record PreviewItem(
			UUID sourceTaskId,
			String externalKey,
			String title,
			TaskStatus status,
			TaskType taskType,
			String issueTypeName,
			ItemClassification classification,
			Readiness readiness,
			List<String> blockers,
			List<String> warnings,
			FieldMappingPreview mapping,
			PlannedCopyPreview plannedCopy,
			PlannedStatusHandling plannedStatusHandling,
			PlannedSprintPlacement plannedSprint,
			UUID plannedTargetSprintId,
			UUID parentTaskId,
			ParentPreview parent,
			UUID claimRunId,
			UUID claimItemId,
			UUID supersededTargetTaskId,
			String supersededTargetExternalKey,
			UUID supersededRunId) {}

	public record FieldMappingPreview(
			String resolvedIssueTypeId,
			String resolvedIssueTypeName,
			boolean issueTypeMatchedByName,
			String resolvedPriorityId,
			String resolvedPriorityName,
			String resolvedAssigneeAccountId,
			String resolvedAssigneeDisplayName) {}

	public record PlannedCopyPreview(
			String title,
			String description,
			Integer storyPoint,
			LocalDateTime dueDate,
			LocalDateTime startDate,
			List<String> labels) {}

	public record ParentPreview(
			UUID parentTaskId,
			String parentExternalKey,
			TaskStatus parentStatus,
			boolean parentAlsoCandidate,
			boolean parentDone,
			boolean parentSuperseded,
			UUID parentTargetTaskId,
			String orderNote) {}

	public record TargetOptionsSummary(
			int issueTypeCount, int priorityCount, int assignableUserCount, int assignableUserCap) {}
}

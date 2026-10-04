package com.saga.be.service.contribution;

import com.saga.be.entity.enums.ContributionCriterion;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.service.contribution.ReservedContributionMarkerClassifier.Outcome;
import com.saga.be.service.contribution.SprintFirstContributionMixer.TaskFact;
import com.saga.be.service.projection.JiraParentResolution;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns project tasks into mixer facts. A Standard item with no Subtask is unchanged. A Standard
 * item with Subtasks does not award its own story points: each Subtask receives
 * {@code parentStoryPoint × (entered / 10)} when both the parent and that Subtask are DONE.
 * Sprint comes from the parent. The {@code saga:*} label is the Subtask's own, so a test
 * Subtask under a code parent scores as test. DOCUMENT/RESEARCH evidence is the Subtask's.
 * CODE/TEST score only when that same task has a linked non-merge commit. An Epic awards nothing.
 */
public final class ContributionTaskFacts {

	private ContributionTaskFacts() {}

	public static List<TaskFact> from(List<Task> tasks, Set<UUID> evidenced, Set<UUID> committed) {
		if (tasks == null || tasks.isEmpty()) {
			return List.of();
		}
		Map<String, List<Task>> subtasksByParent = new HashMap<>();
		for (Task task : tasks) {
			if (!isSubtask(task) || task.getJiraIntegration() == null || task.getParentExternalId() == null) {
				continue;
			}
			subtasksByParent
					.computeIfAbsent(
							JiraParentResolution.key(task.getJiraIntegration().getId(), task.getParentExternalId()),
							ignored -> new ArrayList<>())
					.add(task);
		}
		List<TaskFact> facts = new ArrayList<>();
		for (Task task : tasks) {
			if (isSubtask(task) || isEpic(task)) {
				continue;
			}
			List<Task> children = childrenOf(task, subtasksByParent);
			if (children.isEmpty()) {
				if (task.getAssigneeStudent() == null) {
					continue;
				}
				facts.add(standard(task, evidenced, committed));
				continue;
			}
			for (Task child : children) {
				if (child.getAssigneeStudent() == null) {
					continue;
				}
				facts.add(subtask(task, child, evidenced, committed));
			}
		}
		return List.copyOf(facts);
	}

	private static TaskFact standard(Task task, Set<UUID> evidenced, Set<UUID> committed) {
		Outcome outcome = ReservedContributionMarkerClassifier.classify(TaskLabelParser.parse(task.getLabelsJson()));
		ContributionCriterion criterion = gate(task, ReservedContributionMarkerClassifier.toCriterion(outcome), evidenced, committed);
		Integer storyPoint = task.getStoryPoint();
		return new TaskFact(
				task.getAssigneeStudent().getId(),
				task.getSprint() == null ? null : task.getSprint().getId(),
				task.getSprint() == null ? null : task.getSprint().getName(),
				task.getStatus(),
				storyPoint == null ? null : BigDecimal.valueOf(storyPoint.longValue()),
				criterion);
	}

	private static TaskFact subtask(Task parent, Task child, Set<UUID> evidenced, Set<UUID> committed) {
		Outcome outcome = ReservedContributionMarkerClassifier.classify(TaskLabelParser.parse(child.getLabelsJson()));
		ContributionCriterion criterion = gate(child, ReservedContributionMarkerClassifier.toCriterion(outcome), evidenced, committed);
		boolean bothDone = parent.getStatus() == TaskStatus.DONE && child.getStatus() == TaskStatus.DONE;
		return new TaskFact(
				child.getAssigneeStudent().getId(),
				parent.getSprint() == null ? null : parent.getSprint().getId(),
				parent.getSprint() == null ? null : parent.getSprint().getName(),
				bothDone ? TaskStatus.DONE : TaskStatus.IN_PROGRESS,
				SubtaskPercentPolicy.share(parent.getStoryPoint(), child.getStoryPoint()),
				criterion);
	}

	private static ContributionCriterion gate(
			Task task, ContributionCriterion criterion, Set<UUID> evidenced, Set<UUID> committed) {
		if (needsDocument(criterion) && (evidenced == null || !evidenced.contains(task.getId()))) {
			return null;
		}
		if (needsCommit(criterion) && (committed == null || !committed.contains(task.getId()))) {
			return null;
		}
		return criterion;
	}

	private static boolean needsDocument(ContributionCriterion criterion) {
		return criterion == ContributionCriterion.DOCUMENT || criterion == ContributionCriterion.RESEARCH;
	}

	private static boolean needsCommit(ContributionCriterion criterion) {
		return criterion == ContributionCriterion.CODE || criterion == ContributionCriterion.TEST;
	}

	private static List<Task> childrenOf(Task task, Map<String, List<Task>> subtasksByParent) {
		if (task.getJiraIntegration() == null || task.getExternalId() == null || task.getExternalId().isBlank()) {
			return List.of();
		}
		return subtasksByParent.getOrDefault(
				JiraParentResolution.key(task.getJiraIntegration().getId(), task.getExternalId()), List.of());
	}

	private static boolean isSubtask(Task task) {
		return "SUBTASK".equals(task.getIssueTypeLevel());
	}

	private static boolean isEpic(Task task) {
		return "EPIC".equals(task.getIssueTypeLevel()) || "ABOVE_EPIC".equals(task.getIssueTypeLevel());
	}
}

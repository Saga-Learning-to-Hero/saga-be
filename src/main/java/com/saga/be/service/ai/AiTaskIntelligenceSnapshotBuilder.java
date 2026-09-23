package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.service.task.TaskDeadlinePolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

/**
 * Bounded local Task Intelligence evidence: the task's own fields, its deterministic deadline
 * status (computed the same way {@link com.saga.be.service.task.TaskDeadlineWarningService} does,
 * never invented by the model), and up to {@value #MAX_LINKED_COMMITS} most-recent linked commits.
 * No GitHub/Jira live reads occur here — every fact comes from already-synced local rows.
 */
@Component @Profile("!test")
public class AiTaskIntelligenceSnapshotBuilder {
	static final int MAX_LINKED_COMMITS = 40;

	private final ObjectMapper mapper;
	private final TaskGitCommitLinkRepository commitLinks;
	private final TaskWorkSessionRepository workSessions;
	private final TaskDeadlineProperties deadlineProperties;
	private final Clock clock;

	@Autowired
	public AiTaskIntelligenceSnapshotBuilder(ObjectMapper mapper, TaskGitCommitLinkRepository commitLinks, TaskWorkSessionRepository workSessions, TaskDeadlineProperties deadlineProperties) {
		this(mapper, commitLinks, workSessions, deadlineProperties, Clock.systemUTC());
	}

	public AiTaskIntelligenceSnapshotBuilder(ObjectMapper mapper, TaskGitCommitLinkRepository commitLinks, TaskWorkSessionRepository workSessions, TaskDeadlineProperties deadlineProperties, Clock clock) {
		this.mapper = mapper;
		this.commitLinks = commitLinks;
		this.workSessions = workSessions;
		this.deadlineProperties = deadlineProperties;
		this.clock = clock;
	}

	public List<AiEvidenceDraft> build(Task task) {
		LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
		TaskDeadlinePolicy.Status deadlineStatus = TaskDeadlinePolicy.classify(task.getStatus(), task.getDueDate(), now, deadlineProperties.getDueSoonWindow());
		List<TaskGitCommitLink> links = commitLinks.findByTask_IdOrderByGitCommit_CommittedAtDesc(task.getId(), PageRequest.of(0, MAX_LINKED_COMMITS));
		long workSessionCount = workSessions.countByTask_Id(task.getId());

		Map<String, Object> taskField = new TreeMap<>();
		taskField.put("taskId", task.getId());
		taskField.put("projectId", task.getProject().getId());
		taskField.put("externalKey", n(task.getExternalKey()));
		taskField.put("title", n(task.getTitle()));
		taskField.put("description", n(task.getDescription()));
		taskField.put("status", task.getStatus() == null ? null : task.getStatus().name());
		taskField.put("priority", task.getPriority() == null ? null : task.getPriority().name());
		taskField.put("storyPoint", task.getStoryPoint());
		taskField.put("assigneeUserId", task.getAssigneeStudent() == null || task.getAssigneeStudent().getUserAccount() == null ? null : task.getAssigneeStudent().getUserAccount().getId());
		taskField.put("assigneeName", task.getAssigneeStudent() == null || task.getAssigneeStudent().getUserAccount() == null ? null : n(task.getAssigneeStudent().getUserAccount().getFullName()));
		taskField.put("dueDate", task.getDueDate());
		taskField.put("startDate", task.getStartDate());
		taskField.put("sprint", task.getSprint() == null ? null : Map.of("id", task.getSprint().getId(), "name", n(task.getSprint().getName())));
		taskField.put("linkedCommitCount", links.size());
		taskField.put("linkedCommitCountIsBounded", links.size() >= MAX_LINKED_COMMITS);
		taskField.put("workSessionCount", workSessionCount);
		// Deliberately NOT including the raw "now" timestamp here: this payload feeds the
		// evidence-hash-based idempotency key, and a continuously-changing timestamp would defeat
		// idempotency for an otherwise-unchanged task. The bucketed status is enough signal for
		// both the model and for correctly re-triggering analysis when the status transitions.
		taskField.put("deterministicDeadlineStatus", deadlineStatus.name());
		String taskJson = json(taskField);

		List<AiEvidenceDraft> draft = new ArrayList<>();
		draft.add(new AiEvidenceDraft(AiEvidenceType.TASK_FIELD, "task:" + task.getId(), taskJson, null));
		if (!links.isEmpty()) {
			List<Map<String, Object>> commitRows = new ArrayList<>();
			for (TaskGitCommitLink link : links) {
				var commit = link.getGitCommit();
				Map<String, Object> row = new TreeMap<>();
				row.put("sha", commit.getShaHash());
				row.put("message", n(commit.getMessage()));
				row.put("committedAt", commit.getCommittedAt());
				row.put("linkSource", link.getLinkSource() == null ? null : link.getLinkSource().name());
				commitRows.add(row);
			}
			String commitsJson = json(commitRows);
			draft.add(new AiEvidenceDraft(AiEvidenceType.METADATA, "task-linked-commits:" + task.getId(), commitsJson, null));
		}
		return draft;
	}

	private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
	private static String n(String value) { return value == null ? "" : value; }
}

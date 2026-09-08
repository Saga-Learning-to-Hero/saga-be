package com.saga.be.service.jira;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskFileProperties;
import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TaskType;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskAttachment;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.jira.JiraCloudWorkClient;
import com.saga.be.integration.jira.JiraIssueEvidenceParser;
import com.saga.be.integration.jira.JiraIssueEvidenceParser.Attachment;
import com.saga.be.integration.jira.JiraIssueEvidenceParser.IssueRef;
import com.saga.be.integration.jira.JiraIssueEvidenceParser.RemoteLink;
import com.saga.be.integration.jira.JiraTeamTokenService;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.service.attribution.TaskCompletionEvaluator;
import com.saga.be.service.evidence.TaskFileStorage;
import com.saga.be.service.evidence.TaskFileTypes;
import com.saga.be.service.evidence.TaskWebLinkUrls;
import java.io.IOException;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class JiraIssueEvidenceSyncService {

	private static final Logger log = LoggerFactory.getLogger(JiraIssueEvidenceSyncService.class);

	private final JiraIntegrationRepository integrations;
	private final JiraTeamTokenService tokens;
	private final JiraCloudWorkClient jira;
	private final TaskRepository tasks;
	private final TaskWebLinkRepository links;
	private final TaskFileRepository files;
	private final TaskAttachmentRepository attachments;
	private final TaskFileProperties fileProperties;
	private final TaskFileStorage storage;
	private final ObjectMapper mapper;

	public JiraIssueEvidenceSyncService(
			JiraIntegrationRepository integrations,
			JiraTeamTokenService tokens,
			JiraCloudWorkClient jira,
			TaskRepository tasks,
			TaskWebLinkRepository links,
			TaskFileRepository files,
			TaskAttachmentRepository attachments,
			TaskFileProperties fileProperties,
			ObjectMapper mapper) {
		this.integrations = integrations;
		this.tokens = tokens;
		this.jira = jira;
		this.tasks = tasks;
		this.links = links;
		this.files = files;
		this.attachments = attachments;
		this.fileProperties = fileProperties;
		this.storage = new TaskFileStorage(fileProperties.getDirectory());
		this.mapper = mapper;
	}

	public void handleWebhook(String rawJson) {
		try {
			JsonNode root = mapper.readTree(rawJson == null ? "{}" : rawJson);
			IssueRef ref = JiraIssueEvidenceParser.fromWebhook(root).orElse(null);
			if (ref == null || ref.projectId() == null) {
				return;
			}
			List<JiraIntegration> matches =
					integrations.findByJiraProjectIdAndConnectionStatus(ref.projectId(), com.saga.be.entity.enums.IntegrationStatus.ACTIVE);
			if (matches.isEmpty()) {
				matches = integrations.findByJiraProjectIdAndConnectionStatus(
						ref.projectId(), com.saga.be.entity.enums.IntegrationStatus.CONNECTED);
			}
			for (JiraIntegration integration : matches) {
				syncIssue(integration, ref.issueId() != null ? ref.issueId() : ref.issueKey());
			}
		} catch (Exception ex) {
			log.warn("jira evidence webhook skipped: {}", ex.getMessage());
		}
	}

	@Transactional
	public int syncIntegration(JiraIntegration integration) {
		if (integration.getCloudId() == null || integration.getProjectKey() == null) {
			return 0;
		}
		String access = tokens.accessToken(integration);
		int processed = 0;
		int start = 0;
		while (true) {
			List<JsonNode> page = jira.searchIssues(access, integration.getCloudId(), integration.getProjectKey(), start, 50);
			if (page.isEmpty()) {
				break;
			}
			for (JsonNode issue : page) {
				syncFetchedIssue(integration, access, issue);
				processed++;
			}
			start += page.size();
			if (page.size() < 50) {
				break;
			}
		}
		return processed;
	}

	@Transactional
	public void syncIssue(JiraIntegration integration, String issueIdOrKey) {
		String access = tokens.accessToken(integration);
		JsonNode issue = jira.getIssue(access, integration.getCloudId(), issueIdOrKey);
		syncFetchedIssue(integration, access, issue);
	}

	private void syncFetchedIssue(JiraIntegration integration, String access, JsonNode issue) {
		IssueRef ref = JiraIssueEvidenceParser.fromIssue(issue).orElse(null);
		if (ref == null || ref.issueId() == null) {
			return;
		}
		Task task = upsertTask(integration.getProject(), issue, ref);
		JsonNode remote = jira.listRemoteLinks(access, integration.getCloudId(), ref.issueId());
		syncLinks(task, JiraIssueEvidenceParser.remoteLinks(remote));
		List<Attachment> jiraFiles = JiraIssueEvidenceParser.attachments(issue);
		syncAttachmentMetadata(task, jiraFiles);
		syncFiles(integration, access, task, jiraFiles);
	}

	private Task upsertTask(Project project, JsonNode issue, IssueRef ref) {
		Task task = tasks.findByProject_IdAndExternalId(project.getId(), ref.issueId()).orElseGet(Task::new);
		task.setProject(project);
		task.setExternalId(ref.issueId());
		task.setExternalKey(ref.issueKey());
		task.setTitle(JiraIssueEvidenceParser.summary(issue));
		task.setJiraStatusId(JiraIssueEvidenceParser.statusId(issue));
		task.setJiraStatusName(JiraIssueEvidenceParser.statusName(issue));
		task.setJiraStatusCategory(JiraIssueEvidenceParser.statusCategory(issue));
		TaskStatus status = mapStatus(task.getJiraStatusCategory(), task.getJiraStatusName());
		task.setStatus(status);
		task.setIssueTypeName(JiraIssueEvidenceParser.issueTypeName(issue));
		task.setTaskType(mapType(task.getIssueTypeName()));
		task.setSagaCompletionState(TaskCompletionEvaluator.evaluate(status, task.getJiraStatusCategory(), null, null, LocalDateTime.now()));
		try {
			task.setLabelsJson(mapper.writeValueAsString(JiraIssueEvidenceParser.labels(issue)));
		} catch (JsonProcessingException ex) {
			task.setLabelsJson("[]");
		}
		task.setDeletedAt(null);
		return tasks.save(task);
	}

	private void syncLinks(Task task, List<RemoteLink> remoteLinks) {
		Set<String> keep = new HashSet<>();
		for (RemoteLink remote : remoteLinks) {
			String url;
			try {
				url = TaskWebLinkUrls.normalize(remote.url());
			} catch (RuntimeException ex) {
				continue;
			}
			String hash = TaskWebLinkUrls.hash(url);
			keep.add(remote.id());
			TaskWebLink existing = links.findByTask_IdAndExternalId(task.getId(), remote.id()).orElse(null);
			if (existing == null) {
				if (links.findByTask_IdAndUrlHash(task.getId(), hash).isPresent()) {
					continue;
				}
				existing = new TaskWebLink();
				existing.setTask(task);
				existing.setExternalId(remote.id());
				existing.setSource(EvidenceSource.JIRA);
			}
			existing.setUrl(url);
			existing.setUrlHash(hash);
			existing.setTitle(remote.title());
			existing.setSource(EvidenceSource.JIRA);
			links.save(existing);
		}
		for (TaskWebLink row : links.findByTask_IdAndSource(task.getId(), EvidenceSource.JIRA)) {
			if (row.getExternalId() == null || !keep.contains(row.getExternalId())) {
				links.delete(row);
			}
		}
	}

	private void syncAttachmentMetadata(Task task, List<Attachment> jiraFiles) {
		Set<String> keep = new HashSet<>();
		for (Attachment item : jiraFiles) {
			keep.add(item.id());
			TaskAttachment row = attachments.findByTask_IdAndExternalId(task.getId(), item.id()).orElseGet(TaskAttachment::new);
			row.setTask(task);
			row.setExternalId(item.id());
			row.setFilename(item.filename());
			row.setMimeType(item.mimeType());
			row.setSizeBytes(item.sizeBytes());
			row.setAuthorExternalId(item.authorAccountId());
			attachments.save(row);
		}
		for (TaskAttachment row : attachments.findByTask_Id(task.getId())) {
			if (!keep.contains(row.getExternalId())) {
				attachments.delete(row);
			}
		}
	}

	private void syncFiles(JiraIntegration integration, String access, Task task, List<Attachment> jiraFiles) {
		Set<String> keep = new HashSet<>();
		for (Attachment item : jiraFiles) {
			keep.add(item.id());
			TaskFile existing = files.findByTask_IdAndExternalId(task.getId(), item.id()).orElse(null);
			if (existing != null && existing.getSizeBytes() == item.sizeBytes()) {
				continue;
			}
			if (item.sizeBytes() > fileProperties.getMaxBytes()) {
				log.info("jira attachment skipped size task={} attachment={}", task.getId(), item.id());
				continue;
			}
			byte[] content;
			try {
				content = jira.downloadAttachment(access, integration.getCloudId(), item.id());
			} catch (RuntimeException ex) {
				log.warn("jira attachment download failed task={} attachment={}: {}", task.getId(), item.id(), ex.getMessage());
				continue;
			}
			if (content.length == 0 || startsWithMzOrElf(content)) {
				continue;
			}
			String filename;
			try {
				filename = TaskFileTypes.sanitizeFilename(item.filename());
			} catch (RuntimeException ex) {
				filename = "jira-attachment-" + item.id();
			}
			String mime = item.mimeType() == null || item.mimeType().isBlank() ? "application/octet-stream" : item.mimeType();
			String hash = sha256(content);
			if (files.findByTask_IdAndContentHash(task.getId(), hash).isPresent() && existing == null) {
				continue;
			}
			if (existing == null) {
				existing = new TaskFile();
				existing.setTask(task);
				existing.setExternalId(item.id());
				existing.setSource(EvidenceSource.JIRA);
			}
			existing.setOriginalFilename(filename);
			existing.setMimeType(mime);
			existing.setSizeBytes(content.length);
			existing.setContentHash(hash);
			existing.setSource(EvidenceSource.JIRA);
			existing = files.save(existing);
			try {
				storage.write(task.getId(), existing.getId(), content);
			} catch (IOException ex) {
				files.delete(existing);
				log.warn("jira attachment store failed task={} attachment={}", task.getId(), item.id());
			}
		}
		for (TaskFile row : files.findByTask_IdAndSource(task.getId(), EvidenceSource.JIRA)) {
			if (row.getExternalId() == null || !keep.contains(row.getExternalId())) {
				try {
					storage.delete(task.getId(), row.getId());
				} catch (IOException ignored) {
					// orphan file is acceptable; row must still go
				}
				files.delete(row);
			}
		}
	}

	private static TaskStatus mapStatus(String category, String name) {
		String haystack = ((category == null ? "" : category) + " " + (name == null ? "" : name)).toLowerCase(Locale.ROOT);
		if (haystack.contains("done") || haystack.contains("complete")) {
			return TaskStatus.DONE;
		}
		if (haystack.contains("review")) {
			return TaskStatus.IN_REVIEW;
		}
		if (haystack.contains("block")) {
			return TaskStatus.BLOCKED;
		}
		if (haystack.contains("progress") || haystack.contains("indeterminate")) {
			return TaskStatus.IN_PROGRESS;
		}
		return TaskStatus.TODO;
	}

	private static TaskType mapType(String name) {
		if (name == null) {
			return TaskType.TASK;
		}
		return switch (name.toLowerCase(Locale.ROOT)) {
			case "story" -> TaskType.STORY;
			case "bug" -> TaskType.BUG;
			case "epic" -> TaskType.EPIC;
			case "sub-task", "subtask" -> TaskType.SUBTASK;
			default -> TaskType.TASK;
		};
	}

	private static boolean startsWithMzOrElf(byte[] content) {
		return (content.length >= 2 && content[0] == 'M' && content[1] == 'Z')
				|| (content.length >= 4
						&& content[0] == 0x7f
						&& content[1] == 'E'
						&& content[2] == 'L'
						&& content[3] == 'F');
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		} catch (Exception ex) {
			throw new IllegalStateException("SHA-256 unavailable", ex);
		}
	}
}

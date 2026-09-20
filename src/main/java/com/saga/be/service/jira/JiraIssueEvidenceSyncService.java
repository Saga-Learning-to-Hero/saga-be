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
import com.saga.be.persistence.JdbcTransactionGuard;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Jira evidence sync. Provider HTTP (search, issue GET, remote links, attachment download) never
 * runs while a JDBC transaction is open. Each issue persists in a short TX; attachment bytes are
 * downloaded outside TX and written after the file row commits.
 */
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
	private final TransactionTemplate writes;

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
		this(integrations, tokens, jira, tasks, links, files, attachments, fileProperties, mapper, null);
	}

	@Autowired
	public JiraIssueEvidenceSyncService(
			JiraIntegrationRepository integrations,
			JiraTeamTokenService tokens,
			JiraCloudWorkClient jira,
			TaskRepository tasks,
			TaskWebLinkRepository links,
			TaskFileRepository files,
			TaskAttachmentRepository attachments,
			TaskFileProperties fileProperties,
			ObjectMapper mapper,
			PlatformTransactionManager transactionManager) {
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
		this.writes = transactionManager == null ? null : new TransactionTemplate(transactionManager);
	}

	public void handleWebhook(String rawJson) {
		try {
			JsonNode root = mapper.readTree(rawJson == null ? "{}" : rawJson);
			IssueRef ref = JiraIssueEvidenceParser.fromWebhook(root).orElse(null);
			if (ref == null || ref.projectId() == null) {
				return;
			}
			JiraIntegration match = resolveWebhookSource(root, ref.projectId(), ref.projectKey());
			if (match == null) {
				return;
			}
			syncIssue(match, ref.issueId() != null ? ref.issueId() : ref.issueKey());
		} catch (Exception ex) {
			log.warn("jira evidence webhook skipped: {}", ex.getMessage());
		}
	}

	/**
	 * Fail-closed unique source for evidence webhooks. Same order as projection:
	 * matchedWebhookIds → cloudId+jiraProjectId → jiraProjectId+projectKey. Never pick first.
	 */
	private JiraIntegration resolveWebhookSource(JsonNode root, String jiraProjectId, String projectKey) {
		List<String> webhookIds = extractMatchedWebhookIds(root);
		if (!webhookIds.isEmpty()) {
			Map<UUID, JiraIntegration> unique = new java.util.LinkedHashMap<>();
			for (String webhookId : webhookIds) {
				for (JiraIntegration row :
						integrations.findFetchedActiveByWebhookId(
								com.saga.be.entity.enums.IntegrationStatus.ACTIVE, webhookId)) {
					unique.putIfAbsent(row.getId(), row);
				}
			}
			if (unique.size() > 1) {
				log.warn("jira evidence webhook skipped: JIRA_WEBHOOK_SOURCE_AMBIGUOUS routing=matchedWebhookIds");
				return null;
			}
			if (unique.size() == 1) {
				return unique.values().iterator().next();
			}
		}
		String cloudId = extractCloudId(root);
		if (cloudId != null && !cloudId.isBlank()) {
			List<JiraIntegration> byCloud = integrations.findFetchedActiveByCloudAndJiraProject(
					com.saga.be.entity.enums.IntegrationStatus.ACTIVE, cloudId, jiraProjectId);
			if (byCloud.isEmpty()) {
				return null;
			}
			if (byCloud.size() > 1) {
				log.warn("jira evidence webhook skipped: JIRA_WEBHOOK_SOURCE_AMBIGUOUS routing=cloud+jiraProject");
				return null;
			}
			return byCloud.getFirst();
		}
		if (projectKey == null || projectKey.isBlank()) {
			log.warn("jira evidence webhook skipped: JIRA_WEBHOOK_SOURCE_AMBIGUOUS reason=no_cloud_or_key");
			return null;
		}
		List<JiraIntegration> candidates = integrations.findFetchedActiveByJiraProject(
				com.saga.be.entity.enums.IntegrationStatus.ACTIVE, jiraProjectId, projectKey);
		if (candidates.isEmpty()) {
			return null;
		}
		java.util.Set<String> clouds = new java.util.LinkedHashSet<>();
		for (JiraIntegration row : candidates) {
			if (row.getCloudId() != null && !row.getCloudId().isBlank()) {
				clouds.add(row.getCloudId());
			}
		}
		if (clouds.size() > 1 || candidates.size() > 1) {
			log.warn("jira evidence webhook skipped: JIRA_WEBHOOK_SOURCE_AMBIGUOUS routing=jiraProject+key");
			return null;
		}
		return candidates.getFirst();
	}

	private static List<String> extractMatchedWebhookIds(JsonNode root) {
		List<String> out = new ArrayList<>();
		if (root == null) {
			return out;
		}
		JsonNode node = root.path("matchedWebhookIds");
		if (node == null || !node.isArray()) {
			return out;
		}
		for (JsonNode item : node) {
			if (item == null || item.isNull() || item.isMissingNode()) {
				continue;
			}
			if (item.isNumber()) {
				out.add(String.valueOf(item.asLong()));
			} else {
				String text = item.asText(null);
				if (text != null && !text.isBlank()) {
					out.add(text.trim());
				}
			}
		}
		return out;
	}

	private static String extractCloudId(JsonNode root) {
		if (root == null) {
			return null;
		}
		String cloudId = textField(root, "cloudId");
		if (cloudId != null) {
			return cloudId;
		}
		JsonNode nested = root.path("cloud");
		if (!nested.isMissingNode() && !nested.isNull()) {
			cloudId = textField(nested, "id");
			if (cloudId != null) {
				return cloudId;
			}
			return textField(nested, "cloudId");
		}
		return null;
	}

	private static String textField(JsonNode node, String field) {
		if (node == null || node.isMissingNode() || node.isNull()) {
			return null;
		}
		JsonNode value = node.path(field);
		if (value.isMissingNode() || value.isNull()) {
			return null;
		}
		String text = value.asText(null);
		return text == null || text.isBlank() ? null : text;
	}

	public int syncIntegration(JiraIntegration integration) {
		if (integration.getCloudId() == null || integration.getProjectKey() == null) {
			return 0;
		}
		String access = tokens.accessToken(integration);
		int processed = 0;
		String nextPageToken = null;
		while (true) {
			JdbcTransactionGuard.requireInactive("jira evidence search");
			JiraCloudWorkClient.EvidenceSearchPage page =
					jira.searchIssues(access, integration.getCloudId(), integration.getProjectKey(), nextPageToken, 50);
			if (page.issues().isEmpty()) {
				break;
			}
			for (JsonNode issue : page.issues()) {
				syncFetchedIssue(integration, access, issue);
				processed++;
			}
			if (page.last() || page.nextPageToken() == null) {
				break;
			}
			nextPageToken = page.nextPageToken();
		}
		return processed;
	}

	public void syncIssue(JiraIntegration integration, String issueIdOrKey) {
		String access = tokens.accessToken(integration);
		JdbcTransactionGuard.requireInactive("jira evidence issue get");
		JsonNode issue = jira.getIssue(access, integration.getCloudId(), issueIdOrKey);
		syncFetchedIssue(integration, access, issue);
	}

	private void syncFetchedIssue(JiraIntegration integration, String access, JsonNode issue) {
		IssueRef ref = JiraIssueEvidenceParser.fromIssue(issue).orElse(null);
		if (ref == null || ref.issueId() == null) {
			return;
		}
		JdbcTransactionGuard.requireInactive("jira evidence remote links");
		JsonNode remote = jira.listRemoteLinks(access, integration.getCloudId(), ref.issueId());
		List<RemoteLink> remoteLinks = JiraIssueEvidenceParser.remoteLinks(remote);
		List<Attachment> jiraFiles = JiraIssueEvidenceParser.attachments(issue);
		IssuePersist persisted = inTx(() -> persistIssueCore(integration, issue, ref, remoteLinks, jiraFiles));
		if (persisted == null) {
			return;
		}
		for (Attachment item : jiraFiles) {
			if (persisted.skipDownloadIds().contains(item.id())) {
				continue;
			}
			if (item.sizeBytes() > fileProperties.getMaxBytes()) {
				log.info("jira attachment skipped size task={} attachment={}", persisted.taskId(), item.id());
				continue;
			}
			byte[] content;
			try {
				JdbcTransactionGuard.requireInactive("jira evidence attachment download");
				content = jira.downloadAttachment(access, integration.getCloudId(), item.id());
			} catch (RuntimeException ex) {
				log.warn(
						"jira attachment download failed task={} attachment={}: {}",
						persisted.taskId(),
						item.id(),
						ex.getMessage());
				continue;
			}
			persistDownloadedFile(persisted.taskId(), item, content);
		}
		List<UUID> prunedFileIds = inTx(() -> pruneFileRows(persisted.taskId(), persisted.keepFileIds()));
		for (UUID fileId : prunedFileIds) {
			try {
				storage.delete(persisted.taskId(), fileId);
			} catch (IOException ignored) {
				// orphan file is acceptable after the row is gone
			}
		}
	}

	private IssuePersist persistIssueCore(
			JiraIntegration integration, JsonNode issue, IssueRef ref, List<RemoteLink> remoteLinks, List<Attachment> jiraFiles) {
		Task task = upsertTask(integration, issue, ref);
		syncLinks(task, remoteLinks);
		syncAttachmentMetadata(task, jiraFiles);
		Set<String> keep = new HashSet<>();
		Set<String> skipDownload = new HashSet<>();
		for (Attachment item : jiraFiles) {
			keep.add(item.id());
			TaskFile existing = files.findByTask_IdAndExternalId(task.getId(), item.id()).orElse(null);
			if (existing != null
					&& existing.getSizeBytes() == item.sizeBytes()
					&& existing.getId() != null
					&& storage.exists(task.getId(), existing.getId())) {
				skipDownload.add(item.id());
			}
		}
		return new IssuePersist(task.getId(), keep, skipDownload);
	}

	private void persistDownloadedFile(UUID taskId, Attachment item, byte[] content) {
		if (content.length == 0 || startsWithMzOrElf(content)) {
			return;
		}
		String sanitized;
		try {
			sanitized = TaskFileTypes.sanitizeFilename(item.filename());
		} catch (RuntimeException ex) {
			sanitized = "jira-attachment-" + item.id();
		}
		final String filename = sanitized;
		final String mime =
				item.mimeType() == null || item.mimeType().isBlank() ? "application/octet-stream" : item.mimeType();
		final String hash = sha256(content);
		UUID fileId = inTx(() -> {
			Task task = tasks.findById(taskId).orElse(null);
			if (task == null) {
				return null;
			}
			if (files.findByTask_IdAndContentHash(task.getId(), hash).isPresent()
					&& files.findByTask_IdAndExternalId(task.getId(), item.id()).isEmpty()) {
				return null;
			}
			TaskFile existing = files.findByTask_IdAndExternalId(task.getId(), item.id()).orElse(null);
			if (existing != null && existing.getId() != null) {
				return existing.getId();
			}
			return UUID.randomUUID();
		});
		if (fileId == null) {
			return;
		}
		try {
			JdbcTransactionGuard.requireInactive("jira evidence attachment store");
			storage.write(taskId, fileId, content);
		} catch (IOException ex) {
			log.warn("jira attachment store failed task={} attachment={}", taskId, item.id());
			return;
		}
		try {
			inTx(() -> {
				Task task = tasks.findById(taskId).orElse(null);
				if (task == null) {
					throw new IllegalStateException("task missing after attachment store");
				}
				TaskFile row = files.findByTask_IdAndExternalId(task.getId(), item.id()).orElseGet(TaskFile::new);
				if (row.getId() == null) {
					row.setId(fileId);
					row.setTask(task);
					row.setExternalId(item.id());
					row.setSource(EvidenceSource.JIRA);
				}
				row.setOriginalFilename(filename);
				row.setMimeType(mime);
				row.setSizeBytes(content.length);
				row.setContentHash(hash);
				row.setSource(EvidenceSource.JIRA);
				files.save(row);
				return null;
			});
		} catch (RuntimeException ex) {
			try {
				storage.delete(taskId, fileId);
			} catch (IOException ignored) {
				// orphan bytes are acceptable; no TaskFile row was committed
			}
			log.warn("jira attachment persist failed task={} attachment={}", taskId, item.id());
		}
	}

	private List<UUID> pruneFileRows(UUID taskId, Set<String> keep) {
		List<UUID> pruned = new ArrayList<>();
		for (TaskFile row : files.findByTask_IdAndSource(taskId, EvidenceSource.JIRA)) {
			if (row.getExternalId() == null || !keep.contains(row.getExternalId())) {
				pruned.add(row.getId());
				files.delete(row);
			}
		}
		return pruned;
	}

	private Task upsertTask(JiraIntegration integration, JsonNode issue, IssueRef ref) {
		Project project = integration.getProject();
		Task task = tasks.findByJiraIntegration_IdAndExternalId(integration.getId(), ref.issueId()).orElseGet(Task::new);
		task.setProject(project);
		task.setJiraIntegration(integration);
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

	private <T> T inTx(java.util.function.Supplier<T> action) {
		if (writes == null) {
			return action.get();
		}
		return writes.execute(status -> action.get());
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

	private record IssuePersist(UUID taskId, Set<String> keepFileIds, Set<String> skipDownloadIds) {}
}

package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiEvidenceReference;
import com.saga.be.ai.AiFinding;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.dto.ai.CommitAiReviewDtos.BackfillResult;
import com.saga.be.dto.ai.CommitAiReviewDtos.CodeReview;
import com.saga.be.dto.ai.CommitAiReviewDtos.Coverage;
import com.saga.be.dto.ai.CommitAiReviewDtos.Detail;
import com.saga.be.dto.ai.CommitAiReviewDtos.Finding;
import com.saga.be.dto.ai.CommitAiReviewDtos.LinkedTask;
import com.saga.be.dto.ai.CommitAiReviewDtos.Location;
import com.saga.be.dto.ai.CommitAiReviewDtos.MessageReview;
import com.saga.be.dto.ai.CommitAiReviewDtos.Summary;
import com.saga.be.dto.ai.CommitAiReviewDtos.TaskAlignment;
import com.saga.be.dto.ai.CommitAiReviewDtos.TaskReview;
import com.saga.be.entity.ai.AiAnalysisEvidence;
import com.saga.be.entity.ai.AiAnalysisProviderDecision;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiEvidenceType;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskCommitManualLink;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiAnalysisEvidenceRepository;
import com.saga.be.repository.AiAnalysisProviderDecisionRepository;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskCommitManualLinkRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the AI review of commits for display: the badge next to each commit and the detailed panel
 * (what is wrong in the message, which diff lines have which problem, which task matches or not).
 * Every location shown comes from the evidence the AI was actually given; nothing is invented here.
 */
@Service
@Profile("!test")
public class CommitAiReviewService {

	static final int MAX_BACKFILL = 15;
	private static final int BACKFILL_CANDIDATES = 100;
	private static final int MAX_SNIPPET = 4_000;
	private static final Logger log = LoggerFactory.getLogger(CommitAiReviewService.class);

	private final ProjectDataAuthorization authorization;
	private final GitCommitRepository commits;
	private final AiAnalysisRunRepository runs;
	private final AiAnalysisProviderDecisionRepository decisions;
	private final AiAnalysisEvidenceRepository evidence;
	private final TaskGitCommitLinkRepository links;
	private final TaskCommitManualLinkRepository manualLinks;
	private final TeamAiCredentialService teamKeys;
	private final TeamMemberRepository members;
	private final AiAnalysisSubmissionService submissions;
	private final ObjectMapper mapper;
	private com.saga.be.repository.ProjectRepository projects;

	/** Who may (re)request a review; absent in slice tests (then only merge / key rules apply). */
	private CommitReviewPermission reviewPermission;

	@org.springframework.beans.factory.annotation.Autowired(required = false)
	public void setReviewPermission(CommitReviewPermission reviewPermission) {
		this.reviewPermission = reviewPermission;
	}

	/** READ_ONLY (lecturer), NOT_ALLOWED (a member, not their task), NO_KEY, or null when allowed. */
	private String blockedFor(UUID userId, UUID projectId, UUID commitId, String keySource) {
		CommitReviewPermission.Access access = reviewPermission == null
				? CommitReviewPermission.Access.ALLOWED
				: reviewPermission.access(userId, projectId, commitId);
		if (access == CommitReviewPermission.Access.READ_ONLY) return "READ_ONLY";
		if (access == CommitReviewPermission.Access.NOT_ALLOWED) return "NOT_ALLOWED";
		return "NONE".equals(keySource) ? "NO_KEY" : null;
	}

	/** Where backfill submissions run (GitHub reads take seconds each); inline unless Spring sets it. */
	private java.util.concurrent.Executor background = Runnable::run;

	@org.springframework.beans.factory.annotation.Autowired
	public void setProjects(com.saga.be.repository.ProjectRepository projects) {
		this.projects = projects;
	}

	@org.springframework.beans.factory.annotation.Autowired
	public void setBackground(@org.springframework.beans.factory.annotation.Qualifier("aiAutomationExecutor") java.util.concurrent.Executor background) {
		this.background = background;
	}

	public CommitAiReviewService(
			ProjectDataAuthorization authorization,
			GitCommitRepository commits,
			AiAnalysisRunRepository runs,
			AiAnalysisProviderDecisionRepository decisions,
			AiAnalysisEvidenceRepository evidence,
			TaskGitCommitLinkRepository links,
			TaskCommitManualLinkRepository manualLinks,
			TeamAiCredentialService teamKeys,
			TeamMemberRepository members,
			AiAnalysisSubmissionService submissions,
			ObjectMapper mapper) {
		this.authorization = authorization;
		this.commits = commits;
		this.runs = runs;
		this.decisions = decisions;
		this.evidence = evidence;
		this.links = links;
		this.manualLinks = manualLinks;
		this.teamKeys = teamKeys;
		this.members = members;
		this.submissions = submissions;
		this.mapper = mapper;
	}

	/** Key the next review would use: TEAM | COURSE | NONE. Never throws. */
	public String keySource(UUID projectId, UUID courseId) {
		if (teamKeys.usableKey(projectId).isPresent()) return "TEAM";
		return teamKeys.courseKeyUsable(projectId, courseId, AiInvocationOrigin.USER_REQUEST) ? "COURSE" : "NONE";
	}

	// ---------------------------------------------------------------- list badges

	/** Badge per commit, inside the caller's read transaction (callers already checked access). */
	public Map<UUID, Summary> summaries(UUID projectId, UUID courseId, Collection<GitCommit> page) {
		if (page == null || page.isEmpty()) return Map.of();
		List<UUID> ids = page.stream().map(GitCommit::getId).toList();
		Map<UUID, AiAnalysisRun> latest = latestRuns(projectId, ids);
		Map<UUID, AiAnalysisProviderDecision> decisionByRun = decisionsOf(latest.values());
		Map<UUID, List<String>> keysByCommit = linkedKeys(projectId, ids);
		Set<UUID> linked = keysByCommit.keySet();
		boolean keyAvailable = !"NONE".equals(keySource(projectId, courseId));
		Map<UUID, Summary> out = new HashMap<>();
		for (GitCommit commit : page) {
			AiAnalysisRun run = latest.get(commit.getId());
			AiStructuredResult result = run == null || run.getStatus() != AiAnalysisStatus.COMPLETED ? null : parse(decisionByRun.get(run.getId()));
			var outcome = CommitAiReviewRules.evaluate(commit.looksLikeMerge(), run == null ? null : run.getStatus(), result,
					linked.contains(commit.getId()), keyAvailable,
					new CommitAiReviewRules.MessageContext(commit.getMessage(), commit.getHeadRef(), keysByCommit.getOrDefault(commit.getId(), List.of())));
			out.put(commit.getId(), new Summary(outcome.status(), outcome.label(), outcome.reasons(), linked.contains(commit.getId())));
		}
		return out;
	}

	// ---------------------------------------------------------------- detail panel

	@Transactional(readOnly = true)
	public Detail detail(UUID userId, UUID projectId, UUID commitId) {
		authorization.requireReader(userId, projectId);
		GitCommit commit = requireCommit(projectId, commitId);
		UUID courseId = projects.findCourseIdById(projectId).orElse(null);
		AiAnalysisRun run = latestRuns(projectId, List.of(commitId)).get(commitId);
		AiAnalysisProviderDecision decision = run == null ? null : decisions.findByAnalysisRun_Id(run.getId()).orElse(null);
		AiStructuredResult result = run == null || run.getStatus() != AiAnalysisStatus.COMPLETED ? null : parse(decision);
		boolean canManageLinks = canManageLinks(userId, projectId, commit);
		List<LinkedTask> linkedTasks = linkedTasks(projectId, commit, canManageLinks);
		String keySource = keySource(projectId, courseId);
		boolean merge = commit.looksLikeMerge();
		var outcome = CommitAiReviewRules.evaluate(merge, run == null ? null : run.getStatus(), result, !linkedTasks.isEmpty(), !"NONE".equals(keySource),
				new CommitAiReviewRules.MessageContext(commit.getMessage(), commit.getHeadRef(), linkedTasks.stream().map(LinkedTask::externalKey).toList()));
		Map<UUID, AiAnalysisEvidence> rows = new LinkedHashMap<>();
		if (run != null) for (AiAnalysisEvidence row : evidence.findByAnalysisRun_IdOrderByOrdinalIndexAsc(run.getId())) rows.put(row.getId(), row);
		String blocked = merge ? "MERGE" : blockedFor(userId, projectId, commitId, keySource);
		return new Detail(
				commit.getId(),
				commit.getShaHash(),
				commit.getMessage(),
				merge,
				outcome.status(),
				outcome.label(),
				outcome.headline(),
				outcome.reasons(),
				blocked == null,
				blocked,
				keySource,
				canManageLinks,
				run == null ? null : run.getId(),
				run == null ? null : vietnamTime(run.getCompletedAt()),
				decision == null || decision.getAiProvider() == null ? null : decision.getAiProvider().name(),
				decision == null ? null : decision.getModelId(),
				result == null ? null : CommitAiReviewRules.presentMessage(
						messageReview(result, rows), commit.getMessage(), commit.getHeadRef(),
						linkedTasks.stream().map(LinkedTask::externalKey).toList()),
				result == null ? null : codeReview(result, rows),
				taskReview(result, rows, linkedTasks),
				run == null ? null : AiAnalysisReadService.failure(run, decision));
	}

	/** (Re)request the review of one commit, then return the panel. Called without a transaction:
	 * {@link #detail} must therefore never load anything lazily (it reads the course id by query). */
	public Detail request(UUID userId, UUID projectId, UUID commitId) {
		submissions.submit(userId, projectId, commitId);
		return detail(userId, projectId, commitId);
	}

	/**
	 * Leader-only: review up to 15 recent commits that have no review yet, whose last one failed, or whose
	 * last one was made with an older prompt (so results stop mixing AI versions), e.g. commits pushed
	 * before the team entered its key. A review still queued or running is left alone. Merge commits are skipped. Uses the team
	 * key, or the course key only when the lecturer allows it (course AI automation on).
	 * The commits are picked here; reading their diffs and queueing the reviews happens in the
	 * background, so the request returns at once (each GitHub read takes seconds).
	 */
	public BackfillResult backfill(UUID userId, UUID projectId, Integer limit) {
		authorization.requireStudentLeader(userId, projectId);
		int max = limit == null ? MAX_BACKFILL : Math.max(1, Math.min(MAX_BACKFILL, limit));
		List<UUID> recent = commits.findPageIdsByProject(projectId, PageRequest.of(0, BACKFILL_CANDIDATES)).getContent();
		if (recent.isEmpty()) return new BackfillResult(0, 0, 0);
		// Read without lazy loading: backfill runs outside a transaction (each submission opens its own).
		UUID courseId = projects.findCourseIdById(projectId).orElse(null);
		if (teamKeys.usableKey(projectId).isEmpty() && !teamKeys.courseFallbackAvailable(projectId, courseId)) {
			throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_UNAVAILABLE, HttpStatus.CONFLICT,
					"Nhóm chưa nhập key AI và giảng viên chưa cho dùng key của lớp, nên chưa đánh giá hàng loạt được.");
		}
		Map<UUID, GitCommit> byId = new HashMap<>();
		for (GitCommit commit : commits.findFetchedByIdIn(recent)) byId.put(commit.getId(), commit);
		Map<UUID, AiAnalysisRun> latest = latestRuns(projectId, recent);
		List<UUID> picked = new ArrayList<>();
		int skipped = 0;
		for (UUID id : recent) {
			if (picked.size() >= max) break;
			GitCommit commit = byId.get(id);
			AiAnalysisRun run = latest.get(id);
			if (commit == null || commit.looksLikeMerge() || !needsReview(run)) {
				skipped++;
				continue;
			}
			picked.add(id);
		}
		return queueBackfill(userId, projectId, picked, skipped);
	}

	/** No review, a failed one, or a finished one made with an older prompt; never one still in progress. */
	static boolean needsReview(AiAnalysisRun run) {
		if (run == null) return true;
		return switch (run.getStatus()) {
			case QUEUED, RUNNING -> false;
			case COMPLETED -> !AiAnalysisSubmissionService.PROMPT_VERSION.equals(run.getPromptVersion());
			default -> true;
		};
	}

	private BackfillResult queueBackfill(UUID userId, UUID projectId, List<UUID> picked, int skipped) {
		if (picked.isEmpty()) return new BackfillResult(0, skipped, 0);
		try {
			background.execute(() -> submitAll(userId, projectId, picked));
		} catch (java.util.concurrent.RejectedExecutionException ex) {
			log.warn("commit review backfill queue full projectId={}", projectId);
			return new BackfillResult(0, skipped, picked.size());
		}
		return new BackfillResult(picked.size(), skipped, 0);
	}

	private void submitAll(UUID userId, UUID projectId, List<UUID> commitIds) {
		int failed = 0;
		for (UUID id : commitIds) {
			try {
				submissions.submit(userId, projectId, id);
			} catch (RuntimeException ex) {
				failed++;
				log.warn("commit review backfill failed projectId={} gitCommitId={} type={}", projectId, id, ex.getClass().getSimpleName());
			}
		}
		log.info("commit review backfill done projectId={} submitted={} failed={}", projectId, commitIds.size() - failed, failed);
	}

	// ---------------------------------------------------------------- pieces

	boolean canManageLinks(UUID userId, UUID projectId, GitCommit commit) {
		RoleInTeam role = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
		if (role == null) return false;
		if (role == RoleInTeam.LEADER) return true;
		return commit.getAuthorStudent() != null && commit.getAuthorStudent().getUserAccount() != null
				&& userId.equals(commit.getAuthorStudent().getUserAccount().getId());
	}

	private List<LinkedTask> linkedTasks(UUID projectId, GitCommit commit, boolean canManage) {
		List<LinkedTask> out = new ArrayList<>();
		Set<UUID> seen = new HashSet<>();
		for (TaskGitCommitLink link : links.findLiveWithTaskByGitCommitIds(List.of(commit.getId()))) {
			Task task = link.getTask();
			if (task.getProject() == null || !projectId.equals(task.getProject().getId()) || !seen.add(task.getId())) continue;
			out.add(new LinkedTask(task.getId(), task.getExternalKey(), task.getTitle(), task.getStatus() == null ? null : task.getStatus().name(), "AUTO", false));
		}
		for (TaskCommitManualLink link : manualLinks.findFetchedByProjectAndCommitIds(projectId, List.of(commit.getId()))) {
			Task task = link.getTask();
			if (!seen.add(task.getId())) continue;
			out.add(new LinkedTask(task.getId(), task.getExternalKey(), task.getTitle(), task.getStatus() == null ? null : task.getStatus().name(), "MANUAL", canManage));
		}
		return out;
	}

	/** Task keys each commit is attached to right now (automatic or manual); a commit absent here has no task. */
	private Map<UUID, List<String>> linkedKeys(UUID projectId, List<UUID> ids) {
		Map<UUID, List<String>> keys = new HashMap<>();
		for (TaskGitCommitLink link : links.findLiveWithTaskByGitCommitIds(ids)) {
			if (link.getTask().getProject() != null && projectId.equals(link.getTask().getProject().getId())) {
				keys.computeIfAbsent(link.getGitCommit().getId(), ignored -> new ArrayList<>()).add(link.getTask().getExternalKey());
			}
		}
		for (TaskCommitManualLink link : manualLinks.findFetchedByProjectAndCommitIds(projectId, ids)) {
			keys.computeIfAbsent(link.getGitCommit().getId(), ignored -> new ArrayList<>()).add(link.getTask().getExternalKey());
		}
		return keys;
	}

	private static final java.time.ZoneId VIETNAM = java.time.ZoneId.of("Asia/Ho_Chi_Minh");

	/** Stored times are the server's local time (UTC on Railway); without an offset the browser read them
	 * as its own local time, 7 hours off. */
	static java.time.OffsetDateTime vietnamTime(java.time.LocalDateTime stored) {
		return stored == null ? null : stored.atZone(java.time.ZoneId.systemDefault()).withZoneSameInstant(VIETNAM).toOffsetDateTime();
	}

	private Map<UUID, AiAnalysisRun> latestRuns(UUID projectId, List<UUID> commitIds) {
		Map<UUID, AiAnalysisRun> latest = new HashMap<>();
		for (AiAnalysisRun run : runs.findCommitReviewRuns(projectId, commitIds)) latest.putIfAbsent(run.getArtifactId(), run);
		return latest;
	}

	private Map<UUID, AiAnalysisProviderDecision> decisionsOf(Collection<AiAnalysisRun> latest) {
		List<UUID> completed = latest.stream().filter(r -> r.getStatus() == AiAnalysisStatus.COMPLETED).map(AiAnalysisRun::getId).toList();
		if (completed.isEmpty()) return Map.of();
		Map<UUID, AiAnalysisProviderDecision> out = new HashMap<>();
		for (AiAnalysisProviderDecision decision : decisions.findByAnalysisRun_IdIn(completed)) out.put(decision.getAnalysisRun().getId(), decision);
		return out;
	}

	AiStructuredResult parse(AiAnalysisProviderDecision decision) {
		if (decision == null || decision.getStructuredResultJson() == null || decision.getStructuredResultJson().isBlank()) return null;
		try {
			return mapper.readValue(decision.getStructuredResultJson(), AiStructuredResult.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private MessageReview messageReview(AiStructuredResult result, Map<UUID, AiAnalysisEvidence> rows) {
		var message = result.commitMessageAssessment();
		return new MessageReview(
				message.verdict() == null ? null : message.verdict().name(),
				CommitAiReviewRules.messageVerdictLabel(message.verdict()),
				message.score(),
				message.summary(),
				message.suggestedMessage(),
				findings(message.findings(), rows));
	}

	private CodeReview codeReview(AiStructuredResult result, Map<UUID, AiAnalysisEvidence> rows) {
		var code = result.codeAssessment();
		return new CodeReview(
				code.verdict() == null ? null : code.verdict().name(),
				CommitAiReviewRules.codeVerdictLabel(code.verdict()),
				code.confidence(),
				findings(code.findings(), rows),
				coverage(rows));
	}

	private TaskReview taskReview(AiStructuredResult result, Map<UUID, AiAnalysisEvidence> rows, List<LinkedTask> linkedTasks) {
		List<TaskAlignment> alignments = new ArrayList<>();
		if (result != null && result.taskAlignments() != null) {
			Map<UUID, JsonNode> taskRows = new HashMap<>();
			for (AiAnalysisEvidence row : rows.values()) {
				if (row.getEvidenceType() != AiEvidenceType.TASK_FIELD) continue;
				JsonNode payload = json(row.getPayloadJson());
				String id = payload.path("taskId").asText(null);
				if (id != null) try { taskRows.put(UUID.fromString(id), payload); } catch (IllegalArgumentException ignored) { }
			}
			for (var alignment : result.taskAlignments()) {
				if (alignment == null) continue;
				JsonNode task = taskRows.get(alignment.taskId());
				alignments.add(new TaskAlignment(
						alignment.taskId(),
						alignment.externalKey() != null ? alignment.externalKey() : task == null ? null : task.path("externalKey").asText(null),
						task == null ? null : task.path("title").asText(null),
						alignment.verdict() == null ? null : alignment.verdict().name(),
						CommitAiReviewRules.taskVerdictLabel(alignment.verdict()),
						alignment.confidence(),
						alignment.summary()));
			}
		}
		var verdict = result == null ? null : result.taskAlignmentSummary();
		return new TaskReview(verdict == null ? null : verdict.name(), CommitAiReviewRules.taskVerdictLabel(verdict), alignments, linkedTasks);
	}

	private List<Finding> findings(List<AiFinding> findings, Map<UUID, AiAnalysisEvidence> rows) {
		if (findings == null) return List.of();
		List<Finding> out = new ArrayList<>();
		for (AiFinding finding : findings) {
			if (finding == null) continue;
			List<Location> locations = new ArrayList<>();
			Set<UUID> seen = new HashSet<>();
			if (finding.evidence() != null) {
				for (AiEvidenceReference ref : finding.evidence()) {
					if (ref == null || ref.evidenceId() == null || !seen.add(ref.evidenceId())) continue;
					AiAnalysisEvidence row = rows.get(ref.evidenceId());
					if (row != null) locations.add(location(row));
				}
			}
			out.add(new Finding(finding.code(), finding.message(), locations));
		}
		return out;
	}

	private Location location(AiAnalysisEvidence row) {
		JsonNode payload = json(row.getPayloadJson());
		AiEvidenceType type = row.getEvidenceType();
		if (type == AiEvidenceType.DIFF_HUNK || type == AiEvidenceType.CODE_RANGE) {
			String path = payload.path("path").asText(null);
			String hunk = payload.path("hunkId").asText(null);
			return new Location("DIFF_HUNK", path, hunk, cut(payload.path("patch").asText(null)), path == null ? "Code" : path + (hunk == null ? "" : " · " + hunk));
		}
		if (type == AiEvidenceType.COMMIT_MESSAGE) {
			return new Location("COMMIT_MESSAGE", null, null, cut(payload.path("message").asText(null)), "Tên commit");
		}
		if (type == AiEvidenceType.TASK_FIELD) {
			String key = payload.path("externalKey").asText("");
			String title = payload.path("title").asText("");
			return new Location("TASK_FIELD", null, null, cut(payload.path("description").asText(null)), (key + " " + title).trim());
		}
		if (type == AiEvidenceType.SYLLABUS_PHASE || type == AiEvidenceType.SYLLABUS_DELIVERABLE || type == AiEvidenceType.SYLLABUS_VERSION || type == AiEvidenceType.SYLLABUS_NODE) {
			String code = payload.path("code").asText(payload.path("versionLabel").asText(""));
			String name = payload.path("name").asText("");
			return new Location("SYLLABUS", null, null, cut(payload.path("description").asText(null)), ("Syllabus " + code + " " + name).trim());
		}
		return new Location("OTHER", null, null, null, type == null ? null : type.name());
	}

	private Coverage coverage(Map<UUID, AiAnalysisEvidence> rows) {
		for (AiAnalysisEvidence row : rows.values()) {
			if (row.getEvidenceType() != AiEvidenceType.CHANGED_FILE_MANIFEST) continue;
			JsonNode manifest = json(row.getPayloadJson());
			return new Coverage(
					manifest.path("filesTotal").isNumber() ? manifest.path("filesTotal").asInt() : null,
					manifest.path("filesAnalyzed").isNumber() ? manifest.path("filesAnalyzed").asInt() : null,
					manifest.path("filesOmitted").isNumber() ? manifest.path("filesOmitted").asInt() : null,
					"COMPLETE".equals(manifest.path("coverage").asText()));
		}
		return null;
	}

	private GitCommit requireCommit(UUID projectId, UUID commitId) {
		GitCommit commit = commits.findFetchedByIdIn(List.of(commitId)).stream().findFirst().orElse(null);
		if (commit == null || commit.getRepo() == null || commit.getRepo().getProject() == null || !projectId.equals(commit.getRepo().getProject().getId())) {
			throw new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND, "Không tìm thấy commit trong dự án này.");
		}
		return commit;
	}

	private JsonNode json(String raw) {
		try {
			return raw == null ? mapper.createObjectNode() : mapper.readTree(raw);
		} catch (Exception ex) {
			return mapper.createObjectNode();
		}
	}

	private static String cut(String text) {
		if (text == null) return null;
		return text.length() <= MAX_SNIPPET ? text : text.substring(0, MAX_SNIPPET) + "\n…";
	}
}

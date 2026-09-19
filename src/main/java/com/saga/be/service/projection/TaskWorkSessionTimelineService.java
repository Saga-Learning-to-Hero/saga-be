package com.saga.be.service.projection;

import com.saga.be.dto.project.TaskWorkSessionTimelineResponse;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse.CommitItem;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse.CommitsBlock;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse.SessionItem;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse.TaskSummary;
import com.saga.be.dto.project.TaskWorkSessionTimelineResponse.WorkSessionsBlock;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Project-scoped timeline of task work sessions (team-visible presence) and V23 linked coding
 * commits. Streams are independent — no session↔commit causality.
 */
@Service
@Profile("!test")
public class TaskWorkSessionTimelineService {

	/** Session history default — matches evidence preview convention. */
	static final int DEFAULT_SESSION_PAGE = 0;
	static final int DEFAULT_SESSION_SIZE = 20;
	static final int MAX_SESSION_SIZE = 100;

	/** Commit page — same contract as {@code GET .../tasks/{taskId}/commits}. */
	static final int DEFAULT_COMMIT_PAGE = ProjectProjectionReadService.DEFAULT_PAGE;
	static final int DEFAULT_COMMIT_SIZE = ProjectProjectionReadService.DEFAULT_SIZE;
	static final int MAX_COMMIT_SIZE = ProjectProjectionReadService.MAX_SIZE;

	private final ProjectDataAuthorization authorization;
	private final TaskRepository tasks;
	private final TaskWorkSessionRepository sessions;
	private final TaskGitCommitLinkRepository links;
	private final StudentProfileRepository students;

	public TaskWorkSessionTimelineService(
			ProjectDataAuthorization authorization,
			TaskRepository tasks,
			TaskWorkSessionRepository sessions,
			TaskGitCommitLinkRepository links,
			StudentProfileRepository students) {
		this.authorization = authorization;
		this.tasks = tasks;
		this.sessions = sessions;
		this.links = links;
		this.students = students;
	}

	@Transactional(readOnly = true)
	public TaskWorkSessionTimelineResponse get(
			UUID userId,
			UUID projectId,
			UUID taskId,
			Integer sessionPage,
			Integer sessionSize,
			Integer commitPage,
			Integer commitSize) {
		authorization.requireReader(userId, projectId);
		int sPage = resolvePage(sessionPage, DEFAULT_SESSION_PAGE, "sessionPage");
		int sSize = resolveSize(sessionSize, DEFAULT_SESSION_SIZE, MAX_SESSION_SIZE, "sessionSize");
		int cPage = resolvePage(commitPage, DEFAULT_COMMIT_PAGE, "commitPage");
		int cSize = resolveSize(commitSize, DEFAULT_COMMIT_SIZE, MAX_COMMIT_SIZE, "commitSize");

		Task task = tasks.findByIdAndProject_IdAndDeletedAtIsNull(taskId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Task was not found for this project."));

		LocalDateTime now = LocalDateTime.now();
		List<Object[]> bounds = sessions.findStartedAndEndedByTaskId(taskId);
		long sessionCount = bounds.size();
		long totalElapsed = 0L;
		for (Object[] row : bounds) {
			totalElapsed += elapsedSeconds((LocalDateTime) row[0], (LocalDateTime) row[1], now);
		}

		List<TaskWorkSession> openRows = sessions.findOpenFetchedByTaskId(taskId);
		Page<UUID> sessionIdPage = sessions.findPageIdsByTaskId(taskId, PageRequest.of(sPage, sSize));
		List<TaskWorkSession> pageRows = List.of();
		if (!sessionIdPage.getContent().isEmpty()) {
			Map<UUID, TaskWorkSession> byId = new HashMap<>();
			for (TaskWorkSession row : sessions.findFetchedByIdIn(sessionIdPage.getContent())) {
				byId.put(row.getId(), row);
			}
			List<TaskWorkSession> ordered = new ArrayList<>(sessionIdPage.getContent().size());
			for (UUID id : sessionIdPage.getContent()) {
				TaskWorkSession row = byId.get(id);
				if (row != null) {
					ordered.add(row);
				}
			}
			pageRows = ordered;
		}

		Map<UUID, StudentProfile> profiles = loadProfiles(openRows, pageRows);
		List<SessionItem> openItems = openRows.stream().map(row -> toSession(row, now, profiles)).toList();
		List<SessionItem> sessionItems = pageRows.stream().map(row -> toSession(row, now, profiles)).toList();
		int sessionTotalPages = sessionIdPage.getTotalPages();

		Page<UUID> linkIdPage =
				links.findPageLinkIdsByProjectAndTaskV23(projectId, taskId, PageRequest.of(cPage, cSize));
		List<CommitItem> commitItems = List.of();
		if (!linkIdPage.getContent().isEmpty()) {
			Map<UUID, TaskGitCommitLink> byLinkId = new HashMap<>();
			for (TaskGitCommitLink link : links.findFetchedWithAuthorByIdIn(linkIdPage.getContent())) {
				byLinkId.put(link.getId(), link);
			}
			List<CommitItem> ordered = new ArrayList<>(linkIdPage.getContent().size());
			for (UUID id : linkIdPage.getContent()) {
				TaskGitCommitLink link = byLinkId.get(id);
				if (link != null) {
					ordered.add(toCommit(link));
				}
			}
			commitItems = ordered;
		}

		return new TaskWorkSessionTimelineResponse(
				new TaskSummary(task.getId(), task.getExternalKey(), task.getTitle()),
				new WorkSessionsBlock(
						sessionCount,
						totalElapsed,
						openItems,
						sPage,
						sSize,
						sessionIdPage.getTotalElements(),
						sessionTotalPages,
						sessionItems),
				new CommitsBlock(
						cPage, cSize, linkIdPage.getTotalElements(), linkIdPage.getTotalPages(), commitItems));
	}

	private Map<UUID, StudentProfile> loadProfiles(List<TaskWorkSession> open, List<TaskWorkSession> page) {
		Set<UUID> userIds = new HashSet<>();
		for (TaskWorkSession row : open) {
			if (row.getUser() != null && row.getUser().getId() != null) {
				userIds.add(row.getUser().getId());
			}
		}
		for (TaskWorkSession row : page) {
			if (row.getUser() != null && row.getUser().getId() != null) {
				userIds.add(row.getUser().getId());
			}
		}
		if (userIds.isEmpty()) {
			return Map.of();
		}
		Map<UUID, StudentProfile> byUser = new HashMap<>();
		for (StudentProfile profile : students.findFetchedByUserAccount_IdIn(userIds)) {
			if (profile.getUserAccount() != null && profile.getUserAccount().getId() != null) {
				byUser.put(profile.getUserAccount().getId(), profile);
			}
		}
		return byUser;
	}

	static SessionItem toSession(TaskWorkSession session, LocalDateTime now, Map<UUID, StudentProfile> profiles) {
		UserAccount user = session.getUser();
		UUID userId = user == null ? null : user.getId();
		StudentProfile profile = userId == null ? null : profiles.get(userId);
		return new SessionItem(
				session.getId(),
				userId,
				profile == null ? null : profile.getId(),
				profile == null ? null : profile.getStudentCode(),
				user == null ? null : user.getFullName(),
				user == null ? null : user.getAvatarUrl(),
				session.getStartedAt(),
				session.getEndedAt(),
				session.getStatus() == null ? null : session.getStatus().name(),
				elapsedSeconds(session.getStartedAt(), session.getEndedAt(), now));
	}

	static CommitItem toCommit(TaskGitCommitLink link) {
		GitCommit commit = link.getGitCommit();
		return new CommitItem(
				commit.getId(),
				commit.getShaHash(),
				commit.getMessage(),
				commit.getRepo() == null ? null : commit.getRepo().getFullName(),
				commit.getAuthorStudent() == null ? null : commit.getAuthorStudent().getId(),
				commit.getCommittedAt(),
				link.getCreatedAt(),
				link.getLinkSource() == null ? null : link.getLinkSource().name());
	}

	/**
	 * Same elapsed rule as {@code TaskEvidenceService.toResponse}: use {@code endedAt} when present
	 * (including dirty OPEN+endedAt); otherwise {@code now}. Negative / inverted ranges clamp to 0.
	 */
	static long elapsedSeconds(LocalDateTime startedAt, LocalDateTime endedAt, LocalDateTime now) {
		LocalDateTime end = endedAt != null ? endedAt : now;
		if (startedAt == null || end == null || end.isBefore(startedAt)) {
			return 0L;
		}
		return Duration.between(startedAt, end).getSeconds();
	}

	private static int resolvePage(Integer page, int defaultPage, String name) {
		int resolved = page == null ? defaultPage : page;
		if (resolved < 0) {
			throw invalid(name + " must be >= 0.");
		}
		return resolved;
	}

	private static int resolveSize(Integer size, int defaultSize, int maxSize, String name) {
		int resolved = size == null ? defaultSize : size;
		if (resolved < 1 || resolved > maxSize) {
			throw invalid(name + " must be between 1 and " + maxSize + ".");
		}
		return resolved;
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.REQUEST_INVALID, HttpStatus.BAD_REQUEST, message);
	}
}

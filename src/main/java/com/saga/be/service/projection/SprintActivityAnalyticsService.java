package com.saga.be.service.projection;

import com.saga.be.dto.project.SprintActivityResponse;
import com.saga.be.dto.project.SprintActivityResponse.CommitCounts;
import com.saga.be.dto.project.SprintActivityResponse.SprintActivity;
import com.saga.be.dto.project.SprintActivityResponse.TaskCounts;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only per-sprint Task/Commit activity. Students always see their own assigned tasks and
 * authored commits. Assigned lecturers see project-wide totals, or one ACTIVE member when
 * {@code studentId} is supplied. Ordinary members cannot inspect a teammate. ADMIN is denied —
 * same product-data policy as {@link ProjectDataAuthorization#requireReader}.
 */
@Service
@Profile("!test")
public class SprintActivityAnalyticsService {

	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final TaskGitCommitLinkRepository links;
	private final SprintRepository sprints;
	private final StudentProfileRepository students;
	private final TeamMemberRepository members;
	private final UserAccountRepository users;
	private final ProjectDataAuthorization authorization;

	public SprintActivityAnalyticsService(
			TaskRepository tasks,
			GitCommitRepository commits,
			TaskGitCommitLinkRepository links,
			SprintRepository sprints,
			StudentProfileRepository students,
			TeamMemberRepository members,
			UserAccountRepository users,
			ProjectDataAuthorization authorization) {
		this.tasks = tasks;
		this.commits = commits;
		this.links = links;
		this.sprints = sprints;
		this.students = students;
		this.members = members;
		this.users = users;
		this.authorization = authorization;
	}

	@Transactional(readOnly = true)
	public SprintActivityResponse getSprintActivity(UUID userId, UUID projectId, UUID requestedStudentId) {
		authorization.requireReader(userId, projectId);
		UUID studentId = resolveScopeStudentId(userId, projectId, requestedStudentId);

		List<Sprint> sprintRows = sprints.findActiveByProject_Id(projectId);
		Map<UUID, long[]> tasksBySprint = tasksBySprint(loadTaskRows(projectId, studentId));
		Map<UUID, Set<UUID>> linkedCommitIdsBySprint = linkedCommitIdsBySprint(projectId, studentId);
		List<Object[]> commitTimes = loadCommitTimes(projectId, studentId);

		List<SprintActivity> activity = sprintRows.stream()
				.map(sprint -> toActivity(sprint, tasksBySprint, linkedCommitIdsBySprint, commitTimes))
				.toList();
		return new SprintActivityResponse(Instant.now(), activity);
	}

	/**
	 * {@code null} means project-wide (lecturer, no studentId). Students are always scoped to their
	 * own profile — {@code requestedStudentId} is ignored so a member cannot read a teammate.
	 */
	private UUID resolveScopeStudentId(UUID userId, UUID projectId, UUID requestedStudentId) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() == AccountRole.STUDENT) {
			StudentProfile profile = students
					.findByUserAccount_Id(userId)
					.orElseThrow(() -> new IntegrationException(
							IntegrationErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, "Access denied."));
			return profile.getId();
		}
		if (requestedStudentId == null) {
			return null;
		}
		if (!members.existsActiveByProjectIdAndStudentProfileId(projectId, requestedStudentId)) {
			throw new AcademicException(
					AcademicErrorCode.TEAM_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Student is not an ACTIVE member of this project's team.");
		}
		return requestedStudentId;
	}

	private List<Object[]> loadTaskRows(UUID projectId, UUID studentId) {
		if (studentId == null) {
			return tasks.countGroupedBySprintAndStatus(projectId);
		}
		return tasks.countGroupedBySprintAndStatusForAssignee(projectId, studentId);
	}

	private Map<UUID, Set<UUID>> linkedCommitIdsBySprint(UUID projectId, UUID studentId) {
		List<Object[]> rows = studentId == null
				? links.findLinkedCommitIdsBySprint(projectId)
				: links.findLinkedCommitIdsBySprintAndAuthor(projectId, studentId);
		Map<UUID, Set<UUID>> out = new HashMap<>();
		for (Object[] row : rows) {
			UUID sprintId = (UUID) row[0];
			UUID commitId = (UUID) row[1];
			out.computeIfAbsent(sprintId, id -> new HashSet<>()).add(commitId);
		}
		return out;
	}

	private List<Object[]> loadCommitTimes(UUID projectId, UUID studentId) {
		if (studentId == null) {
			return commits.findIdAndCommittedAtByProject(projectId);
		}
		return commits.findIdAndCommittedAtByProjectAndAuthor(projectId, studentId);
	}

	/** sprintId -> {total, todo, inProgress, inReview, done} */
	private static Map<UUID, long[]> tasksBySprint(List<Object[]> rows) {
		Map<UUID, long[]> out = new HashMap<>();
		for (Object[] row : rows) {
			UUID sprintId = (UUID) row[0];
			if (sprintId == null) {
				continue;
			}
			TaskStatus status = (TaskStatus) row[1];
			long count = (Long) row[2];
			long[] bucket = out.computeIfAbsent(sprintId, id -> new long[5]);
			bucket[0] += count;
			if (status == null) {
				continue;
			}
			switch (status) {
				case TODO -> bucket[1] += count;
				case IN_PROGRESS -> bucket[2] += count;
				case IN_REVIEW -> bucket[3] += count;
				case DONE -> bucket[4] += count;
				case BLOCKED -> {
					// counted in total only — contract has no blocked field
				}
			}
		}
		return out;
	}

	private static SprintActivity toActivity(
			Sprint sprint,
			Map<UUID, long[]> tasksBySprint,
			Map<UUID, Set<UUID>> linkedCommitIdsBySprint,
			List<Object[]> commitTimes) {
		long[] taskCounts = tasksBySprint.getOrDefault(sprint.getId(), new long[5]);
		TaskCounts tasks = new TaskCounts(taskCounts[0], taskCounts[1], taskCounts[2], taskCounts[3], taskCounts[4]);
		Set<UUID> linkedIds = linkedCommitIdsBySprint.getOrDefault(sprint.getId(), Set.of());
		long unlinked = countUnlinked(sprint.getStartDate(), sprint.getEndDate(), linkedIds, commitTimes);
		CommitCounts commits = new CommitCounts(linkedIds.size(), unlinked);
		return new SprintActivity(
				sprint.getId(),
				sprint.getName(),
				uppercaseState(sprint.getState()),
				toLocalDate(sprint.getStartDate()),
				toLocalDate(sprint.getEndDate()),
				tasks,
				commits);
	}

	/**
	 * Commits whose timestamp falls in {@code [startDate, endDate]} and are not already linked to a
	 * task in this sprint. No window (missing start or end) → unlinked is 0; those commits still
	 * appear in {@code linked} when attached to a sprint task.
	 */
	private static long countUnlinked(
			LocalDateTime start, LocalDateTime end, Set<UUID> linkedIds, List<Object[]> commitTimes) {
		if (start == null || end == null) {
			return 0;
		}
		long unlinked = 0;
		for (Object[] row : commitTimes) {
			UUID commitId = (UUID) row[0];
			LocalDateTime at = (LocalDateTime) row[1];
			if (at == null || linkedIds.contains(commitId)) {
				continue;
			}
			if (!at.isBefore(start) && !at.isAfter(end)) {
				unlinked++;
			}
		}
		return unlinked;
	}

	private static String uppercaseState(String state) {
		return state == null ? null : state.toUpperCase();
	}

	private static LocalDate toLocalDate(LocalDateTime value) {
		return value == null ? null : value.toLocalDate();
	}
}

package com.saga.be.service.lecturer;

import com.saga.be.dto.team.LecturerCourseProgressResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamRepository;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lecturer course-level progress overview — one lightweight row per Team, aggregated with a
 * bounded, constant number of queries regardless of team count (never one query per team). Reuses
 * {@link LecturerCourseAuthorization#requireCourse} exactly as {@code LecturerTeamService}/
 * {@code LecturerCourseService} already do for "is this a real, currently-assigned course" —
 * but layers one extra rule found only here: ADMIN is denied for THIS endpoint specifically.
 *
 * <p>{@code LecturerCourseAuthorization} intentionally lets ADMIN bypass for other lecturer
 * endpoints (course listing/detail/roster — "support" access, per {@code LecturerCourseController}'s
 * own Tag description) and that is correctly left unchanged. But this feature's product policy is
 * LECTURER + Team Leader analytics only, and {@link com.saga.be.service.projection.ProjectDataAuthorization}
 * already denies ADMIN for the equivalent project-level progress data — allowing ADMIN through here
 * would be a policy bypass onto the same aggregated Task/Commit/Sprint data via a different route.
 * The ADMIN denial is therefore added locally in this service, not in the shared
 * {@code LecturerCourseAuthorization}, so no other lecturer endpoint is affected.
 */
@Service
@Profile("!test")
public class LecturerProgressService {

	private final LecturerCourseAuthorization authorization;
	private final TeamRepository teams;
	private final TaskRepository tasks;
	private final GitCommitRepository commits;
	private final SprintRepository sprints;

	public LecturerProgressService(
			LecturerCourseAuthorization authorization,
			TeamRepository teams,
			TaskRepository tasks,
			GitCommitRepository commits,
			SprintRepository sprints) {
		this.authorization = authorization;
		this.teams = teams;
		this.tasks = tasks;
		this.commits = commits;
		this.sprints = sprints;
	}

	@Transactional(readOnly = true)
	public LecturerCourseProgressResponse getCourseProgress(UserAccount actor, UUID courseId) {
		requireNotAdmin(actor);
		Course course = authorization.requireCourse(actor, courseId);
		List<Team> teamRows = teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId);
		List<UUID> projectIds = teamRows.stream()
				.map(Team::getProject)
				.filter(Objects::nonNull)
				.map(Project::getId)
				.toList();

		Map<UUID, long[]> taskCountsByProject = taskCountsByProject(projectIds);
		Map<UUID, LocalDateTime> lastCommitByProject = lastCommitByProject(projectIds);
		Map<UUID, LocalDateTime> lastTaskUpdateByProject = lastTaskUpdateByProject(projectIds);
		Map<UUID, String> currentSprintNameByProject = currentSprintNameByProject(projectIds);

		List<LecturerCourseProgressResponse.Entry> entries = teamRows.stream()
				.map(team -> toEntry(
						team, taskCountsByProject, lastCommitByProject, lastTaskUpdateByProject, currentSprintNameByProject))
				.toList();
		return new LecturerCourseProgressResponse(course.getId(), entries);
	}

	private LecturerCourseProgressResponse.Entry toEntry(
			Team team,
			Map<UUID, long[]> taskCountsByProject,
			Map<UUID, LocalDateTime> lastCommitByProject,
			Map<UUID, LocalDateTime> lastTaskUpdateByProject,
			Map<UUID, String> currentSprintNameByProject) {
		Project project = team.getProject();
		UUID projectId = project == null ? null : project.getId();
		long[] taskCounts = projectId == null ? new long[2] : taskCountsByProject.getOrDefault(projectId, new long[2]);
		Double completionPercent = taskCounts[0] == 0 ? null : (taskCounts[1] * 100.0) / taskCounts[0];
		LocalDateTime lastActivityAt = projectId == null
				? null
				: latest(lastTaskUpdateByProject.get(projectId), lastCommitByProject.get(projectId));
		return new LecturerCourseProgressResponse.Entry(
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				projectId,
				taskCounts[0],
				taskCounts[1],
				completionPercent,
				projectId == null ? null : currentSprintNameByProject.get(projectId),
				lastActivityAt);
	}

	/** projectId -> {total, completed}. */
	private Map<UUID, long[]> taskCountsByProject(List<UUID> projectIds) {
		Map<UUID, long[]> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.countGroupedByStatusForProjects(projectIds)) {
			UUID projectId = (UUID) row[0];
			TaskStatus status = (TaskStatus) row[1];
			long count = (Long) row[2];
			long[] bucket = out.computeIfAbsent(projectId, id -> new long[2]);
			bucket[0] += count;
			if (status == TaskStatus.DONE) {
				bucket[1] += count;
			}
		}
		return out;
	}

	private Map<UUID, LocalDateTime> lastCommitByProject(List<UUID> projectIds) {
		Map<UUID, LocalDateTime> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : commits.countAndMaxCommittedAtGroupedByProjects(projectIds)) {
			out.put((UUID) row[0], (LocalDateTime) row[2]);
		}
		return out;
	}

	private Map<UUID, LocalDateTime> lastTaskUpdateByProject(List<UUID> projectIds) {
		Map<UUID, LocalDateTime> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Object[] row : tasks.findMaxUpdatedAtGroupedByProjects(projectIds)) {
			out.put((UUID) row[0], (LocalDateTime) row[1]);
		}
		return out;
	}

	/** One deterministic "active"-state sprint name per project (see {@code ProjectProgressService#currentSprint}). */
	private Map<UUID, String> currentSprintNameByProject(List<UUID> projectIds) {
		Map<UUID, String> out = new HashMap<>();
		if (projectIds.isEmpty()) {
			return out;
		}
		for (Sprint sprint : sprints.findActiveByProjectIdIn(projectIds)) {
			if (sprint.getState() == null || !sprint.getState().equalsIgnoreCase("active")) {
				continue;
			}
			UUID projectId = sprint.getJiraIntegration().getProject().getId();
			out.putIfAbsent(projectId, sprint.getName());
		}
		return out;
	}

	/**
	 * ADMIN-deny, scoped to this one endpoint only — checked before {@code requireCourse} so
	 * ADMIN gets a flat 403 without even revealing whether {@code courseId} exists, matching how
	 * {@code ProjectDataAuthorization} denies ADMIN up front for the equivalent project-level data.
	 */
	private static void requireNotAdmin(UserAccount actor) {
		if (actor != null && actor.getAccountRole() == AccountRole.ADMIN) {
			throw new AcademicException(
					AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Progress analytics are limited to the assigned Lecturer and Team Leaders.");
		}
	}

	private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
		if (a == null) {
			return b;
		}
		if (b == null) {
			return a;
		}
		return a.isAfter(b) ? a : b;
	}
}

package com.saga.be.service.admin.dashboard;

import com.saga.be.dto.admin.dashboard.AdminDashboardAvailableSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardWeeklyPointResponse;
import com.saga.be.config.AdminDashboardProperties;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.admin.dashboard.AdminDashboardSemesterWeeks.WeekSlice;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * Grouped semester-scope aggregates. No provider HTTP. Callers must run this inside a short
 * read-only JDBC block and release it before any Redis write.
 *
 * <p>Phase B adds three constant queries (V23 activity id+timestamp, distinct linked ids, DONE
 * completion timestamps) then buckets in memory. SQL count does not scale with {@code totalWeeks}.
 *
 * <p>Timestamps are naive {@link LocalDateTime} wall-clock values compared to semester
 * {@code LocalDate.atStartOfDay()} bounds. {@code now} is {@code LocalDateTime.now(clock)} from
 * {@code saga.dashboard.zone} (default UTC). No zone conversion is applied to stored commit/task
 * columns. Temporal display fields are re-derived on each HTTP response from the same clock.
 *
 * <p>{@code tasksCompleted} counts current {@code DONE} rows only, bucketed by
 * {@code coalesce(completedAt, resolvedAt, createdAt)}. This is not an immutable status-transition
 * history: a task that was DONE in week 2 and later reopened is omitted.
 */
@Service
@Profile("!test")
public class AdminDashboardQueryService {

	private final SemesterRepository semesters;
	private final ActiveSemesterSettingRepository activeSettings;
	private final CourseRepository courses;
	private final CourseEnrollmentRepository enrollments;
	private final TeamRepository teams;
	private final GitCommitRepository commits;
	private final TaskRepository tasks;
	private final TaskGitCommitLinkRepository links;
	private final Clock clock;

	@Autowired
	public AdminDashboardQueryService(
			SemesterRepository semesters,
			ActiveSemesterSettingRepository activeSettings,
			CourseRepository courses,
			CourseEnrollmentRepository enrollments,
			TeamRepository teams,
			GitCommitRepository commits,
			TaskRepository tasks,
			TaskGitCommitLinkRepository links,
			AdminDashboardProperties properties) {
		this(
				semesters,
				activeSettings,
				courses,
				enrollments,
				teams,
				commits,
				tasks,
				links,
				properties.clock());
	}

	public AdminDashboardQueryService(
			SemesterRepository semesters,
			ActiveSemesterSettingRepository activeSettings,
			CourseRepository courses,
			CourseEnrollmentRepository enrollments,
			TeamRepository teams,
			GitCommitRepository commits,
			TaskRepository tasks,
			TaskGitCommitLinkRepository links,
			Clock clock) {
		this.semesters = semesters;
		this.activeSettings = activeSettings;
		this.courses = courses;
		this.enrollments = enrollments;
		this.teams = teams;
		this.commits = commits;
		this.tasks = tasks;
		this.links = links;
		this.clock = clock;
	}

	public AdminDashboardCachedPayload compute(Semester selected, Instant cachedAt) {
		LocalDate today = LocalDate.now(clock);
		UUID selectedId = selected.getId();
		UUID activeId = activeSemesterId();
		LocalDate start = selected.getStartDate().toLocalDate();
		LocalDate end = selected.getEndDate().toLocalDate();

		List<AdminDashboardAvailableSemesterResponse> available = semesters
				.findByDeletedAtIsNullOrderByStartDateDescIdDesc()
				.stream()
				.map(row -> toAvailable(row, activeId, today))
				.toList();

		Semester previous = semesters
				.findFirstByDeletedAtIsNullAndStartDateNotNullAndStartDateLessThanOrderByStartDateDescIdDesc(
						selected.getStartDate())
				.orElse(null);

		long totalStudents = enrollments.countDistinctStudentsBySemesterAndStatus(selectedId, EnrollmentStatus.ACTIVE);
		Double growth;
		String comparedCode;
		if (previous == null) {
			growth = null;
			comparedCode = null;
		} else {
			long previousStudents =
					enrollments.countDistinctStudentsBySemesterAndStatus(previous.getId(), EnrollmentStatus.ACTIVE);
			comparedCode = previous.getCode();
			growth = previousStudents == 0
					? null
					: ratioPercent(totalStudents - previousStudents, previousStudents);
		}

		long totalCourses = courses.countBySemester_IdAndDeletedAtIsNull(selectedId);
		long totalTeams = teams.countByCourseSemester(selectedId);
		long connectedTeams = teams.countConnectedByCourseSemester(selectedId);
		Double connectedRate = totalTeams == 0 ? null : ratioPercent(connectedTeams, totalTeams);

		long rawCommits = commits.countRawByCourseSemester(selectedId);
		long activeTasks = tasks.countActiveByCourseSemester(selectedId);
		long traceable = commits.countTraceableByCourseSemester(selectedId);
		long linked = links.countDistinctLinkedTraceableByCourseSemester(selectedId);
		Double traceability = traceable == 0 ? null : ratioPercent(linked, traceable);

		LocalDateTime now = LocalDateTime.now(clock);
		List<WeekSlice> slices = AdminDashboardSemesterWeeks.slices(start, end, now);
		LocalDateTime startInclusive = AdminDashboardSemesterWeeks.startInclusive(start);
		LocalDateTime endExclusive = AdminDashboardSemesterWeeks.endExclusive(end);
		List<Object[]> activityRows =
				commits.findActivityIdAndTimestampByCourseSemester(selectedId, startInclusive, endExclusive);
		Set<UUID> linkedIds = new HashSet<>(links.findDistinctLinkedTraceableIdsByCourseSemester(selectedId));
		List<LocalDateTime> completions =
				tasks.findDoneCompletionTimestampsByCourseSemester(selectedId, startInclusive, endExclusive);
		List<AdminDashboardWeeklyPointResponse> weeklyTimeline = AdminDashboardWeeklyTimeline.bucket(
				slices, AdminDashboardWeeklyTimeline.activities(activityRows, linkedIds), completions);

		AdminDashboardSelectedSemesterResponse selectedDto = new AdminDashboardSelectedSemesterResponse(
				selectedId,
				selected.getCode(),
				selected.getName(),
				start,
				end,
				slices.size(),
				AdminDashboardSemesterWeeks.currentWeekIndex(slices),
				selectedId.equals(activeId));

		return new AdminDashboardCachedPayload(
				UUID.randomUUID().toString(),
				cachedAt,
				selectedDto,
				available,
				new AdminDashboardKpisResponse(
						totalStudents,
						growth,
						comparedCode,
						totalCourses,
						totalTeams,
						connectedTeams,
						connectedRate,
						rawCommits,
						activeTasks,
						traceability),
				weeklyTimeline);
	}

	private UUID activeSemesterId() {
		return activeSettings
				.findByIdFetchSemester((byte) 1)
				.map(setting -> setting.getSemester())
				.filter(semester -> semester.getDeletedAt() == null)
				.map(Semester::getId)
				.orElse(null);
	}

	private static AdminDashboardAvailableSemesterResponse toAvailable(
			Semester semester, UUID activeId, LocalDate today) {
		LocalDate start = toDate(semester.getStartDate());
		LocalDate end = toDate(semester.getEndDate());
		return new AdminDashboardAvailableSemesterResponse(
				semester.getId(),
				semester.getCode(),
				semester.getName(),
				start,
				end,
				semester.getId().equals(activeId),
				AdminDashboardSemesterWeeks.periodStatus(start, end, today));
	}

	private static LocalDate toDate(LocalDateTime value) {
		return value == null ? null : value.toLocalDate();
	}

	static Double ratioPercent(long numerator, long denominator) {
		if (denominator == 0) {
			return null;
		}
		double value = (numerator * 100.0d) / denominator;
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return null;
		}
		return value;
	}
}

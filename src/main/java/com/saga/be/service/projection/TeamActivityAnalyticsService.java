package com.saga.be.service.projection;

import com.saga.be.dto.project.BurndownChartResponse;
import com.saga.be.dto.project.BurndownChartResponse.BurndownPoint;
import com.saga.be.dto.project.HeatmapResponse;
import com.saga.be.dto.project.HeatmapResponse.HeatmapActor;
import com.saga.be.dto.project.HeatmapResponse.HeatmapCell;
import com.saga.be.dto.project.HeatmapResponse.StudentHeatmap;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only activity heatmap and sprint burndown. Same product-data policy as
 * {@link ProjectDataAuthorization#requireReader}. Not a contribution formula.
 */
@Service
@Profile("!test")
public class TeamActivityAnalyticsService {

	static final int MAX_HEATMAP_DAYS = 366;

	private final TeamRepository teams;
	private final TeamMemberRepository members;
	private final SprintRepository sprints;
	private final GitCommitRepository commits;
	private final PeerReviewRepository peerReviews;
	private final TaskFileRepository files;
	private final TaskWebLinkRepository webLinks;
	private final TaskAttachmentRepository attachments;
	private final TaskRepository tasks;
	private final ProjectDataAuthorization authorization;

	public TeamActivityAnalyticsService(
			TeamRepository teams,
			TeamMemberRepository members,
			SprintRepository sprints,
			GitCommitRepository commits,
			PeerReviewRepository peerReviews,
			TaskFileRepository files,
			TaskWebLinkRepository webLinks,
			TaskAttachmentRepository attachments,
			TaskRepository tasks,
			ProjectDataAuthorization authorization) {
		this.teams = teams;
		this.members = members;
		this.sprints = sprints;
		this.commits = commits;
		this.peerReviews = peerReviews;
		this.files = files;
		this.webLinks = webLinks;
		this.attachments = attachments;
		this.tasks = tasks;
		this.authorization = authorization;
	}

	@Transactional(readOnly = true)
	public HeatmapResponse heatmap(
			UUID userId, UUID courseId, UUID teamId, LocalDate startDate, LocalDate endDate, UUID studentId) {
		ProjectContext ctx = requireTeamProject(userId, courseId, teamId);
		validateRange(startDate, endDate);
		List<RosterStudent> roster = activeRoster(teamId);
		if (studentId != null) {
			roster = roster.stream().filter(row -> row.id().equals(studentId)).toList();
			if (roster.isEmpty()) {
				throw new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Student is not an ACTIVE member of this team's project.");
			}
		}
		Map<UUID, Map<LocalDate, Counts>> byStudent = emptyBuckets(roster, startDate, endDate);
		addEvents(byStudent, commits.findAuthorAndCommittedAtByProject(ctx.projectId()), Kind.COMMIT, startDate, endDate);
		addEvents(
				byStudent,
				peerReviews.findReviewerAndCreatedAtByProject(ctx.projectId()),
				Kind.PEER_REVIEW,
				startDate,
				endDate);
		addEvents(byStudent, files.findAuthorAndCreatedAtByProject(ctx.projectId()), Kind.DOCUMENT, startDate, endDate);
		addEvents(byStudent, webLinks.findAuthorAndCreatedAtByProject(ctx.projectId()), Kind.DOCUMENT, startDate, endDate);
		addEvents(
				byStudent,
				attachments.findAssigneeAndCreatedAtByProject(ctx.projectId()),
				Kind.DOCUMENT,
				startDate,
				endDate);
		addEvents(byStudent, tasks.findAssigneeAndCreatedAtByProject(ctx.projectId()), Kind.TASK, startDate, endDate);

		List<StudentHeatmap> students = new ArrayList<>();
		Map<LocalDate, Counts> teamDays = emptyDayMap(startDate, endDate);
		Map<LocalDate, List<HeatmapActor>> actorsByDay = emptyActorMap(startDate, endDate);
		for (RosterStudent row : roster) {
			Map<LocalDate, Counts> days = byStudent.get(row.id());
			List<HeatmapCell> cells = new ArrayList<>();
			Counts totals = new Counts();
			HeatmapActor actor = toActor(row);
			for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
				Counts cell = days.get(day);
				cells.add(toCell(day, cell, List.of()));
				totals.add(cell);
				teamDays.get(day).add(cell);
				if (cell.activities() > 0) {
					actorsByDay.get(day).add(actor);
				}
			}
			students.add(toStudent(row, totals, cells));
		}
		List<HeatmapCell> days = new ArrayList<>();
		for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
			days.add(toCell(day, teamDays.get(day), List.copyOf(actorsByDay.get(day))));
		}
		return new HeatmapResponse(courseId, teamId, studentId, startDate, endDate, students, days);
	}

	@Transactional(readOnly = true)
	public BurndownChartResponse burndown(UUID userId, UUID courseId, UUID teamId, UUID sprintId) {
		ProjectContext ctx = requireTeamProject(userId, courseId, teamId);
		Sprint sprint = sprints
				.findActiveByIdAndProject_Id(sprintId, ctx.projectId())
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Sprint was not found for this project."));
		if (sprint.getStartDate() == null || sprint.getEndDate() == null) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"Sprint startDate and endDate are required for burndown.");
		}
		LocalDate startDate = sprint.getStartDate().toLocalDate();
		LocalDate endDate = sprint.getEndDate().toLocalDate();
		if (startDate.isAfter(endDate)) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"Sprint startDate must not be after endDate.");
		}
		List<Object[]> rows = tasks.findBurndownRowsByProjectAndSprint(ctx.projectId(), sprintId);
		int totalScope = rows.size();
		List<LocalDate> doneDates = new ArrayList<>();
		for (Object[] row : rows) {
			LocalDate done = doneDate(row);
			if (done != null) {
				doneDates.add(done);
			}
		}
		List<LocalDate> days = enumerateDays(startDate, endDate);
		List<BurndownPoint> points = new ArrayList<>();
		for (LocalDate day : days) {
			int doneCount = 0;
			for (LocalDate done : doneDates) {
				if (!done.isAfter(day)) {
					doneCount++;
				}
			}
			points.add(new BurndownPoint(day, totalScope - doneCount, doneCount));
		}
		return new BurndownChartResponse(
				courseId, teamId, sprint.getId(), sprint.getName(), startDate, endDate, totalScope, points);
	}

	private ProjectContext requireTeamProject(UUID userId, UUID courseId, UUID teamId) {
		Team team = teams
				.findFetchedByIdAndCourse_Id(teamId, courseId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Team was not found for this course."));
		Project project = team.getProject();
		if (project == null || project.getId() == null) {
			throw new AcademicException(
					AcademicErrorCode.PROJECT_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Team does not have a project yet.");
		}
		authorization.requireReader(userId, project.getId());
		return new ProjectContext(project.getId());
	}

	private List<RosterStudent> activeRoster(UUID teamId) {
		List<RosterStudent> roster = new ArrayList<>();
		for (TeamMember member : members.findFetchedByTeam_Id(teamId)) {
			CourseEnrollment enrollment = member.getCourseEnrollment();
			if (enrollment == null || enrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE) {
				continue;
			}
			StudentProfile profile = enrollment.getStudentProfile();
			if (profile == null || profile.getId() == null) {
				continue;
			}
			UserAccount account = profile.getUserAccount();
			roster.add(new RosterStudent(
					profile.getId(),
					profile.getStudentCode(),
					account == null ? null : account.getFullName(),
					account == null ? null : account.getAvatarUrl()));
		}
		roster.sort(Comparator.comparing((RosterStudent row) -> nullToEmpty(row.studentCode()))
				.thenComparing(row -> nullToEmpty(row.fullName()))
				.thenComparing(RosterStudent::id));
		return roster;
	}

	private static void validateRange(LocalDate startDate, LocalDate endDate) {
		if (startDate == null || endDate == null) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"startDate and endDate are required.");
		}
		if (startDate.isAfter(endDate)) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"startDate must not be after endDate.");
		}
		if (ChronoUnit.DAYS.between(startDate, endDate) + 1 > MAX_HEATMAP_DAYS) {
			throw new AcademicException(
					AcademicErrorCode.REQUEST_INVALID,
					HttpStatus.BAD_REQUEST,
					"Date range must be at most " + MAX_HEATMAP_DAYS + " days.");
		}
	}

	private static Map<UUID, Map<LocalDate, Counts>> emptyBuckets(
			List<RosterStudent> roster, LocalDate startDate, LocalDate endDate) {
		Map<UUID, Map<LocalDate, Counts>> out = new LinkedHashMap<>();
		for (RosterStudent row : roster) {
			out.put(row.id(), emptyDayMap(startDate, endDate));
		}
		return out;
	}

	private static Map<LocalDate, Counts> emptyDayMap(LocalDate startDate, LocalDate endDate) {
		Map<LocalDate, Counts> days = new LinkedHashMap<>();
		for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
			days.put(day, new Counts());
		}
		return days;
	}

	private static Map<LocalDate, List<HeatmapActor>> emptyActorMap(LocalDate startDate, LocalDate endDate) {
		Map<LocalDate, List<HeatmapActor>> days = new LinkedHashMap<>();
		for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
			days.put(day, new ArrayList<>());
		}
		return days;
	}

	private static void addEvents(
			Map<UUID, Map<LocalDate, Counts>> byStudent,
			List<Object[]> rows,
			Kind kind,
			LocalDate startDate,
			LocalDate endDate) {
		if (rows == null) {
			return;
		}
		for (Object[] row : rows) {
			if (row == null || row.length < 2 || !(row[0] instanceof UUID studentId) || !(row[1] instanceof LocalDateTime at)) {
				continue;
			}
			Map<LocalDate, Counts> days = byStudent.get(studentId);
			if (days == null) {
				continue;
			}
			LocalDate day = at.toLocalDate();
			if (day.isBefore(startDate) || day.isAfter(endDate)) {
				continue;
			}
			days.get(day).increment(kind);
		}
	}

	private static LocalDate doneDate(Object[] row) {
		TaskStatus status = (TaskStatus) row[0];
		LocalDateTime completedAt = (LocalDateTime) row[1];
		LocalDateTime resolvedAt = (LocalDateTime) row[2];
		LocalDateTime createdAt = (LocalDateTime) row[3];
		if (status != TaskStatus.DONE) {
			return null;
		}
		LocalDateTime at = completedAt != null ? completedAt : (resolvedAt != null ? resolvedAt : createdAt);
		return at == null ? null : at.toLocalDate();
	}

	private static List<LocalDate> enumerateDays(LocalDate startDate, LocalDate endDate) {
		List<LocalDate> days = new ArrayList<>();
		for (LocalDate day = startDate; !day.isAfter(endDate); day = day.plusDays(1)) {
			days.add(day);
		}
		return days;
	}

	private static StudentHeatmap toStudent(RosterStudent row, Counts totals, List<HeatmapCell> cells) {
		return new StudentHeatmap(
				row.id(),
				row.studentCode(),
				row.fullName(),
				row.avatar(),
				totals.commits,
				totals.peerReviews,
				totals.documents,
				totals.tasks,
				totals.activities(),
				cells);
	}

	private static HeatmapActor toActor(RosterStudent row) {
		return new HeatmapActor(row.id(), row.studentCode(), row.fullName(), row.avatar());
	}

	private static HeatmapCell toCell(LocalDate day, Counts counts, List<HeatmapActor> actors) {
		return new HeatmapCell(
				day,
				counts.commits,
				counts.peerReviews,
				counts.documents,
				counts.tasks,
				counts.activities(),
				actors);
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private record ProjectContext(UUID projectId) {}

	private record RosterStudent(UUID id, String studentCode, String fullName, String avatar) {}

	enum Kind {
		COMMIT,
		PEER_REVIEW,
		DOCUMENT,
		TASK
	}

	static final class Counts {
		long commits;
		long peerReviews;
		long documents;
		long tasks;

		void increment(Kind kind) {
			switch (kind) {
				case COMMIT -> commits++;
				case PEER_REVIEW -> peerReviews++;
				case DOCUMENT -> documents++;
				case TASK -> tasks++;
			}
		}

		void add(Counts other) {
			commits += other.commits;
			peerReviews += other.peerReviews;
			documents += other.documents;
			tasks += other.tasks;
		}

		long activities() {
			return commits + peerReviews + documents + tasks;
		}
	}
}

package com.saga.be.service.delay;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.dto.delay.DelayCaseDtos.ExplainRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LecturerReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LeaderReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.MemberOnTimeRate;
import com.saga.be.dto.delay.DelayCaseDtos.OnTimeRateResponse;
import com.saga.be.dto.delay.DelayCaseDtos.Permissions;
import com.saga.be.dto.delay.DelayCaseDtos.StudentRef;
import com.saga.be.dto.delay.DelayCaseDtos.TaskRef;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.delay.TaskDelayCase;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.DelayCaseEnums.CloseReason;
import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Outcome;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.DelayCauseCategory;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.TaskDelayCaseRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Delay cases: when a task misses its deadline the assignee explains why, the team leader confirms,
 * and the lecturer decides the cases the policy sends up (see {@link DelayCaseRules}). Nothing here
 * is decided by AI, and contribution scoring is untouched: a closed case only feeds the on-time rate.
 *
 * <p>Deadlines are calendar days in {@code saga.delay-cases.zone} (the students' day): a task due on
 * 04/10 is late only once 04/10 is over there.
 */
@Service
@Profile("!test")
public class TaskDelayCaseService {

	private static final Logger log = LoggerFactory.getLogger(TaskDelayCaseService.class);
	private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd/MM/yyyy");
	private static final DateTimeFormatter MINUTE_DAY = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy");
	static final int NOTE_MAX = 1000;
	static final int URL_MAX = 2048;

	private final TaskDelayCaseRepository cases;
	private final TaskRepository tasks;
	private final DelaySignalCollector signalCollector;
	private final ProjectDataAuthorization authorization;
	private final UserAccountRepository users;
	private final TeamMemberRepository members;
	private final TeamByProjectRepository teams;
	private final NotificationService notifications;
	private final Clock clock;
	private final ZoneId zone;
	private final Duration explanationWindow;
	private final ObjectMapper json = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

	@Autowired
	public TaskDelayCaseService(
			TaskDelayCaseRepository cases,
			TaskRepository tasks,
			DelaySignalCollector signalCollector,
			ProjectDataAuthorization authorization,
			UserAccountRepository users,
			TeamMemberRepository members,
			TeamByProjectRepository teams,
			NotificationService notifications,
			@Value("${saga.delay-cases.zone:Asia/Ho_Chi_Minh}") String zone,
			@Value("${saga.delay-cases.explanation-window:3d}") Duration explanationWindow) {
		this(cases, tasks, signalCollector, authorization, users, members, teams, notifications,
				Clock.systemDefaultZone(), ZoneId.of(zone), explanationWindow);
	}

	TaskDelayCaseService(
			TaskDelayCaseRepository cases,
			TaskRepository tasks,
			DelaySignalCollector signalCollector,
			ProjectDataAuthorization authorization,
			UserAccountRepository users,
			TeamMemberRepository members,
			TeamByProjectRepository teams,
			NotificationService notifications,
			Clock clock,
			ZoneId zone,
			Duration explanationWindow) {
		this.cases = cases;
		this.tasks = tasks;
		this.signalCollector = signalCollector;
		this.authorization = authorization;
		this.users = users;
		this.members = members;
		this.teams = teams;
		this.notifications = notifications;
		this.clock = clock;
		this.zone = zone;
		this.explanationWindow = explanationWindow;
	}

	/** The students' current calendar day. */
	public LocalDate today() {
		return LocalDate.now(clock.withZone(zone));
	}

	private LocalDateTime now() {
		return LocalDateTime.now(clock);
	}

	// ------------------------------------------------------------------ opening & expiry (scanner)

	/** Late = finished after the due day, or still open once the due day is over. */
	public static boolean isLate(Task task, LocalDate today) {
		if (task.getDueDate() == null) {
			return false;
		}
		LocalDate due = task.getDueDate().toLocalDate();
		if (task.getStatus() == TaskStatus.DONE) {
			return task.getCompletedAt() != null && task.getCompletedAt().toLocalDate().isAfter(due);
		}
		return today.isAfter(due);
	}

	/**
	 * Opens the case for this task's current due date if it is late, assigned and has none yet.
	 * Returns true when a case was opened.
	 */
	@Transactional
	public boolean openIfAbsent(UUID taskId) {
		Task task = tasks.findById(taskId).orElse(null);
		if (task == null
				|| task.getDeletedAt() != null
				|| task.getProject() == null
				|| task.getAssigneeStudent() == null
				|| task.getDueDate() == null
				|| !isLate(task, today())
				|| cases.existsByTask_IdAndDueDate(task.getId(), task.getDueDate())) {
			return false;
		}
		LocalDateTime now = now();
		TaskDelayCase delay = new TaskDelayCase();
		delay.setProject(task.getProject());
		delay.setTask(task);
		delay.setStudentProfile(task.getAssigneeStudent());
		delay.setDueDate(task.getDueDate());
		delay.setOpenedAt(now);
		delay.setExplanationDueAt(now.plus(explanationWindow));
		delay.setStatus(DelayCaseStatus.OPEN);
		delay.setSignalsJson(writeSignals(signalCollector.collect(task, task.getDueDate())));
		TaskDelayCase saved = cases.save(delay);
		notifyUser(
				assigneeUserId(saved),
				"Cần giải trình lý do trễ hạn",
				"Task " + label(task) + " đã trễ hạn chót " + task.getDueDate().format(DAY)
						+ ". Hãy giải trình lý do trước " + saved.getExplanationDueAt().format(MINUTE_DAY)
						+ ", nếu không lần trễ này được ghi nhận là chủ quan.",
				saved,
				"opened");
		return true;
	}

	/** Closes as subjective every case whose explanation window is over without an explanation. */
	@Transactional
	public int expireOverdueExplanations() {
		LocalDateTime now = now();
		int closed = 0;
		for (TaskDelayCase delay : cases.findByStatusAndExplanationDueAtBefore(DelayCaseStatus.OPEN, now)) {
			delay.setStatus(DelayCaseStatus.CLOSED_SUBJECTIVE);
			delay.setCloseReason(CloseReason.EXPLANATION_EXPIRED);
			delay.setClosedAt(now);
			cases.save(delay);
			notifyUser(
					assigneeUserId(delay),
					"Hồ sơ trễ hạn đã đóng",
					"Hết hạn giải trình cho task " + label(delay.getTask())
							+ ", lần trễ này được ghi nhận là chủ quan. Giảng viên có thể mở lại hồ sơ.",
					delay,
					"expired");
			closed++;
		}
		return closed;
	}

	// ------------------------------------------------------------------ reads

	@Transactional(readOnly = true)
	public List<DelayCaseResponse> list(UUID userId, UUID projectId, DelayCaseStatus status, UUID taskId) {
		Viewer viewer = viewer(userId, projectId);
		return cases.findFetchedByProject(projectId).stream()
				.filter(delay -> status == null || delay.getStatus() == status)
				.filter(delay -> taskId == null || taskId.equals(delay.getTask().getId()))
				.map(delay -> toResponse(delay, viewer))
				.toList();
	}

	@Transactional(readOnly = true)
	public DelayCaseResponse get(UUID userId, UUID projectId, UUID caseId) {
		Viewer viewer = viewer(userId, projectId);
		return toResponse(requireCase(projectId, caseId), viewer);
	}

	/** The lecturer's review queue across the courses they teach (default: cases waiting for them). */
	@Transactional(readOnly = true)
	public List<DelayCaseResponse> lecturerQueue(UUID userId, List<DelayCaseStatus> statuses) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() != AccountRole.LECTURER) {
			throw forbidden("Only a lecturer has a delay case review queue.");
		}
		List<DelayCaseStatus> wanted = statuses == null || statuses.isEmpty()
				? List.of(DelayCaseStatus.AWAITING_LECTURER)
				: statuses;
		Viewer viewer = new Viewer(userId, true, false);
		return cases.findFetchedForLecturer(userId, wanted).stream().map(delay -> toResponse(delay, viewer)).toList();
	}

	// ------------------------------------------------------------------ actions

	@Transactional
	public DelayCaseResponse explain(UUID userId, UUID projectId, UUID caseId, ExplainRequest request) {
		Viewer viewer = viewer(userId, projectId);
		TaskDelayCase delay = requireCase(projectId, caseId);
		requireStatus(delay, DelayCaseStatus.OPEN, "This delay case is no longer waiting for an explanation.");
		if (!userId.equals(assigneeUserId(delay))) {
			throw forbidden("Only the assignee of the task can explain this delay.");
		}
		LocalDateTime now = now();
		if (now.isAfter(delay.getExplanationDueAt())) {
			throw conflict("The explanation window of this delay case is over.");
		}
		DelayCauseCategory category = request == null ? null : request.category();
		if (category == null) {
			throw invalid("Choose the cause of the delay.");
		}
		String note = trimToNull(request.note());
		if (note != null && note.length() > NOTE_MAX) {
			throw invalid("The note must be at most " + NOTE_MAX + " characters.");
		}
		if (category.requiresNote() && note == null) {
			throw invalid("Describe the cause: a note is required for this category.");
		}
		Task blocker = null;
		if (category == DelayCauseCategory.BLOCKED_BY_TASK) {
			if (request.blockingTaskId() == null) {
				throw invalid("Choose the task that blocked this one.");
			}
			if (request.blockingTaskId().equals(delay.getTask().getId())) {
				throw invalid("A task cannot block itself.");
			}
			blocker = tasks.findByIdAndProject_IdAndDeletedAtIsNull(request.blockingTaskId(), projectId)
					.orElseThrow(() -> invalid("The blocking task was not found in this project."));
		}
		String evidenceUrl = trimToNull(request.evidenceUrl());
		if (evidenceUrl != null) {
			String lower = evidenceUrl.toLowerCase(Locale.ROOT);
			if (evidenceUrl.length() > URL_MAX || !(lower.startsWith("https://") || lower.startsWith("http://"))) {
				throw invalid("The evidence link must be an http(s) URL of at most " + URL_MAX + " characters.");
			}
		}
		DelaySignals signals = signalCollector.collect(delay.getTask(), delay.getDueDate());
		DelayCaseRules.Check check = DelayCaseRules.verify(
				category,
				signals,
				blocker == null
						? null
						: new DelayCaseRules.BlockerFacts(label(blocker), blocker.getStatus(), blocker.getCompletedAt()),
				delay.getDueDate());
		delay.setCategory(category);
		delay.setExplanationNote(note);
		delay.setBlockingTask(blocker);
		delay.setEvidenceUrl(evidenceUrl);
		delay.setExplainedAt(now);
		delay.setExplainedByUserId(userId);
		delay.setSignalsJson(writeSignals(signals));
		delay.setVerification(check.verification());
		delay.setVerificationNote(check.note());
		boolean assigneeIsLeader = viewer.leader();
		delay.setStatus(DelayCaseRules.afterExplanation(assigneeIsLeader));
		TaskDelayCase saved = cases.save(delay);
		if (saved.getStatus() == DelayCaseStatus.AWAITING_LEADER) {
			for (UUID leader : leaderUserIds(projectId)) {
				if (!leader.equals(userId)) {
					notifyUser(leader, "Hồ sơ trễ hạn chờ bạn xác nhận",
							studentName(saved) + " đã giải trình lý do trễ của task " + label(saved.getTask()) + ".", saved, "awaiting-leader");
				}
			}
		} else {
			notifyLecturer(saved);
		}
		return toResponse(saved, viewer);
	}

	@Transactional
	public DelayCaseResponse leaderReview(UUID userId, UUID projectId, UUID caseId, LeaderReviewRequest request) {
		Viewer viewer = viewer(userId, projectId);
		TaskDelayCase delay = requireCase(projectId, caseId);
		requireStatus(delay, DelayCaseStatus.AWAITING_LEADER, "This delay case is not waiting for the team leader.");
		if (!viewer.leader()) {
			throw forbidden("Only the team leader can confirm a delay explanation.");
		}
		if (userId.equals(assigneeUserId(delay))) {
			throw forbidden("The team leader cannot confirm their own delay case.");
		}
		LeaderDecision decision = request == null ? null : request.decision();
		if (decision == null) {
			throw invalid("Choose agree or disagree.");
		}
		String comment = trimToNull(request.comment());
		if (comment != null && comment.length() > NOTE_MAX) {
			throw invalid("The comment must be at most " + NOTE_MAX + " characters.");
		}
		if (decision == LeaderDecision.DISAGREE && comment == null) {
			throw invalid("Explain why you disagree.");
		}
		LocalDateTime now = now();
		delay.setLeaderDecision(decision);
		delay.setLeaderComment(comment);
		delay.setLeaderReviewedByUserId(userId);
		delay.setLeaderReviewedAt(now);
		DelayCaseStatus next = DelayCaseRules.afterLeader(delay.getCategory(), delay.getVerification(), decision);
		delay.setStatus(next);
		if (next == DelayCaseStatus.CLOSED_SUBJECTIVE) {
			delay.setCloseReason(CloseReason.LEADER_CONFIRMED);
			delay.setClosedAt(now);
		}
		TaskDelayCase saved = cases.save(delay);
		if (next == DelayCaseStatus.CLOSED_SUBJECTIVE) {
			notifyClosed(saved);
		} else {
			notifyLecturer(saved);
		}
		return toResponse(saved, viewer);
	}

	@Transactional
	public DelayCaseResponse lecturerReview(UUID userId, UUID projectId, UUID caseId, LecturerReviewRequest request) {
		Viewer viewer = viewer(userId, projectId);
		TaskDelayCase delay = requireCase(projectId, caseId);
		requireStatus(delay, DelayCaseStatus.AWAITING_LECTURER, "This delay case is not waiting for the lecturer.");
		if (!viewer.lecturer()) {
			throw forbidden("Only the course lecturer can decide a delay case.");
		}
		Outcome outcome = request == null ? null : request.outcome();
		if (outcome == null) {
			throw invalid("Choose objective or subjective.");
		}
		String comment = trimToNull(request.comment());
		if (comment != null && comment.length() > NOTE_MAX) {
			throw invalid("The comment must be at most " + NOTE_MAX + " characters.");
		}
		LocalDateTime now = now();
		delay.setLecturerOutcome(outcome);
		delay.setLecturerComment(comment);
		delay.setLecturerReviewedByUserId(userId);
		delay.setLecturerReviewedAt(now);
		delay.setStatus(outcome == Outcome.OBJECTIVE ? DelayCaseStatus.CLOSED_OBJECTIVE : DelayCaseStatus.CLOSED_SUBJECTIVE);
		delay.setCloseReason(CloseReason.LECTURER_DECIDED);
		delay.setClosedAt(now);
		TaskDelayCase saved = cases.save(delay);
		notifyClosed(saved);
		return toResponse(saved, viewer);
	}

	/**
	 * Lecturer reopens a closed case: one closed for a missing explanation goes back to the assignee
	 * with a new explanation window; any other goes back to the lecturer's queue.
	 */
	@Transactional
	public DelayCaseResponse reopen(UUID userId, UUID projectId, UUID caseId) {
		Viewer viewer = viewer(userId, projectId);
		TaskDelayCase delay = requireCase(projectId, caseId);
		if (!viewer.lecturer()) {
			throw forbidden("Only the course lecturer can reopen a delay case.");
		}
		if (!delay.getStatus().closed()) {
			throw conflict("Only a closed delay case can be reopened.");
		}
		boolean expired = delay.getCloseReason() == CloseReason.EXPLANATION_EXPIRED;
		delay.setClosedAt(null);
		delay.setCloseReason(null);
		delay.setLecturerOutcome(null);
		delay.setLecturerComment(null);
		delay.setLecturerReviewedAt(null);
		delay.setLecturerReviewedByUserId(null);
		if (expired) {
			delay.setStatus(DelayCaseStatus.OPEN);
			delay.setExplanationDueAt(now().plus(explanationWindow));
		} else {
			delay.setStatus(DelayCaseStatus.AWAITING_LECTURER);
		}
		TaskDelayCase saved = cases.save(delay);
		if (expired) {
			notifyUser(assigneeUserId(saved), "Hồ sơ trễ hạn được mở lại",
					"Giảng viên đã mở lại hồ sơ trễ của task " + label(saved.getTask())
							+ ". Hãy giải trình trước " + saved.getExplanationDueAt().format(MINUTE_DAY) + ".",
					saved, "reopened");
		}
		return toResponse(saved, viewer);
	}

	// ------------------------------------------------------------------ on-time rate

	@Transactional(readOnly = true)
	public OnTimeRateResponse onTimeRate(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		LocalDate today = today();
		Set<String> excused = new HashSet<>();
		for (Object[] row : cases.findObjectiveClosedTaskDues(projectId)) {
			excused.add(row[0] + "|" + row[1]);
		}
		Map<UUID, int[]> counts = new LinkedHashMap<>();
		for (Task task : tasks.findActiveFetchedByProject_Id(projectId)) {
			if (task.getAssigneeStudent() == null || task.getDueDate() == null) {
				continue;
			}
			boolean done = task.getStatus() == TaskStatus.DONE;
			if (!done && !today.isAfter(task.getDueDate().toLocalDate())) {
				continue; // not due yet: nothing to judge
			}
			int[] c = counts.computeIfAbsent(task.getAssigneeStudent().getId(), id -> new int[3]);
			c[0]++;
			if (isLate(task, today)) {
				if (excused.contains(task.getId() + "|" + task.getDueDate())) {
					c[2]++;
				} else {
					c[1]++;
				}
			}
		}
		List<MemberOnTimeRate> rows = new ArrayList<>();
		for (StudentProfile student : activeMembers(projectId)) {
			int[] c = counts.getOrDefault(student.getId(), new int[3]);
			int evaluated = c[0];
			int late = c[1];
			int excusedLate = c[2];
			BigDecimal rate = evaluated == 0
					? null
					: BigDecimal.valueOf((evaluated - late) * 100.0 / evaluated).setScale(1, RoundingMode.HALF_UP);
			UserAccount account = student.getUserAccount();
			rows.add(new MemberOnTimeRate(
					student.getId(),
					account == null ? null : account.getFullName(),
					student.getStudentCode(),
					evaluated,
					evaluated - late - excusedLate,
					late,
					excusedLate,
					rate));
		}
		return new OnTimeRateResponse(projectId, today, rows);
	}

	// ------------------------------------------------------------------ helpers

	/** Who is looking: assigned lecturer, or an active member (leader or not) of the project's team. */
	record Viewer(UUID userId, boolean lecturer, boolean leader) {}

	private Viewer viewer(UUID userId, UUID projectId) {
		authorization.requireReader(userId, projectId);
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() == AccountRole.LECTURER) {
			return new Viewer(userId, true, false);
		}
		RoleInTeam role = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
		return new Viewer(userId, false, role == RoleInTeam.LEADER);
	}

	private TaskDelayCase requireCase(UUID projectId, UUID caseId) {
		return cases.findFetchedByIdAndProject(caseId, projectId)
				.orElseThrow(() -> new IntegrationException(
						IntegrationErrorCode.DELAY_CASE_NOT_FOUND, HttpStatus.NOT_FOUND, "Delay case was not found in this project."));
	}

	private static void requireStatus(TaskDelayCase delay, DelayCaseStatus expected, String message) {
		if (delay.getStatus() != expected) {
			throw conflict(message);
		}
	}

	private DelayCaseResponse toResponse(TaskDelayCase delay, Viewer viewer) {
		UUID assignee = assigneeUserId(delay);
		boolean isAssignee = Objects.equals(viewer.userId(), assignee);
		boolean seesDetails = isAssignee || viewer.leader() || viewer.lecturer();
		DelayCaseStatus status = delay.getStatus();
		Permissions permissions = new Permissions(
				status == DelayCaseStatus.OPEN && isAssignee,
				status == DelayCaseStatus.AWAITING_LEADER && viewer.leader() && !isAssignee,
				status == DelayCaseStatus.AWAITING_LECTURER && viewer.lecturer(),
				status.closed() && viewer.lecturer());
		StudentProfile student = delay.getStudentProfile();
		UserAccount account = student == null ? null : student.getUserAccount();
		DelayCauseCategory category = delay.getCategory();
		return new DelayCaseResponse(
				delay.getId(),
				delay.getProject() == null ? null : delay.getProject().getId(),
				taskRef(delay.getTask()),
				student == null
						? null
						: new StudentRef(student.getId(), account == null ? null : account.getId(),
								account == null ? null : account.getFullName(), student.getStudentCode()),
				delay.getDueDate() == null ? null : delay.getDueDate().toLocalDate(),
				delay.getOpenedAt(),
				delay.getExplanationDueAt(),
				status.name(),
				category == null ? null : category.name(),
				category == null ? null : category.group().name(),
				seesDetails ? delay.getExplanationNote() : null,
				taskRef(delay.getBlockingTask()),
				seesDetails ? delay.getEvidenceUrl() : null,
				delay.getExplainedAt(),
				readSignals(delay.getSignalsJson()),
				delay.getVerification() == null ? null : delay.getVerification().name(),
				delay.getVerificationNote(),
				delay.getLeaderDecision() == null ? null : delay.getLeaderDecision().name(),
				seesDetails ? delay.getLeaderComment() : null,
				delay.getLeaderReviewedAt(),
				delay.getLecturerOutcome() == null ? null : delay.getLecturerOutcome().name(),
				seesDetails ? delay.getLecturerComment() : null,
				delay.getLecturerReviewedAt(),
				delay.getClosedAt(),
				delay.getCloseReason() == null ? null : delay.getCloseReason().name(),
				permissions);
	}

	private static TaskRef taskRef(Task task) {
		return task == null ? null : new TaskRef(task.getId(), task.getExternalKey(), task.getTitle());
	}

	private static UUID assigneeUserId(TaskDelayCase delay) {
		StudentProfile student = delay.getStudentProfile();
		return student == null || student.getUserAccount() == null ? null : student.getUserAccount().getId();
	}

	private static String studentName(TaskDelayCase delay) {
		StudentProfile student = delay.getStudentProfile();
		UserAccount account = student == null ? null : student.getUserAccount();
		if (account != null && account.getFullName() != null && !account.getFullName().isBlank()) {
			return account.getFullName().trim();
		}
		return student == null || student.getStudentCode() == null ? "Thành viên" : student.getStudentCode();
	}

	private static String label(Task task) {
		if (task == null) {
			return "";
		}
		return task.getExternalKey() != null && !task.getExternalKey().isBlank() ? task.getExternalKey() : String.valueOf(task.getTitle());
	}

	private List<StudentProfile> activeMembers(UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		if (team == null) {
			return List.of();
		}
		List<StudentProfile> out = new ArrayList<>();
		for (TeamMember member : members.findFetchedByTeam_Id(team.getId())) {
			if (member.getCourseEnrollment() != null
					&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
					&& member.getCourseEnrollment().getStudentProfile() != null) {
				out.add(member.getCourseEnrollment().getStudentProfile());
			}
		}
		return out;
	}

	private List<UUID> leaderUserIds(UUID projectId) {
		Team team = teams.findByProject_Id(projectId).orElse(null);
		if (team == null) {
			return List.of();
		}
		List<UUID> out = new ArrayList<>();
		for (TeamMember member : members.findFetchedByTeam_Id(team.getId())) {
			if (member.getRoleInTeam() == RoleInTeam.LEADER
					&& member.getCourseEnrollment() != null
					&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
					&& member.getCourseEnrollment().getStudentProfile() != null
					&& member.getCourseEnrollment().getStudentProfile().getUserAccount() != null) {
				out.add(member.getCourseEnrollment().getStudentProfile().getUserAccount().getId());
			}
		}
		return out;
	}

	private void notifyLecturer(TaskDelayCase delay) {
		Project project = delay.getProject();
		UserAccount lecturer = project == null
						|| project.getCourse() == null
						|| project.getCourse().getInstructor() == null
				? null
				: project.getCourse().getInstructor().getUserAccount();
		if (lecturer != null) {
			notifyUser(lecturer.getId(), "Hồ sơ trễ hạn chờ duyệt",
					"Hồ sơ trễ của task " + label(delay.getTask()) + " (" + studentName(delay) + ") cần giảng viên quyết định.",
					delay, "awaiting-lecturer");
		}
	}

	private void notifyClosed(TaskDelayCase delay) {
		String outcome = delay.getStatus() == DelayCaseStatus.CLOSED_OBJECTIVE
				? "khách quan, không tính là trễ"
				: "chủ quan, được tính là trễ";
		notifyUser(assigneeUserId(delay), "Hồ sơ trễ hạn đã có kết quả",
				"Lần trễ của task " + label(delay.getTask()) + " được ghi nhận là " + outcome + ".", delay, "closed");
	}

	private void notifyUser(UUID userId, String title, String message, TaskDelayCase delay, String step) {
		if (userId == null) {
			return;
		}
		try {
			notifications.createNotification(
					userId,
					NotificationType.TASK,
					title,
					message.length() > 1000 ? message.substring(0, 999) + "…" : message,
					null,
					"delay-case:" + delay.getId() + ":" + step + ":" + UUID.randomUUID());
		} catch (RuntimeException ex) {
			// A notification failure must not undo the case change.
			log.warn("delay case notification failed caseId={} step={} type={}", delay.getId(), step, ex.getClass().getSimpleName());
		}
	}

	private String writeSignals(DelaySignals signals) {
		try {
			return json.writeValueAsString(signals);
		} catch (Exception ex) {
			return null;
		}
	}

	private DelaySignals readSignals(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return json.readValue(value, DelaySignals.class);
		} catch (Exception ex) {
			return null;
		}
	}

	private static String trimToNull(String value) {
		if (value == null) {
			return null;
		}
		String trimmed = value.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}

	private static IntegrationException conflict(String message) {
		return new IntegrationException(IntegrationErrorCode.DELAY_CASE_STATE_CONFLICT, HttpStatus.CONFLICT, message);
	}

	private static IntegrationException invalid(String message) {
		return new IntegrationException(IntegrationErrorCode.DELAY_CASE_INPUT_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static IntegrationException forbidden(String message) {
		return new IntegrationException(IntegrationErrorCode.DELAY_CASE_FORBIDDEN, HttpStatus.FORBIDDEN, message);
	}
}

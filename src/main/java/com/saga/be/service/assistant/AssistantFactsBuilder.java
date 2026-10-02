package com.saga.be.service.assistant;

import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.dto.delay.DelayCaseDtos.MemberOnTimeRate;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.assistant.AssistantFacts.Fact;
import com.saga.be.service.assistant.AssistantFacts.Hints;
import com.saga.be.service.assistant.AssistantFacts.Viewer;
import com.saga.be.service.delay.TaskDelayCaseService;
import com.saga.be.service.projection.ProjectProjectionReadService;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Selects the facts one question may be answered from. Every read goes through the same services
 * the app's own screens use (task board, commit list, delay cases, on-time rate), with the asker's
 * own user id, so the assistant never sees more than the asker could open themselves -- including
 * the delay case privacy rules. Nothing here calls AI or decides anything; it only collects and
 * bounds data (saga-ai accepts at most 100 evidence items).
 */
@Component
@Profile("!test")
public class AssistantFactsBuilder {

	static final int MAX_SPRINTS = 2;
	static final int MAX_MEMBERS = 12;
	static final int MAX_TASKS = 40;
	static final int MAX_DELAY_CASES = 15;
	static final int MAX_COMMITS = 20;
	static final int DUE_SOON_DAYS = 3;
	private static final int TEXT_LIMIT = 200;
	private static final Pattern TASK_KEY = Pattern.compile("\\b([A-Za-z][A-Za-z0-9]{0,15}-\\d{1,6})\\b");

	private final ProjectRepository projects;
	private final UserAccountRepository users;
	private final StudentProfileRepository students;
	private final TeamByProjectRepository teams;
	private final TeamMemberRepository members;
	private final SprintRepository sprints;
	private final GitCommitRepository commits;
	private final ProjectProjectionReadService projections;
	private final TaskDelayCaseService delays;
	private final Clock clock;
	private final ZoneId zone;

	@Autowired
	public AssistantFactsBuilder(
			ProjectRepository projects,
			UserAccountRepository users,
			StudentProfileRepository students,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			SprintRepository sprints,
			GitCommitRepository commits,
			ProjectProjectionReadService projections,
			TaskDelayCaseService delays,
			@Value("${saga.assistant.zone:Asia/Ho_Chi_Minh}") String zone) {
		this(projects, users, students, teams, members, sprints, commits, projections, delays,
				Clock.systemDefaultZone(), ZoneId.of(zone));
	}

	AssistantFactsBuilder(
			ProjectRepository projects,
			UserAccountRepository users,
			StudentProfileRepository students,
			TeamByProjectRepository teams,
			TeamMemberRepository members,
			SprintRepository sprints,
			GitCommitRepository commits,
			ProjectProjectionReadService projections,
			TaskDelayCaseService delays,
			Clock clock,
			ZoneId zone) {
		this.projects = projects;
		this.users = users;
		this.students = students;
		this.teams = teams;
		this.members = members;
		this.sprints = sprints;
		this.commits = commits;
		this.projections = projections;
		this.delays = delays;
		this.clock = clock;
		this.zone = zone;
	}

	/** The caller has already checked that {@code userId} may read {@code projectId}. */
	public AssistantFacts build(UUID userId, UUID projectId, String question) {
		LocalDate today = LocalDate.now(clock.withZone(zone));
		Project project = projects.findById(projectId).orElseThrow();
		Viewer viewer = viewer(userId, projectId);

		List<ProjectTaskResponse> tasks = projections.listTasks(userId, projectId).stream()
				.filter(task -> task.migration() == null || !task.migration().superseded())
				.toList();
		List<MemberOnTimeRate> roster = delays.onTimeRate(userId, projectId).members();
		Map<UUID, String> memberNames = new HashMap<>();
		for (MemberOnTimeRate member : roster) {
			memberNames.put(member.studentProfileId(), member.fullName());
		}
		Map<UUID, RoleInTeam> teamRoles = teamRoles(projectId);

		String normalizedQuestion = normalize(question);
		List<ProjectTaskResponse> matchedTasks = matchTasks(question, tasks);
		List<MemberOnTimeRate> matchedMembers = matchMembers(normalizedQuestion, roster);

		List<Fact> items = new ArrayList<>();
		List<Sprint> activeSprints = sprints.findActiveByProject_Id(projectId).stream()
				.filter(sprint -> "active".equalsIgnoreCase(sprint.getState()))
				.limit(MAX_SPRINTS)
				.toList();
		List<DelayCaseResponse> delayCases = delays.list(userId, projectId, null, null);

		items.add(projectFact(project, tasks, roster, delayCases, today));
		for (Sprint sprint : activeSprints) {
			items.add(sprintFact(sprint, tasks));
		}
		for (MemberOnTimeRate member : roster.stream().limit(MAX_MEMBERS).toList()) {
			items.add(memberFact(projectId, member, tasks, teamRoles, viewer, today));
		}
		List<ProjectTaskResponse> selectedTasks = selectTasks(tasks, matchedTasks, matchedMembers, activeSprints, viewer, today);
		for (ProjectTaskResponse task : selectedTasks) {
			items.add(taskFact(task, today));
		}
		for (DelayCaseResponse delay : selectDelayCases(delayCases, matchedTasks)) {
			items.add(delayFact(delay));
		}
		for (ProjectCommitResponse commit : selectCommits(userId, projectId, matchedTasks, matchedMembers)) {
			items.add(commitFact(commit, memberNames));
		}

		List<UUID> overdue = tasks.stream()
				.filter(task -> isOverdue(task, today))
				.sorted(Comparator.comparing(ProjectTaskResponse::dueDate))
				.map(ProjectTaskResponse::id)
				.toList();
		Hints hints = new Hints(
				projectId,
				activeSprints.stream().map(Sprint::getId).toList(),
				overdue,
				matchedTasks.stream().map(ProjectTaskResponse::id).toList(),
				matchedMembers.stream().map(MemberOnTimeRate::studentProfileId).toList());
		return new AssistantFacts(List.copyOf(items), viewer, today, hints);
	}

	// ------------------------------------------------------------------ viewer & team

	private Viewer viewer(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() == AccountRole.LECTURER) {
			return new Viewer(userId, "LECTURER", null, account.getFullName(), null);
		}
		UUID memberId = students.findByUserAccount_Id(userId).map(StudentProfile::getId).orElse(null);
		RoleInTeam role = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
		return new Viewer(userId, "STUDENT", role == null ? null : role.name(), account.getFullName(), memberId);
	}

	private Map<UUID, RoleInTeam> teamRoles(UUID projectId) {
		Map<UUID, RoleInTeam> roles = new HashMap<>();
		teams.findByProject_Id(projectId).ifPresent(team -> {
			for (TeamMember member : members.findFetchedByTeam_Id(team.getId())) {
				if (member.getCourseEnrollment() != null
						&& member.getCourseEnrollment().getEnrollmentStatus() == EnrollmentStatus.ACTIVE
						&& member.getCourseEnrollment().getStudentProfile() != null) {
					roles.put(member.getCourseEnrollment().getStudentProfile().getId(), member.getRoleInTeam());
				}
			}
		});
		return roles;
	}

	// ------------------------------------------------------------------ matching

	/** Task keys written in the question (SAGA-12, saga-12), matched case-insensitively. */
	static List<ProjectTaskResponse> matchTasks(String question, List<ProjectTaskResponse> tasks) {
		Set<String> keys = new LinkedHashSet<>();
		Matcher matcher = TASK_KEY.matcher(question == null ? "" : question);
		while (matcher.find()) {
			keys.add(matcher.group(1).toUpperCase(Locale.ROOT));
		}
		List<ProjectTaskResponse> matched = new ArrayList<>();
		for (String key : keys) {
			tasks.stream()
					.filter(task -> task.externalKey() != null && task.externalKey().equalsIgnoreCase(key))
					.findFirst()
					.ifPresent(matched::add);
		}
		return matched;
	}

	/**
	 * Members named in the question: the full name, the given name (last word, as Vietnamese names
	 * are written) or the student code, compared without diacritics or case.
	 */
	static List<MemberOnTimeRate> matchMembers(String normalizedQuestion, List<MemberOnTimeRate> roster) {
		Set<String> tokens = new HashSet<>(Arrays.asList(normalizedQuestion.split("[^a-z0-9]+")));
		List<MemberOnTimeRate> matched = new ArrayList<>();
		for (MemberOnTimeRate member : roster) {
			String name = normalize(member.fullName());
			String code = normalize(member.studentCode());
			String[] words = name.isBlank() ? new String[0] : name.split("\\s+");
			String given = words.length == 0 ? "" : words[words.length - 1];
			boolean byName = !name.isBlank() && normalizedQuestion.contains(name);
			boolean byGiven = given.length() >= 2 && tokens.contains(given);
			boolean byCode = !code.isBlank() && tokens.contains(code);
			if (byName || byGiven || byCode) {
				matched.add(member);
			}
		}
		return matched;
	}

	/** Lower case, no diacritics (đ becomes d), single spaces. */
	static String normalize(String value) {
		if (value == null) {
			return "";
		}
		String stripped = Normalizer.normalize(value.replace('đ', 'd').replace('Đ', 'D'), Normalizer.Form.NFD)
				.replaceAll("\\p{M}+", "");
		return stripped.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
	}

	// ------------------------------------------------------------------ selection

	private List<ProjectTaskResponse> selectTasks(
			List<ProjectTaskResponse> tasks,
			List<ProjectTaskResponse> matchedTasks,
			List<MemberOnTimeRate> matchedMembers,
			List<Sprint> activeSprints,
			Viewer viewer,
			LocalDate today) {
		Map<UUID, ProjectTaskResponse> selected = new LinkedHashMap<>();
		Set<UUID> sprintIds = new LinkedHashSet<>(activeSprints.stream().map(Sprint::getId).toList());
		Set<UUID> memberIds = new LinkedHashSet<>(matchedMembers.stream().map(MemberOnTimeRate::studentProfileId).toList());
		addAll(selected, matchedTasks);
		addAll(selected, tasks.stream().filter(task -> isOverdue(task, today)).sorted(byDue()).toList());
		addAll(selected, tasks.stream().filter(task -> isDueSoon(task, today)).sorted(byDue()).toList());
		addAll(selected, tasks.stream().filter(task -> task.assigneeStudentId() != null && memberIds.contains(task.assigneeStudentId())).sorted(byDue()).toList());
		if (viewer.memberId() != null) {
			addAll(selected, tasks.stream().filter(task -> !isDone(task) && viewer.memberId().equals(task.assigneeStudentId())).sorted(byDue()).toList());
		}
		addAll(selected, tasks.stream().filter(task -> !isDone(task) && task.sprint() != null && sprintIds.contains(task.sprint().id())).sorted(byDue()).toList());
		addAll(selected, tasks.stream()
				.sorted(Comparator.comparing(ProjectTaskResponse::updatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
				.toList());
		return selected.values().stream().limit(MAX_TASKS).toList();
	}

	private static void addAll(Map<UUID, ProjectTaskResponse> selected, List<ProjectTaskResponse> tasks) {
		for (ProjectTaskResponse task : tasks) {
			if (selected.size() >= MAX_TASKS) {
				return;
			}
			selected.putIfAbsent(task.id(), task);
		}
	}

	private static Comparator<ProjectTaskResponse> byDue() {
		return Comparator.comparing(ProjectTaskResponse::dueDate, Comparator.nullsLast(Comparator.naturalOrder()));
	}

	/** Open cases first, a named task's cases before the rest, then newest. */
	private static List<DelayCaseResponse> selectDelayCases(List<DelayCaseResponse> delayCases, List<ProjectTaskResponse> matchedTasks) {
		Set<UUID> matched = new LinkedHashSet<>(matchedTasks.stream().map(ProjectTaskResponse::id).toList());
		return delayCases.stream()
				.sorted(Comparator
						.comparing((DelayCaseResponse delay) -> delay.task() == null || !matched.contains(delay.task().id()))
						.thenComparing(delay -> delay.status() != null && delay.status().startsWith("CLOSED")))
				.limit(MAX_DELAY_CASES)
				.toList();
	}

	/** Commits of named tasks, then of named members, then the project's latest. */
	private List<ProjectCommitResponse> selectCommits(
			UUID userId, UUID projectId, List<ProjectTaskResponse> matchedTasks, List<MemberOnTimeRate> matchedMembers) {
		Map<UUID, ProjectCommitResponse> selected = new LinkedHashMap<>();
		for (ProjectTaskResponse task : matchedTasks.stream().limit(3).toList()) {
			addCommits(selected, projections.listTaskCommits(userId, projectId, task.id(), 0, 5, true).items());
		}
		for (MemberOnTimeRate member : matchedMembers.stream().limit(2).toList()) {
			addCommits(selected, projections.listCommits(userId, projectId, 0, 5, member.studentProfileId()).items());
		}
		addCommits(selected, projections.listCommits(userId, projectId, 0, 10).items());
		return selected.values().stream().limit(MAX_COMMITS).toList();
	}

	private static void addCommits(Map<UUID, ProjectCommitResponse> selected, List<ProjectCommitResponse> rows) {
		for (ProjectCommitResponse commit : rows) {
			if (selected.size() >= MAX_COMMITS) {
				return;
			}
			selected.putIfAbsent(commit.id(), commit);
		}
	}

	// ------------------------------------------------------------------ facts

	private Fact projectFact(
			Project project, List<ProjectTaskResponse> tasks, List<MemberOnTimeRate> roster, List<DelayCaseResponse> delayCases, LocalDate today) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", text(project.getName()));
		payload.put("today", today.toString());
		payload.put("taskCount", tasks.size());
		payload.put("taskStatusCounts", statusCounts(tasks));
		payload.put("overdueTaskCount", tasks.stream().filter(task -> isOverdue(task, today)).count());
		payload.put("dueSoonTaskCount", tasks.stream().filter(task -> isDueSoon(task, today)).count());
		payload.put("dueSoonMeans", "not done and due within the next " + DUE_SOON_DAYS + " days");
		payload.put("unassignedOpenTaskCount", tasks.stream().filter(task -> !isDone(task) && task.assigneeStudentId() == null).count());
		payload.put("activeMemberCount", roster.size());
		payload.put("openDelayCaseCount", delayCases.stream().filter(delay -> delay.status() != null && !delay.status().startsWith("CLOSED")).count());
		return new Fact(AssistantFacts.PROJECT, project.getId(), text(project.getName()), null, null, payload);
	}

	private static Fact sprintFact(Sprint sprint, List<ProjectTaskResponse> tasks) {
		List<ProjectTaskResponse> inSprint = tasks.stream()
				.filter(task -> task.sprint() != null && sprint.getId().equals(task.sprint().id()))
				.toList();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", text(sprint.getName()));
		payload.put("state", sprint.getState());
		payload.put("startDate", day(sprint.getStartDate()));
		payload.put("endDate", day(sprint.getEndDate()));
		payload.put("taskCount", inSprint.size());
		payload.put("taskStatusCounts", statusCounts(inSprint));
		payload.put("storyPointsDone", inSprint.stream().filter(AssistantFactsBuilder::isDone).mapToInt(AssistantFactsBuilder::points).sum());
		payload.put("storyPointsTotal", inSprint.stream().mapToInt(AssistantFactsBuilder::points).sum());
		return new Fact(AssistantFacts.SPRINT, sprint.getId(), text(sprint.getName()), null, null, payload);
	}

	private Fact memberFact(
			UUID projectId, MemberOnTimeRate member, List<ProjectTaskResponse> tasks, Map<UUID, RoleInTeam> teamRoles, Viewer viewer, LocalDate today) {
		UUID id = member.studentProfileId();
		List<ProjectTaskResponse> own = tasks.stream().filter(task -> id.equals(task.assigneeStudentId())).toList();
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("name", text(member.fullName()));
		payload.put("studentCode", member.studentCode());
		RoleInTeam role = teamRoles.get(id);
		payload.put("teamRole", role == null ? null : role.name());
		payload.put("isViewer", id.equals(viewer.memberId()));
		payload.put("assignedTaskStatusCounts", statusCounts(own));
		payload.put("overdueTaskCount", own.stream().filter(task -> isOverdue(task, today)).count());
		payload.put("storyPointsDone", own.stream().filter(AssistantFactsBuilder::isDone).mapToInt(AssistantFactsBuilder::points).sum());
		payload.put("storyPointsAssigned", own.stream().mapToInt(AssistantFactsBuilder::points).sum());
		Map<String, Object> onTime = new LinkedHashMap<>();
		onTime.put("evaluatedTasks", member.evaluatedTasks());
		onTime.put("onTimeTasks", member.onTimeTasks());
		onTime.put("lateTasks", member.lateTasks());
		onTime.put("excusedLateTasks", member.excusedLateTasks());
		onTime.put("onTimeRatePercent", member.onTimeRate());
		payload.put("onTimeRate", onTime);
		long commitCount = 0;
		LocalDateTime lastCommitAt = null;
		List<Object[]> commitRows = commits.countAndMaxCommittedAtByProjectAndAuthor(projectId, id);
		if (!commitRows.isEmpty() && commitRows.getFirst() != null) {
			Object[] row = commitRows.getFirst();
			commitCount = row[0] == null ? 0 : ((Number) row[0]).longValue();
			lastCommitAt = row.length > 1 ? (LocalDateTime) row[1] : null;
		}
		payload.put("authoredCommitCount", commitCount);
		payload.put("lastCommitAt", lastCommitAt == null ? null : lastCommitAt.toString());
		return new Fact(AssistantFacts.MEMBER, id, text(member.fullName()), null, null, payload);
	}

	private static Fact taskFact(ProjectTaskResponse task, LocalDate today) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("key", task.externalKey());
		payload.put("title", text(task.title()));
		payload.put("status", task.status());
		payload.put("issueTypeLevel", task.issueTypeLevel());
		payload.put("assignee", text(task.assigneeDisplayName()));
		payload.put("assigneeMemberId", task.assigneeStudentId() == null ? null : task.assigneeStudentId().toString());
		payload.put("sprint", task.sprint() == null ? null : text(task.sprint().name()));
		payload.put("dueDate", task.dueDate() == null ? null : task.dueDate().toString());
		payload.put("overdue", isOverdue(task, today));
		payload.put("dueSoon", isDueSoon(task, today));
		payload.put("storyPoint", task.storyPoint());
		payload.put("linkedCommitCount", task.linkedCommitCount());
		payload.put("parentKey", task.parent() == null ? null : task.parent().externalKey());
		return new Fact(AssistantFacts.TASK, task.id(), taskLabel(task.externalKey(), task.title()), task.id(), null, payload);
	}

	private static Fact delayFact(DelayCaseResponse delay) {
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("taskKey", delay.task() == null ? null : delay.task().externalKey());
		payload.put("taskTitle", delay.task() == null ? null : text(delay.task().title()));
		payload.put("member", delay.student() == null ? null : text(delay.student().fullName()));
		payload.put("missedDueDate", delay.dueDate() == null ? null : delay.dueDate().toString());
		payload.put("status", delay.status());
		payload.put("category", delay.category());
		payload.put("categoryGroup", delay.categoryGroup());
		payload.put("verification", delay.verification());
		payload.put("verificationNote", text(delay.verificationNote()));
		// Null unless this viewer may read it (assignee, team leader, lecturer): the service already applied that rule.
		payload.put("explanationNote", text(delay.explanationNote()));
		payload.put("leaderDecision", delay.leaderDecision());
		payload.put("lecturerOutcome", delay.lecturerOutcome());
		payload.put("closeReason", delay.closeReason());
		String key = delay.task() == null ? null : delay.task().externalKey();
		return new Fact(AssistantFacts.DELAY_CASE, delay.id(), "Hồ sơ trễ hạn " + (key == null ? "" : key).trim(),
				delay.task() == null ? null : delay.task().id(), null, payload);
	}

	private static Fact commitFact(ProjectCommitResponse commit, Map<UUID, String> memberNames) {
		String shortSha = commit.sha() == null ? null : commit.sha().substring(0, Math.min(7, commit.sha().length()));
		String title = firstLine(commit.message());
		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("sha", shortSha);
		payload.put("message", title);
		payload.put("repository", commit.repositoryFullName());
		payload.put("committedAt", commit.committedAt() == null ? null : commit.committedAt().toString());
		payload.put("author", commit.authorStudentId() == null ? null : text(memberNames.get(commit.authorStudentId())));
		payload.put("authorMemberId", commit.authorStudentId() == null ? null : commit.authorStudentId().toString());
		payload.put("isMerge", commit.isMerge());
		return new Fact(AssistantFacts.COMMIT, commit.id(), (shortSha == null ? "" : shortSha) + (title == null ? "" : " · " + title),
				null, commit.sha(), payload);
	}

	// ------------------------------------------------------------------ helpers

	static boolean isDone(ProjectTaskResponse task) {
		return "DONE".equals(task.status());
	}

	static boolean isOverdue(ProjectTaskResponse task, LocalDate today) {
		return !isDone(task) && task.dueDate() != null && task.dueDate().isBefore(today);
	}

	static boolean isDueSoon(ProjectTaskResponse task, LocalDate today) {
		return !isDone(task)
				&& task.dueDate() != null
				&& !task.dueDate().isBefore(today)
				&& !task.dueDate().isAfter(today.plusDays(DUE_SOON_DAYS));
	}

	private static int points(ProjectTaskResponse task) {
		return task.storyPoint() == null ? 0 : task.storyPoint();
	}

	private static Map<String, Long> statusCounts(List<ProjectTaskResponse> tasks) {
		Map<String, Long> counts = new TreeMap<>();
		for (ProjectTaskResponse task : tasks) {
			counts.merge(Objects.requireNonNullElse(task.status(), "UNKNOWN"), 1L, Long::sum);
		}
		return counts;
	}

	static String taskLabel(String key, String title) {
		String safeKey = key == null ? "" : key;
		String safeTitle = text(title);
		if (safeTitle == null) {
			return safeKey;
		}
		return safeKey.isBlank() ? safeTitle : safeKey + " · " + safeTitle;
	}

	private static String day(LocalDateTime value) {
		return value == null ? null : value.toLocalDate().toString();
	}

	private static String firstLine(String message) {
		if (message == null) {
			return null;
		}
		String line = message.strip().lines().findFirst().orElse("");
		return text(line);
	}

	/** Untrusted free text, bounded. */
	static String text(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String trimmed = value.strip();
		return trimmed.length() <= TEXT_LIMIT ? trimmed : trimmed.substring(0, TEXT_LIMIT - 1) + "…";
	}
}

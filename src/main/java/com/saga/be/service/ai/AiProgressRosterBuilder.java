package com.saga.be.service.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.dto.ai.CommitAiReviewDtos;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Per-team and per-member facts for the lecturer's progress report and its Word export: who has which
 * overdue task, how many tasks each member finished, commits, commits attached to no task, commits the
 * AI review warned about. Everything is an exact count from the local database, fetched in a fixed
 * number of bulk queries for the whole course (never one query per team or member). "Most active" is
 * ranked by finished story points, finished tasks and commits only; it is not the contribution score.
 */
@Component
@Profile("!test")
public class AiProgressRosterBuilder {

	static final int OVERDUE_LIST_CAP = 30;
	static final int TEAM_OVERDUE_CAP = 15;
	static final int ATTENTION_CAP = 20;
	static final int MOST_ACTIVE_CAP = 5;
	static final int UNLINKED_ATTENTION_THRESHOLD = 3;

	private final TaskRepository tasks;
	private final TeamMemberRepository members;
	private final GitCommitRepository commits;
	private final AiAnalysisRunRepository runs;
	private final CourseRepository courses;
	private final ObjectMapper mapper;

	public AiProgressRosterBuilder(TaskRepository tasks, TeamMemberRepository members, GitCommitRepository commits,
			AiAnalysisRunRepository runs, CourseRepository courses, ObjectMapper mapper) {
		this.tasks = tasks;
		this.members = members;
		this.commits = commits;
		this.runs = runs;
		this.courses = courses;
		this.mapper = mapper;
	}

	/** Course header: code, name, subject, semester, lecturer (no lazy loading). */
	public Map<String, Object> courseHeader(UUID courseId) {
		Map<String, Object> header = new LinkedHashMap<>();
		List<Object[]> rows = courseId == null ? List.of() : courses.findReportHeader(courseId);
		if (rows.isEmpty()) return header;
		Object[] row = rows.getFirst();
		header.put("courseCode", row[0]);
		header.put("courseName", row[1]);
		header.put("subject", join(row[2], row[3]));
		header.put("semester", row[4]);
		header.put("lecturer", row[5]);
		return header;
	}

	/**
	 * Adds {@code teams}, {@code totals}, {@code overdueTasks}, {@code attention} and {@code mostActive}
	 * to {@code facts}. {@code now} is the same instant the overdue counts use.
	 */
	public void addRoster(Map<String, Object> facts, List<Team> teams, LocalDateTime now) {
		List<Team> withProject = teams.stream().filter(team -> team.getProject() != null).toList();
		if (withProject.isEmpty()) return;
		List<UUID> projectIds = withProject.stream().map(team -> team.getProject().getId()).toList();
		List<UUID> teamIds = withProject.stream().map(Team::getId).toList();

		Map<UUID, List<Member>> membersByTeam = new HashMap<>();
		Map<UUID, Member> memberById = new HashMap<>();
		for (Object[] row : members.findActiveProgressReportRows(teamIds)) {
			Member member = new Member((UUID) row[1], (String) row[2], (String) row[3], row[4] instanceof RoleInTeam role ? role.name() : null);
			membersByTeam.computeIfAbsent((UUID) row[0], ignored -> new ArrayList<>()).add(member);
			memberById.put(member.id, member);
		}
		Map<UUID, TeamStats> statsByProject = new HashMap<>();
		for (Team team : withProject) statsByProject.put(team.getProject().getId(), new TeamStats(team));

		List<Map<String, Object>> overdueAll = new ArrayList<>();
		for (Object[] row : tasks.findProgressReportRows(projectIds)) {
			TeamStats team = statsByProject.get((UUID) row[0]);
			if (team == null) continue;
			TaskStatus status = (TaskStatus) row[3];
			LocalDateTime due = (LocalDateTime) row[4];
			int points = row[5] instanceof Integer p ? p : 0;
			Member assignee = row[6] == null ? null : memberById.get((UUID) row[6]);
			boolean done = status == TaskStatus.DONE;
			team.tasksTotal++;
			team.statusCounts.merge(status == null ? "UNKNOWN" : status.name(), 1L, Long::sum);
			if (done) team.tasksDone++;
			if (assignee != null) {
				assignee.tasksTotal++;
				assignee.storyPointsTotal += points;
				if (done) { assignee.tasksDone++; assignee.storyPointsDone += points; }
				else if (status == TaskStatus.IN_PROGRESS || status == TaskStatus.IN_REVIEW) assignee.tasksInProgress++;
			} else if (!done) {
				team.unassignedOpenTasks++;
			}
			if (!done && due != null && due.isBefore(now)) {
				Map<String, Object> overdue = new LinkedHashMap<>();
				overdue.put("team", team.name);
				overdue.put("key", row[1]);
				overdue.put("title", truncate((String) row[2]));
				overdue.put("assignee", assignee == null ? "Chưa giao" : assignee.name);
				overdue.put("dueDate", due.toLocalDate().toString());
				overdue.put("overdueDays", Math.max(1, ChronoUnit.DAYS.between(due.toLocalDate(), now.toLocalDate())));
				team.overdue.add(overdue);
				overdueAll.add(overdue);
				if (assignee != null) assignee.overdue.add(overdue);
			}
		}

		for (Object[] row : commits.countProgressReportCommits(projectIds)) {
			TeamStats team = statsByProject.get((UUID) row[0]);
			if (team == null) continue;
			long count = ((Number) row[2]).longValue();
			team.commits += count;
			Member author = row[1] == null ? null : memberById.get((UUID) row[1]);
			if (author == null) { team.unmappedCommits += count; continue; }
			author.commits += count;
			author.lastCommitAt = row[3] instanceof LocalDateTime last ? last.toLocalDate().toString() : null;
		}
		for (Object[] row : commits.countProgressReportUnlinkedCommits(projectIds)) {
			TeamStats team = statsByProject.get((UUID) row[0]);
			if (team == null) continue;
			long count = ((Number) row[2]).longValue();
			team.unlinkedCommits += count;
			Member author = row[1] == null ? null : memberById.get((UUID) row[1]);
			if (author != null) author.unlinkedCommits += count;
		}
		Set<UUID> seenCommits = new HashSet<>();
		for (Object[] row : runs.findCompletedCommitReviewsForReport(projectIds)) {
			if (!seenCommits.add((UUID) row[2])) continue; // newest completed review per commit
			TeamStats team = statsByProject.get((UUID) row[0]);
			if (team == null) continue;
			boolean warned = isWarning((String) row[3]);
			team.reviewedCommits++;
			if (warned) team.warnedCommits++;
			Member author = row[1] == null ? null : memberById.get((UUID) row[1]);
			if (author != null) {
				author.reviewedCommits++;
				if (warned) author.warnedCommits++;
			}
		}

		List<Map<String, Object>> teamFacts = new ArrayList<>();
		List<Map<String, Object>> attention = new ArrayList<>();
		List<Member> everyone = new ArrayList<>();
		Map<String, Long> totals = new TreeMap<>();
		for (Team source : withProject) {
			TeamStats team = statsByProject.get(source.getProject().getId());
			List<Member> roster = membersByTeam.getOrDefault(source.getId(), List.of()).stream()
					.sorted(Comparator.comparing((Member m) -> !"LEADER".equals(m.role)).thenComparing(m -> m.name == null ? "" : m.name))
					.toList();
			everyone.addAll(roster);
			for (Member member : roster) {
				List<String> reasons = member.attentionReasons();
				if (!reasons.isEmpty()) {
					Map<String, Object> flag = new LinkedHashMap<>();
					flag.put("team", team.name);
					flag.put("member", member.name);
					flag.put("studentCode", member.studentCode);
					flag.put("reasons", reasons);
					attention.add(flag);
				}
			}
			teamFacts.add(team.toFacts(roster));
			totals.merge("memberCount", (long) roster.size(), Long::sum);
			totals.merge("tasksTotal", team.tasksTotal, Long::sum);
			totals.merge("tasksDone", team.tasksDone, Long::sum);
			totals.merge("overdueTasks", (long) team.overdue.size(), Long::sum);
			totals.merge("commits", team.commits, Long::sum);
			totals.merge("unlinkedCommits", team.unlinkedCommits, Long::sum);
			totals.merge("reviewedCommits", team.reviewedCommits, Long::sum);
			totals.merge("aiWarnedCommits", team.warnedCommits, Long::sum);
		}
		overdueAll.sort(Comparator.comparingLong((Map<String, Object> o) -> ((Number) o.get("overdueDays")).longValue()).reversed());
		facts.put("teams", teamFacts);
		facts.put("totals", totals);
		facts.put("overdueTasks", overdueAll.stream().limit(OVERDUE_LIST_CAP).toList());
		facts.put("overdueTaskListTruncated", overdueAll.size() > OVERDUE_LIST_CAP);
		facts.put("attention", attention.stream().limit(ATTENTION_CAP).toList());
		facts.put("mostActive", everyone.stream()
				.filter(m -> m.tasksDone > 0 || m.commits > 0)
				.sorted(Comparator.comparingLong((Member m) -> m.storyPointsDone).thenComparingLong(m -> m.tasksDone).thenComparingLong(m -> m.commits).reversed())
				.limit(MOST_ACTIVE_CAP)
				.map(m -> {
					Map<String, Object> row = new LinkedHashMap<>();
					row.put("team", teamOf(m, membersByTeam, statsByProject, withProject));
					row.put("member", m.name);
					row.put("studentCode", m.studentCode);
					row.put("tasksDone", m.tasksDone);
					row.put("storyPointsDone", m.storyPointsDone);
					row.put("commits", m.commits);
					return row;
				})
				.toList());
	}

	/** Same rule as the commit badge, without the "no task" reason (counted separately as unlinked). */
	private boolean isWarning(String structuredResultJson) {
		if (structuredResultJson == null || structuredResultJson.isBlank()) return false;
		try {
			AiStructuredResult result = mapper.readValue(structuredResultJson, AiStructuredResult.class);
			return CommitAiReviewDtos.WARNING.equals(
					CommitAiReviewRules.evaluate(false, AiAnalysisStatus.COMPLETED, result, true, true).status());
		} catch (Exception ex) {
			return false;
		}
	}

	private static String teamOf(Member member, Map<UUID, List<Member>> membersByTeam, Map<UUID, TeamStats> statsByProject, List<Team> teams) {
		for (Team team : teams) {
			if (membersByTeam.getOrDefault(team.getId(), List.of()).contains(member)) return statsByProject.get(team.getProject().getId()).name;
		}
		return null;
	}

	private static String join(Object code, Object name) {
		String left = code == null ? "" : code.toString();
		String right = name == null ? "" : name.toString();
		return left.isBlank() ? right : right.isBlank() ? left : left + " - " + right;
	}

	private static String truncate(String text) {
		if (text == null) return null;
		String clean = text.strip();
		return clean.length() <= 120 ? clean : clean.substring(0, 117) + "...";
	}

	private static final class Member {
		final UUID id;
		final String studentCode;
		final String name;
		final String role;
		long tasksTotal, tasksDone, tasksInProgress, storyPointsTotal, storyPointsDone;
		long commits, unlinkedCommits, reviewedCommits, warnedCommits;
		String lastCommitAt;
		final List<Map<String, Object>> overdue = new ArrayList<>();

		Member(UUID id, String studentCode, String name, String role) {
			this.id = id;
			this.studentCode = studentCode;
			this.name = name;
			this.role = role;
		}

		List<String> attentionReasons() {
			List<String> reasons = new ArrayList<>();
			if (!overdue.isEmpty()) {
				reasons.add(overdue.size() + " task quá hạn: " + String.join(", ",
						overdue.stream().map(o -> Objects.toString(o.get("key"), "?")).toList()));
			}
			if (commits == 0) reasons.add("chưa có commit nào");
			if (tasksTotal > 0 && tasksDone == 0) reasons.add("chưa hoàn thành task nào trong " + tasksTotal + " task được giao");
			if (tasksTotal == 0) reasons.add("chưa được giao task nào");
			if (warnedCommits > 0) reasons.add(warnedCommits + " commit bị AI cảnh báo");
			if (unlinkedCommits >= UNLINKED_ATTENTION_THRESHOLD) reasons.add(unlinkedCommits + " commit chưa gắn task");
			return reasons;
		}

		Map<String, Object> toFacts() {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("name", name);
			row.put("studentCode", studentCode);
			row.put("role", role);
			row.put("tasksTotal", tasksTotal);
			row.put("tasksDone", tasksDone);
			row.put("tasksInProgress", tasksInProgress);
			row.put("tasksNotDone", tasksTotal - tasksDone);
			row.put("storyPointsDone", storyPointsDone);
			row.put("storyPointsTotal", storyPointsTotal);
			row.put("overdueTasks", overdue.stream().map(o -> o.get("key") + " (" + o.get("overdueDays") + " ngày)").toList());
			row.put("commits", commits);
			row.put("unlinkedCommits", unlinkedCommits);
			row.put("aiReviewedCommits", reviewedCommits);
			row.put("aiWarnedCommits", warnedCommits);
			row.put("lastCommitAt", lastCommitAt);
			return row;
		}
	}

	private static final class TeamStats {
		final String name;
		final Integer teamNo;
		long tasksTotal, tasksDone, unassignedOpenTasks, commits, unmappedCommits, unlinkedCommits, reviewedCommits, warnedCommits;
		final Map<String, Long> statusCounts = new TreeMap<>();
		final List<Map<String, Object>> overdue = new ArrayList<>();

		TeamStats(Team team) {
			this.teamNo = team.getTeamNo();
			this.name = team.getName() != null && !team.getName().isBlank() ? team.getName()
					: team.getTeamNo() == null ? "Nhóm" : "Nhóm " + team.getTeamNo();
		}

		Map<String, Object> toFacts(List<Member> roster) {
			Map<String, Object> row = new LinkedHashMap<>();
			row.put("teamNo", teamNo);
			row.put("teamName", name);
			row.put("memberCount", roster.size());
			row.put("tasksTotal", tasksTotal);
			row.put("tasksDone", tasksDone);
			row.put("tasksNotDone", tasksTotal - tasksDone);
			row.put("taskStatusCounts", statusCounts);
			row.put("unassignedOpenTasks", unassignedOpenTasks);
			row.put("overdueCount", overdue.size());
			row.put("overdueTasks", overdue.stream()
					.sorted(Comparator.comparingLong((Map<String, Object> o) -> ((Number) o.get("overdueDays")).longValue()).reversed())
					.limit(TEAM_OVERDUE_CAP).toList());
			row.put("commits", commits);
			row.put("commitsByUnmappedAuthors", unmappedCommits);
			row.put("unlinkedCommits", unlinkedCommits);
			row.put("aiReviewedCommits", reviewedCommits);
			row.put("aiWarnedCommits", warnedCommits);
			row.put("members", roster.stream().map(Member::toFacts).toList());
			return row;
		}
	}
}

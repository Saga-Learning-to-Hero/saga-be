package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.ai.AiCodeVerdict;
import com.saga.be.ai.AiCommitMessageVerdict;
import com.saga.be.ai.AiOverallDecision;
import com.saga.be.ai.AiStructuredResult;
import com.saga.be.ai.AiTaskAlignmentVerdict;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.repository.AiAnalysisRunRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The lecturer's report names who has which overdue task, commits, AI warnings, in bulk queries. */
class AiProgressRosterBuilderTest {

	private final TaskRepository tasks = mock(TaskRepository.class);
	private final TeamMemberRepository members = mock(TeamMemberRepository.class);
	private final GitCommitRepository commits = mock(GitCommitRepository.class);
	private final AiAnalysisRunRepository runs = mock(AiAnalysisRunRepository.class);
	private final CourseRepository courses = mock(CourseRepository.class);
	private final ObjectMapper mapper = new ObjectMapper();
	private final AiProgressRosterBuilder builder = new AiProgressRosterBuilder(tasks, members, commits, runs, courses, mapper);

	private final LocalDateTime now = LocalDateTime.of(2026, 10, 5, 10, 0);
	private final UUID projectId = UUID.randomUUID();
	private final UUID teamId = UUID.randomUUID();
	private final UUID leader = UUID.randomUUID();
	private final UUID member = UUID.randomUUID();

	private Team team() {
		Project project = new Project();
		project.setId(projectId);
		Team team = new Team();
		team.setId(teamId);
		team.setTeamNo(2);
		team.setName("Nhóm 2");
		team.setProject(project);
		return team;
	}

	private static List<Object[]> rows(Object[]... rows) {
		return new ArrayList<>(List.of(rows));
	}

	private String review(AiCommitMessageVerdict message) throws Exception {
		return mapper.writeValueAsString(new AiStructuredResult(
				new AiStructuredResult.CommitMessageAssessment(message, 70, "s", null, List.of()),
				new AiStructuredResult.CodeAssessment(AiCodeVerdict.POSITIVE, 0.8, List.of(), List.of()),
				List.of(), AiTaskAlignmentVerdict.ALIGNS, List.of(), AiOverallDecision.INFORMATIONAL, false));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> build() throws Exception {
		when(members.findActiveProgressReportRows(anyList())).thenReturn(rows(
				new Object[] {teamId, member, "SE170002", "Trần Thị B", RoleInTeam.MEMBER},
				new Object[] {teamId, leader, "SE170001", "Nguyễn Văn A", RoleInTeam.LEADER}));
		when(tasks.findProgressReportRows(anyList())).thenReturn(rows(
				new Object[] {projectId, "SAGA-1", "Đăng nhập", TaskStatus.DONE, now.minusDays(5), 3, leader},
				new Object[] {projectId, "SAGA-2", "Trang hồ sơ", TaskStatus.DONE, now.minusDays(2), 5, leader},
				new Object[] {projectId, "SAGA-12", "Xuất báo cáo", TaskStatus.IN_PROGRESS, now.minusDays(3), 2, member},
				new Object[] {projectId, "SAGA-13", "Không ai nhận", TaskStatus.TODO, now.plusDays(4), 1, null}));
		when(commits.countProgressReportCommits(anyList())).thenReturn(rows(
				new Object[] {projectId, leader, 12L, now.minusDays(1)},
				new Object[] {projectId, null, 2L, now.minusDays(1)}));
		when(commits.countProgressReportUnlinkedCommits(anyList())).thenReturn(rows(
				new Object[] {projectId, leader, 4L}));
		UUID commitA = UUID.randomUUID();
		when(runs.findCompletedCommitReviewsForReport(anyList())).thenReturn(rows(
				new Object[] {projectId, leader, commitA, review(AiCommitMessageVerdict.POOR)},
				new Object[] {projectId, leader, commitA, review(AiCommitMessageVerdict.CLEAR)}, // older: ignored
				new Object[] {projectId, leader, UUID.randomUUID(), review(AiCommitMessageVerdict.CLEAR)}));
		Map<String, Object> facts = new TreeMap<>();
		builder.addRoster(facts, List.of(team()), now);
		return facts;
	}

	@Test
	@SuppressWarnings("unchecked")
	void eachMemberGetsTasksCommitsWarningsAndTheirOverdueTasksByKey() throws Exception {
		Map<String, Object> facts = build();

		Map<String, Object> team = ((List<Map<String, Object>>) facts.get("teams")).getFirst();
		assertThat(team).containsEntry("teamName", "Nhóm 2").containsEntry("tasksTotal", 4L).containsEntry("tasksDone", 2L)
				.containsEntry("overdueCount", 1).containsEntry("unassignedOpenTasks", 1L).containsEntry("commits", 14L)
				.containsEntry("commitsByUnmappedAuthors", 2L).containsEntry("aiReviewedCommits", 2L).containsEntry("aiWarnedCommits", 1L);
		List<Map<String, Object>> roster = (List<Map<String, Object>>) team.get("members");
		assertThat(roster).extracting(m -> m.get("name")).containsExactly("Nguyễn Văn A", "Trần Thị B"); // leader first
		assertThat(roster.get(0)).containsEntry("tasksDone", 2L).containsEntry("storyPointsDone", 8L).containsEntry("commits", 12L)
				.containsEntry("unlinkedCommits", 4L).containsEntry("aiWarnedCommits", 1L);
		assertThat(roster.get(1)).containsEntry("tasksDone", 0L).containsEntry("tasksInProgress", 1L).containsEntry("commits", 0L);
		assertThat((List<String>) roster.get(1).get("overdueTasks")).containsExactly("SAGA-12 (3 ngày)");

		Map<String, Object> overdue = ((List<Map<String, Object>>) facts.get("overdueTasks")).getFirst();
		assertThat(overdue).containsEntry("team", "Nhóm 2").containsEntry("key", "SAGA-12").containsEntry("assignee", "Trần Thị B").containsEntry("overdueDays", 3L);
	}

	@Test
	@SuppressWarnings("unchecked")
	void attentionNamesTheMemberWithTheReasons_andMostActiveIsByFinishedWorkThenCommits() throws Exception {
		Map<String, Object> facts = build();

		List<Map<String, Object>> attention = (List<Map<String, Object>>) facts.get("attention");
		Map<String, Object> b = attention.stream().filter(a -> "Trần Thị B".equals(a.get("member"))).findFirst().orElseThrow();
		assertThat((List<String>) b.get("reasons")).contains("1 task quá hạn: SAGA-12", "chưa có commit nào", "chưa hoàn thành task nào trong 1 task được giao");
		Map<String, Object> a = attention.stream().filter(x -> "Nguyễn Văn A".equals(x.get("member"))).findFirst().orElseThrow();
		assertThat((List<String>) a.get("reasons")).containsExactly("1 commit bị AI cảnh báo", "4 commit chưa gắn task");

		List<Map<String, Object>> active = (List<Map<String, Object>>) facts.get("mostActive");
		assertThat(active).hasSize(1);
		assertThat(active.getFirst()).containsEntry("member", "Nguyễn Văn A").containsEntry("team", "Nhóm 2").containsEntry("storyPointsDone", 8L);
		assertThat((Map<String, Object>) facts.get("totals")).containsEntry("memberCount", 2L).containsEntry("overdueTasks", 1L);
	}

	@Test
	void theWholeCourseTakesOneQueryPerKindNotOnePerTeam() throws Exception {
		build();

		verify(tasks, times(1)).findProgressReportRows(anyList());
		verify(members, times(1)).findActiveProgressReportRows(anyList());
		verify(commits, times(1)).countProgressReportCommits(anyList());
		verify(commits, times(1)).countProgressReportUnlinkedCommits(anyList());
		verify(runs, times(1)).findCompletedCommitReviewsForReport(anyList());
	}

	@Test
	void theCourseHeaderComesFromOneQuery() {
		UUID courseId = UUID.randomUUID();
		when(courses.findReportHeader(courseId)).thenReturn(rows(new Object[] {"SE1802", "SWR302-FA26-SE1802", "SWR302", "Software Requirement", "Fall 2026", "Nguyễn Ngân"}));

		assertThat(builder.courseHeader(courseId)).containsEntry("courseCode", "SE1802").containsEntry("subject", "SWR302 - Software Requirement")
				.containsEntry("semester", "Fall 2026").containsEntry("lecturer", "Nguyễn Ngân");
	}
}

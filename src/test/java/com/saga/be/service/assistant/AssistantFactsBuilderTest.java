package com.saga.be.service.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.dto.delay.DelayCaseDtos.MemberOnTimeRate;
import com.saga.be.dto.delay.DelayCaseDtos.OnTimeRateResponse;
import com.saga.be.dto.delay.DelayCaseDtos.StudentRef;
import com.saga.be.dto.delay.DelayCaseDtos.TaskRef;
import com.saga.be.dto.project.ProjectCommitPageResponse;
import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.dto.project.ProjectTaskResponse;
import com.saga.be.dto.project.ProjectTaskSprintResponse;
import com.saga.be.dto.integration.failover.TaskMigrationSummary;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.assistant.AssistantFacts.Fact;
import com.saga.be.service.delay.TaskDelayCaseService;
import com.saga.be.service.projection.ProjectProjectionReadService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssistantFactsBuilderTest {

	/** 03/10/2026 00:30 in Vietnam, still 02/10 in UTC: "today" must be the Vietnamese day. */
	private static final Instant NOW = Instant.parse("2026-10-02T17:30:00Z");
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 3);

	@Mock private ProjectRepository projects;
	@Mock private UserAccountRepository users;
	@Mock private StudentProfileRepository students;
	@Mock private TeamByProjectRepository teams;
	@Mock private TeamMemberRepository members;
	@Mock private SprintRepository sprints;
	@Mock private GitCommitRepository commits;
	@Mock private ProjectProjectionReadService projections;
	@Mock private TaskDelayCaseService delays;

	private final UUID projectId = UUID.randomUUID();
	private final UUID userId = UUID.randomUUID();
	private final UUID viewerMemberId = UUID.randomUUID();
	private final UUID minhId = UUID.randomUUID();
	private final UUID sprintId = UUID.randomUUID();
	private AssistantFactsBuilder builder;
	private List<ProjectTaskResponse> tasks;

	@BeforeEach
	void setUp() {
		builder = new AssistantFactsBuilder(projects, users, students, teams, members, sprints, commits, projections, delays,
				Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh"));
		Project project = new Project();
		project.setId(projectId);
		project.setName("Smart Library");
		when(projects.findById(projectId)).thenReturn(Optional.of(project));
		UserAccount viewer = new UserAccount();
		viewer.setId(userId);
		viewer.setFullName("Nguyễn Văn An");
		viewer.setAccountRole(AccountRole.STUDENT);
		when(users.findById(userId)).thenReturn(Optional.of(viewer));
		StudentProfile profile = new StudentProfile();
		profile.setId(viewerMemberId);
		when(students.findByUserAccount_Id(userId)).thenReturn(Optional.of(profile));
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.LEADER));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(delays.onTimeRate(userId, projectId)).thenReturn(new OnTimeRateResponse(projectId, TODAY, List.of(
				new MemberOnTimeRate(viewerMemberId, "Nguyễn Văn An", "SE170001", 4, 3, 1, 0, new BigDecimal("75.0")),
				new MemberOnTimeRate(minhId, "Trần Đức Minh", "SE170002", 2, 2, 0, 0, new BigDecimal("100.0")))));
		when(delays.list(userId, projectId, null, null)).thenReturn(List.of());
		Sprint active = new Sprint();
		active.setId(sprintId);
		active.setName("Sprint 4");
		active.setState("active");
		active.setStartDate(LocalDateTime.of(2026, 9, 28, 0, 0));
		active.setEndDate(LocalDateTime.of(2026, 10, 11, 0, 0));
		Sprint closed = new Sprint();
		closed.setId(UUID.randomUUID());
		closed.setName("Sprint 3");
		closed.setState("closed");
		when(sprints.findActiveByProject_Id(projectId)).thenReturn(List.of(active, closed));
		when(commits.countAndMaxCommittedAtByProjectAndAuthor(eq(projectId), any()))
				.thenReturn(List.<Object[]>of(new Object[] {7L, LocalDateTime.of(2026, 10, 2, 9, 0)}));
		when(projections.listCommits(eq(userId), eq(projectId), eq(0), eq(10))).thenReturn(page(commit("recent")));
		when(projections.listCommits(eq(userId), eq(projectId), eq(0), eq(5), any())).thenReturn(page(commit("by-member")));
		when(projections.listTaskCommits(eq(userId), eq(projectId), any(), eq(0), eq(5), eq(true))).thenReturn(page(commit("of-task")));
		tasks = new ArrayList<>();
		when(projections.listTasks(userId, projectId)).thenAnswer(inv -> tasks);
	}

	@Test
	void theProjectSprintAndMembersAreFactsAndTodayIsTheVietnameseDay() {
		tasks.add(task("SAGA-1", "IN_PROGRESS", LocalDate.of(2026, 10, 2), viewerMemberId, sprintId, 3));
		tasks.add(task("SAGA-2", "DONE", LocalDate.of(2026, 9, 30), minhId, sprintId, 5));
		tasks.add(task("SAGA-3", "TODO", LocalDate.of(2026, 10, 6), null, null, null));

		AssistantFacts facts = builder.build(userId, projectId, "Tiến độ sprint thế nào?");

		assertThat(facts.today()).isEqualTo(TODAY);
		Fact project = facts.find("PROJECT", projectId).orElseThrow();
		assertThat(project.payload())
				.containsEntry("taskCount", 3)
				.containsEntry("overdueTaskCount", 1L)
				.containsEntry("dueSoonTaskCount", 1L)
				.containsEntry("unassignedOpenTaskCount", 1L)
				.containsEntry("activeMemberCount", 2);
		Fact sprint = facts.find("SPRINT", sprintId).orElseThrow();
		assertThat(sprint.payload()).containsEntry("taskCount", 2).containsEntry("storyPointsDone", 5).containsEntry("storyPointsTotal", 8);
		// only the active sprint
		assertThat(facts.items()).filteredOn(fact -> fact.kind().equals("SPRINT")).hasSize(1);
		Fact viewer = facts.find("MEMBER", viewerMemberId).orElseThrow();
		assertThat(viewer.payload()).containsEntry("isViewer", true).containsEntry("overdueTaskCount", 1L).containsEntry("authoredCommitCount", 7L);
		assertThat(facts.viewer().role()).isEqualTo("STUDENT");
		assertThat(facts.viewer().teamRole()).isEqualTo("LEADER");
		assertThat(facts.viewer().memberId()).isEqualTo(viewerMemberId);
		Fact overdue = facts.items().stream().filter(fact -> "SAGA-1 · Task SAGA-1".equals(fact.label())).findFirst().orElseThrow();
		assertThat(overdue.payload()).containsEntry("overdue", true).containsEntry("dueSoon", false);
		assertThat(facts.hints().overdueTaskIds()).containsExactly(overdue.id());
	}

	@Test
	void taskKeysAndMemberNamesInTheQuestionAreMatchedWithoutCaseOrDiacritics() {
		ProjectTaskResponse named = task("SAGA-12", "IN_PROGRESS", LocalDate.of(2026, 10, 20), null, null, null);
		tasks.add(named);
		tasks.add(task("SAGA-13", "TODO", null, null, null, null));

		AssistantFacts facts = builder.build(userId, projectId, "saga-12 tới đâu rồi? Còn minh thì sao?");

		assertThat(facts.hints().matchedTaskIds()).containsExactly(named.id());
		assertThat(facts.hints().matchedMemberIds()).containsExactly(minhId);
		assertThat(facts.items().stream().filter(fact -> fact.kind().equals("COMMIT")).map(Fact::label).toList())
				.containsExactly("of-task · of-task", "by-memb · by-member", "recent1 · recent");
		verify(projections).listTaskCommits(userId, projectId, named.id(), 0, 5, true);
		verify(projections).listCommits(userId, projectId, 0, 5, minhId);
	}

	@Test
	void membersMatchByFullNameOrStudentCodeButNotByAPieceOfAWord() {
		List<MemberOnTimeRate> roster = List.of(
				new MemberOnTimeRate(minhId, "Trần Đức Minh", "SE170002", 0, 0, 0, 0, null),
				new MemberOnTimeRate(viewerMemberId, "Lê Thị Ánh", "SE170003", 0, 0, 0, 0, null));

		assertThat(AssistantFactsBuilder.matchMembers(AssistantFactsBuilder.normalize("TRẦN ĐỨC MINH làm gì?"), roster))
				.extracting(MemberOnTimeRate::studentProfileId).containsExactly(minhId);
		assertThat(AssistantFactsBuilder.matchMembers(AssistantFactsBuilder.normalize("bạn se170003 sao rồi"), roster))
				.extracting(MemberOnTimeRate::studentProfileId).containsExactly(viewerMemberId);
		assertThat(AssistantFactsBuilder.matchMembers(AssistantFactsBuilder.normalize("ánh dạo này ổn không"), roster))
				.extracting(MemberOnTimeRate::studentProfileId).containsExactly(viewerMemberId);
		// a repeated word must not break matching
		assertThat(AssistantFactsBuilder.matchMembers(AssistantFactsBuilder.normalize("minh minh minh"), roster))
				.extracting(MemberOnTimeRate::studentProfileId).containsExactly(minhId);
		// "minhchung" contains "minh" but is not the word "minh"
		assertThat(AssistantFactsBuilder.matchMembers(AssistantFactsBuilder.normalize("minhchung của task"), roster)).isEmpty();
		assertThat(AssistantFactsBuilder.normalize("  Đặng   Minh Ánh ")).isEqualTo("dang minh anh");
	}

	@Test
	void taskFactsAreBoundedNamedAndOverdueFirstAndMovedTasksAreLeftOut() {
		for (int i = 0; i < 60; i++) {
			tasks.add(task("SAGA-" + (100 + i), "TODO", LocalDate.of(2026, 12, 1), null, null, null));
		}
		ProjectTaskResponse overdue = task("SAGA-9", "BLOCKED", LocalDate.of(2026, 9, 1), null, null, null);
		ProjectTaskResponse named = task("SAGA-159", "TODO", LocalDate.of(2026, 12, 1), null, null, null);
		tasks.add(overdue);
		tasks.removeIf(task -> task.externalKey().equals("SAGA-159"));
		tasks.add(named);
		ProjectTaskResponse moved = withMigration(task("SAGA-8", "TODO", LocalDate.of(2026, 9, 1), null, null, null),
				new TaskMigrationSummary(null, null, true));
		tasks.add(moved);

		AssistantFacts facts = builder.build(userId, projectId, "SAGA-159 và SAGA-8?");

		List<Fact> taskFacts = facts.items().stream().filter(fact -> fact.kind().equals("TASK")).toList();
		assertThat(taskFacts).hasSize(AssistantFactsBuilder.MAX_TASKS);
		assertThat(taskFacts.get(0).id()).isEqualTo(named.id());
		assertThat(taskFacts.get(1).id()).isEqualTo(overdue.id());
		assertThat(taskFacts).extracting(Fact::id).doesNotContain(moved.id());
		assertThat(facts.hints().matchedTaskIds()).containsExactly(named.id());
		assertThat(facts.items()).hasSizeLessThanOrEqualTo(100);
	}

	@Test
	void delayCasesComeFromTheServiceAsTheViewerMaySeeThemOpenOnesFirst() {
		ProjectTaskResponse named = task("SAGA-5", "IN_PROGRESS", LocalDate.of(2026, 9, 29), null, null, null);
		tasks.add(named);
		DelayCaseResponse closed = delay(UUID.randomUUID(), "SAGA-4", "CLOSED_SUBJECTIVE", null);
		DelayCaseResponse open = delay(UUID.randomUUID(), "SAGA-6", "AWAITING_LEADER", null);
		DelayCaseResponse ofNamed = delay(named.id(), "SAGA-5", "CLOSED_OBJECTIVE", "Nhập viện");
		when(delays.list(userId, projectId, null, null)).thenReturn(List.of(closed, open, ofNamed));

		AssistantFacts facts = builder.build(userId, projectId, "SAGA-5 vì sao trễ?");

		List<Fact> delayFacts = facts.items().stream().filter(fact -> fact.kind().equals("DELAY_CASE")).toList();
		assertThat(delayFacts).extracting(fact -> fact.payload().get("taskKey")).containsExactly("SAGA-5", "SAGA-6", "SAGA-4");
		assertThat(delayFacts.getFirst().payload()).containsEntry("explanationNote", "Nhập viện");
		assertThat(delayFacts.get(1).payload()).containsEntry("explanationNote", null);
		assertThat(delayFacts.getFirst().taskId()).isEqualTo(named.id());
		assertThat(facts.find("PROJECT", projectId).orElseThrow().payload()).containsEntry("openDelayCaseCount", 1L);
	}

	@Test
	void aLecturerViewerHasNoTeamRoleAndNoMemberId() {
		UserAccount lecturer = new UserAccount();
		lecturer.setId(userId);
		lecturer.setFullName("Cô Hương");
		lecturer.setAccountRole(AccountRole.LECTURER);
		when(users.findById(userId)).thenReturn(Optional.of(lecturer));

		AssistantFacts facts = builder.build(userId, projectId, "Nhóm này có rủi ro gì?");

		assertThat(facts.viewer().role()).isEqualTo("LECTURER");
		assertThat(facts.viewer().teamRole()).isNull();
		assertThat(facts.viewer().memberId()).isNull();
		verify(students, never()).findByUserAccount_Id(any());
	}

	@Test
	void untrustedTextIsTrimmedAndBounded() {
		assertThat(AssistantFactsBuilder.text("  " + "x".repeat(500))).hasSize(200).endsWith("…");
		assertThat(AssistantFactsBuilder.text("   ")).isNull();
		assertThat(AssistantFactsBuilder.taskLabel("SAGA-1", null)).isEqualTo("SAGA-1");
		assertThat(AssistantFactsBuilder.taskLabel(null, "Login")).isEqualTo("Login");
	}

	// ------------------------------------------------------------------ fixtures

	private static ProjectTaskResponse task(String key, String status, LocalDate due, UUID assignee, UUID sprintId, Integer points) {
		return new ProjectTaskResponse(
				UUID.randomUUID(), null, key, "Task " + key, null, status, null, null, null, null, "STANDARD", 0,
				null, assignee == null ? null : "Member", assignee, null, null, null, points,
				sprintId == null ? null : new ProjectTaskSprintResponse(sprintId, "1", "Sprint 4", "active"),
				null, List.of(), due, null, 0L, 0L, false, null, null, LocalDateTime.of(2026, 10, 1, 0, 0),
				null, null, TaskMigrationSummary.none(), null, null, null);
	}

	private static ProjectTaskResponse withMigration(ProjectTaskResponse t, TaskMigrationSummary migration) {
		return new ProjectTaskResponse(
				t.id(), t.externalId(), t.externalKey(), t.title(), t.description(), t.status(), t.jiraStatusId(), t.jiraStatusName(),
				t.issueTypeName(), t.issueTypeId(), t.issueTypeLevel(), t.jiraHierarchyLevel(), t.assigneeExternalId(),
				t.assigneeDisplayName(), t.assigneeStudentId(), t.assignee(), t.priority(), t.priorityDetail(), t.storyPoint(),
				t.sprint(), t.parent(), t.labels(), t.dueDate(), t.startDate(), t.linkedCommitCount(), t.evidenceCount(),
				t.hasEvidence(), t.externalUpdatedAt(), t.createdAt(), t.updatedAt(), t.parentTask(), t.source(), migration,
				t.subtasks(), t.evidenceCheck(), t.scheduleCheck());
	}

	private static ProjectCommitPageResponse page(ProjectCommitResponse commit) {
		return new ProjectCommitPageResponse(List.of(commit), 0, 10, 1);
	}

	private static ProjectCommitResponse commit(String message) {
		return new ProjectCommitResponse(UUID.randomUUID(), UUID.randomUUID(), "org/repo", message + "123456789", message,
				null, null, null, null, LocalDateTime.of(2026, 10, 2, 9, 0), null, 1, false);
	}

	private static DelayCaseResponse delay(UUID taskId, String key, String status, String note) {
		return new DelayCaseResponse(UUID.randomUUID(), null, null, new TaskRef(taskId, key, "Task " + key),
				new StudentRef(UUID.randomUUID(), UUID.randomUUID(), "Trần Đức Minh", "SE170002"), LocalDate.of(2026, 9, 29),
				null, null, status, "OTHER", "OTHER", note, null, null, null, null, "UNVERIFIABLE", "Cần người kiểm tra",
				null, null, null, null, null, null, null, null, null);
	}
}

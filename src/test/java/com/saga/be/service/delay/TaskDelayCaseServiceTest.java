package com.saga.be.service.delay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.delay.DelayCaseDtos.DelayCaseResponse;
import com.saga.be.dto.delay.DelayCaseDtos.ExplainRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LecturerReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.LeaderReviewRequest;
import com.saga.be.dto.delay.DelayCaseDtos.MemberOnTimeRate;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.delay.TaskDelayCase;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.DelayCaseEnums.CloseReason;
import com.saga.be.entity.enums.DelayCaseEnums.LeaderDecision;
import com.saga.be.entity.enums.DelayCaseEnums.Outcome;
import com.saga.be.entity.enums.DelayCaseEnums.Verification;
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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TaskDelayCaseServiceTest {

	/** 06/10/2026 10:00 in Vietnam. */
	private static final Instant NOW = Instant.parse("2026-10-06T03:00:00Z");
	private static final LocalDateTime NOW_UTC = LocalDateTime.ofInstant(NOW, ZoneOffset.UTC);
	private static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 4, 0, 0);

	@Mock private TaskDelayCaseRepository cases;
	@Mock private TaskRepository tasks;
	@Mock private DelaySignalCollector collector;
	@Mock private ProjectDataAuthorization authorization;
	@Mock private UserAccountRepository users;
	@Mock private TeamMemberRepository members;
	@Mock private TeamByProjectRepository teams;
	@Mock private NotificationService notifications;

	private TaskDelayCaseService service;
	private Project project;
	private UserAccount assignee;
	private UserAccount leader;
	private UserAccount member;
	private UserAccount lecturer;
	private StudentProfile assigneeProfile;
	private StudentProfile leaderProfile;
	private Task task;

	@BeforeEach
	void setUp() {
		service = new TaskDelayCaseService(cases, tasks, collector, authorization, users, members, teams, notifications,
				Clock.fixed(NOW, ZoneOffset.UTC), ZoneId.of("Asia/Ho_Chi_Minh"), Duration.ofDays(3));
		assignee = account(AccountRole.STUDENT, "Nguyen Van A");
		leader = account(AccountRole.STUDENT, "Tran Leader");
		member = account(AccountRole.STUDENT, "Le Member");
		lecturer = account(AccountRole.LECTURER, "Co Huong");
		LecturerProfile lecturerProfile = new LecturerProfile();
		lecturerProfile.setUserAccount(lecturer);
		Course course = new Course();
		course.setInstructor(lecturerProfile);
		project = new Project();
		project.setId(UUID.randomUUID());
		project.setCourse(course);
		assigneeProfile = profile(assignee, "SE001");
		leaderProfile = profile(leader, "SE002");
		task = task("SAGA-1", TaskStatus.IN_PROGRESS, DUE, null, assigneeProfile);
		when(members.findActiveRoleByProjectIdAndUserId(project.getId(), assignee.getId())).thenReturn(Optional.of(RoleInTeam.MEMBER));
		when(members.findActiveRoleByProjectIdAndUserId(project.getId(), member.getId())).thenReturn(Optional.of(RoleInTeam.MEMBER));
		when(members.findActiveRoleByProjectIdAndUserId(project.getId(), leader.getId())).thenReturn(Optional.of(RoleInTeam.LEADER));
		Team team = new Team();
		team.setId(UUID.randomUUID());
		when(teams.findByProject_Id(project.getId())).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId()))
				.thenReturn(List.of(teamMember(assigneeProfile, RoleInTeam.MEMBER), teamMember(leaderProfile, RoleInTeam.LEADER)));
		when(cases.save(any())).thenAnswer(inv -> {
			TaskDelayCase saved = inv.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
		when(collector.collect(any(), any())).thenReturn(signals());
	}

	// ------------------------------------------------------------------ opening

	@Test
	void aTaskStillOpenAfterItsDueDayGetsACaseAndTheAssigneeIsAskedToExplain() {
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));

		assertThat(service.openIfAbsent(task.getId())).isTrue();

		ArgumentCaptor<TaskDelayCase> captor = ArgumentCaptor.forClass(TaskDelayCase.class);
		verify(cases).save(captor.capture());
		TaskDelayCase opened = captor.getValue();
		assertThat(opened.getStatus()).isEqualTo(DelayCaseStatus.OPEN);
		assertThat(opened.getDueDate()).isEqualTo(DUE);
		assertThat(opened.getStudentProfile()).isSameAs(assigneeProfile);
		assertThat(opened.getExplanationDueAt()).isEqualTo(NOW_UTC.plusDays(3));
		assertThat(opened.getSignalsJson()).contains("\"commitCount\":2");
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(eq(assignee.getId()), eq(NotificationType.TASK),
				eq("Cần giải trình lý do trễ hạn"), message.capture(), any(), anyString());
		assertThat(message.getValue()).contains("SAGA-1").contains("04/10/2026").contains("chủ quan");
	}

	@Test
	void noCaseForATaskNotLateYetUnassignedOrAlreadyOpened() {
		Task dueToday = task("SAGA-2", TaskStatus.IN_PROGRESS, LocalDateTime.of(2026, 10, 6, 0, 0), null, assigneeProfile);
		Task unassigned = task("SAGA-3", TaskStatus.TODO, DUE, null, null);
		Task doneOnTime = task("SAGA-4", TaskStatus.DONE, DUE, DUE.plusHours(20), assigneeProfile);
		when(tasks.findById(dueToday.getId())).thenReturn(Optional.of(dueToday));
		when(tasks.findById(unassigned.getId())).thenReturn(Optional.of(unassigned));
		when(tasks.findById(doneOnTime.getId())).thenReturn(Optional.of(doneOnTime));
		when(tasks.findById(task.getId())).thenReturn(Optional.of(task));
		when(cases.existsByTask_IdAndDueDate(task.getId(), DUE)).thenReturn(true);

		assertThat(service.openIfAbsent(dueToday.getId())).isFalse();
		assertThat(service.openIfAbsent(unassigned.getId())).isFalse();
		assertThat(service.openIfAbsent(doneOnTime.getId())).isFalse();
		assertThat(service.openIfAbsent(task.getId())).isFalse();
		verify(cases, never()).save(any());
	}

	@Test
	void lateMeansDoneOnALaterDayOrStillOpenAfterTheDueDay() {
		LocalDate today = LocalDate.of(2026, 10, 6);
		assertThat(TaskDelayCaseService.isLate(task("A", TaskStatus.DONE, DUE, DUE.plusDays(1).plusHours(1), null), today)).isTrue();
		assertThat(TaskDelayCaseService.isLate(task("B", TaskStatus.DONE, DUE, DUE.plusHours(23), null), today)).isFalse();
		assertThat(TaskDelayCaseService.isLate(task("C", TaskStatus.DONE, DUE, null, null), today)).isFalse();
		assertThat(TaskDelayCaseService.isLate(task("D", TaskStatus.TODO, LocalDateTime.of(2026, 10, 6, 0, 0), null, null), today)).isFalse();
		assertThat(TaskDelayCaseService.isLate(task("E", TaskStatus.TODO, LocalDateTime.of(2026, 10, 5, 0, 0), null, null), today)).isTrue();
	}

	// ------------------------------------------------------------------ explanation

	@Test
	void onlyTheAssigneeCanExplainAndOnlyWhileTheCaseIsOpenAndInTime() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);

		expectCode(() -> service.explain(member.getId(), project.getId(), delay.getId(), explain(DelayCauseCategory.STARTED_LATE)),
				IntegrationErrorCode.DELAY_CASE_FORBIDDEN);
		delay.setExplanationDueAt(NOW_UTC.minusMinutes(1));
		expectCode(() -> service.explain(assignee.getId(), project.getId(), delay.getId(), explain(DelayCauseCategory.STARTED_LATE)),
				IntegrationErrorCode.DELAY_CASE_STATE_CONFLICT);
		delay.setExplanationDueAt(NOW_UTC.plusDays(1));
		delay.setStatus(DelayCaseStatus.AWAITING_LEADER);
		expectCode(() -> service.explain(assignee.getId(), project.getId(), delay.getId(), explain(DelayCauseCategory.STARTED_LATE)),
				IntegrationErrorCode.DELAY_CASE_STATE_CONFLICT);
	}

	@Test
	void explanationInputIsValidated() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);
		UUID caseId = delay.getId();

		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(DelayCauseCategory.OTHER, "  ", null, null)),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(DelayCauseCategory.BLOCKED_BY_TASK, null, null, null)),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(DelayCauseCategory.BLOCKED_BY_TASK, null, task.getId(), null)),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		UUID foreign = UUID.randomUUID();
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(foreign, project.getId())).thenReturn(Optional.empty());
		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(DelayCauseCategory.BLOCKED_BY_TASK, null, foreign, null)),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(DelayCauseCategory.STARTED_LATE, null, null, "javascript:alert(1)")),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		expectCode(() -> service.explain(assignee.getId(), project.getId(), caseId, new ExplainRequest(null, null, null, null)),
				IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		verify(cases, never()).save(any());
	}

	@Test
	void aMembersExplanationGoesToTheLeaderWhoIsNotified() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);

		DelayCaseResponse response = service.explain(assignee.getId(), project.getId(), delay.getId(),
				new ExplainRequest(DelayCauseCategory.STARTED_LATE, "  Bắt đầu muộn  ", null, "https://drive.example/proof"));

		assertThat(response.status()).isEqualTo("AWAITING_LEADER");
		assertThat(response.category()).isEqualTo("STARTED_LATE");
		assertThat(response.categoryGroup()).isEqualTo("SUBJECTIVE");
		assertThat(response.explanationNote()).isEqualTo("Bắt đầu muộn");
		assertThat(response.verification()).isEqualTo("CONSISTENT");
		assertThat(delay.getExplainedByUserId()).isEqualTo(assignee.getId());
		verify(notifications).createNotification(eq(leader.getId()), eq(NotificationType.TASK),
				eq("Hồ sơ trễ hạn chờ bạn xác nhận"), anyString(), any(), anyString());
	}

	@Test
	void theLeadersOwnExplanationGoesStraightToTheLecturer() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);
		delay.setStudentProfile(leaderProfile);

		DelayCaseResponse response = service.explain(leader.getId(), project.getId(), delay.getId(), explain(DelayCauseCategory.UNDERESTIMATED));

		assertThat(response.status()).isEqualTo("AWAITING_LECTURER");
		verify(notifications).createNotification(eq(lecturer.getId()), eq(NotificationType.TASK),
				eq("Hồ sơ trễ hạn chờ duyệt"), anyString(), any(), anyString());
	}

	@Test
	void aBlockerFinishedBeforeTheDueDayIsFlaggedAsAMismatch() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);
		Task blocker = task("SAGA-12", TaskStatus.DONE, DUE.minusDays(5), DUE.minusDays(2), leaderProfile);
		when(tasks.findByIdAndProject_IdAndDeletedAtIsNull(blocker.getId(), project.getId())).thenReturn(Optional.of(blocker));

		DelayCaseResponse response = service.explain(assignee.getId(), project.getId(), delay.getId(),
				new ExplainRequest(DelayCauseCategory.BLOCKED_BY_TASK, null, blocker.getId(), null));

		assertThat(response.verification()).isEqualTo("MISMATCH");
		assertThat(response.verificationNote()).contains("SAGA-12");
		assertThat(response.blockingTask().externalKey()).isEqualTo("SAGA-12");
	}

	// ------------------------------------------------------------------ leader

	@Test
	void theLeaderAgreeingWithAnUncontradictedSubjectiveCauseClosesTheCase() {
		TaskDelayCase delay = explainedCase(DelayCauseCategory.STARTED_LATE, Verification.CONSISTENT);

		DelayCaseResponse response = service.leaderReview(leader.getId(), project.getId(), delay.getId(),
				new LeaderReviewRequest(LeaderDecision.AGREE, null));

		assertThat(response.status()).isEqualTo("CLOSED_SUBJECTIVE");
		assertThat(delay.getCloseReason()).isEqualTo(CloseReason.LEADER_CONFIRMED);
		assertThat(delay.getClosedAt()).isEqualTo(NOW_UTC);
		verify(notifications).createNotification(eq(assignee.getId()), eq(NotificationType.TASK),
				eq("Hồ sơ trễ hạn đã có kết quả"), anyString(), any(), anyString());
		verify(notifications, never()).createNotification(eq(lecturer.getId()), any(), any(), any(), any(), any());
	}

	@Test
	void anObjectiveCauseOrADisagreementGoesToTheLecturer() {
		TaskDelayCase objective = explainedCase(DelayCauseCategory.PERSONAL_EMERGENCY, Verification.UNVERIFIABLE);
		assertThat(service.leaderReview(leader.getId(), project.getId(), objective.getId(),
				new LeaderReviewRequest(LeaderDecision.AGREE, null)).status()).isEqualTo("AWAITING_LECTURER");

		TaskDelayCase disputed = explainedCase(DelayCauseCategory.STARTED_LATE, Verification.CONSISTENT);
		expectCode(() -> service.leaderReview(leader.getId(), project.getId(), disputed.getId(),
				new LeaderReviewRequest(LeaderDecision.DISAGREE, " ")), IntegrationErrorCode.DELAY_CASE_INPUT_INVALID);
		DelayCaseResponse response = service.leaderReview(leader.getId(), project.getId(), disputed.getId(),
				new LeaderReviewRequest(LeaderDecision.DISAGREE, "Bạn ấy bị task khác chặn"));
		assertThat(response.status()).isEqualTo("AWAITING_LECTURER");
		assertThat(response.leaderComment()).isEqualTo("Bạn ấy bị task khác chặn");
	}

	@Test
	void onlyTheLeaderCanConfirmAndNeverTheirOwnCase() {
		TaskDelayCase delay = explainedCase(DelayCauseCategory.STARTED_LATE, Verification.CONSISTENT);
		expectCode(() -> service.leaderReview(member.getId(), project.getId(), delay.getId(),
				new LeaderReviewRequest(LeaderDecision.AGREE, null)), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);
		expectCode(() -> service.leaderReview(lecturer.getId(), project.getId(), delay.getId(),
				new LeaderReviewRequest(LeaderDecision.AGREE, null)), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);
		delay.setStudentProfile(leaderProfile);
		expectCode(() -> service.leaderReview(leader.getId(), project.getId(), delay.getId(),
				new LeaderReviewRequest(LeaderDecision.AGREE, null)), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);
	}

	// ------------------------------------------------------------------ lecturer

	@Test
	void theLecturerDecidesAndTheAssigneeIsTold() {
		TaskDelayCase delay = explainedCase(DelayCauseCategory.PERSONAL_EMERGENCY, Verification.UNVERIFIABLE);
		delay.setStatus(DelayCaseStatus.AWAITING_LECTURER);
		expectCode(() -> service.lecturerReview(leader.getId(), project.getId(), delay.getId(),
				new LecturerReviewRequest(Outcome.OBJECTIVE, null)), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);

		DelayCaseResponse response = service.lecturerReview(lecturer.getId(), project.getId(), delay.getId(),
				new LecturerReviewRequest(Outcome.OBJECTIVE, "Có giấy nhập viện"));

		assertThat(response.status()).isEqualTo("CLOSED_OBJECTIVE");
		assertThat(response.lecturerComment()).isEqualTo("Có giấy nhập viện");
		assertThat(delay.getCloseReason()).isEqualTo(CloseReason.LECTURER_DECIDED);
		ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
		verify(notifications).createNotification(eq(assignee.getId()), eq(NotificationType.TASK),
				eq("Hồ sơ trễ hạn đã có kết quả"), message.capture(), any(), anyString());
		assertThat(message.getValue()).contains("khách quan");
		expectCode(() -> service.lecturerReview(lecturer.getId(), project.getId(), delay.getId(),
				new LecturerReviewRequest(Outcome.SUBJECTIVE, null)), IntegrationErrorCode.DELAY_CASE_STATE_CONFLICT);
	}

	@Test
	void reopeningAnExpiredCaseGivesTheAssigneeANewWindowAndOtherwiseReturnsToTheLecturer() {
		TaskDelayCase expired = stubCase(DelayCaseStatus.CLOSED_SUBJECTIVE);
		expired.setCloseReason(CloseReason.EXPLANATION_EXPIRED);
		expired.setClosedAt(NOW_UTC.minusDays(1));
		expectCode(() -> service.reopen(leader.getId(), project.getId(), expired.getId()), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);

		DelayCaseResponse reopened = service.reopen(lecturer.getId(), project.getId(), expired.getId());
		assertThat(reopened.status()).isEqualTo("OPEN");
		assertThat(reopened.explanationDueAt()).isEqualTo(NOW_UTC.plusDays(3));
		assertThat(reopened.closeReason()).isNull();
		expectCode(() -> service.reopen(lecturer.getId(), project.getId(), expired.getId()), IntegrationErrorCode.DELAY_CASE_STATE_CONFLICT);

		TaskDelayCase decided = explainedCase(DelayCauseCategory.OTHER, Verification.UNVERIFIABLE);
		decided.setStatus(DelayCaseStatus.CLOSED_OBJECTIVE);
		decided.setCloseReason(CloseReason.LECTURER_DECIDED);
		decided.setLecturerOutcome(Outcome.OBJECTIVE);
		DelayCaseResponse back = service.reopen(lecturer.getId(), project.getId(), decided.getId());
		assertThat(back.status()).isEqualTo("AWAITING_LECTURER");
		assertThat(back.lecturerOutcome()).isNull();
	}

	@Test
	void anUnexplainedCaseClosesAsSubjectiveWhenTheWindowEnds() {
		TaskDelayCase delay = stubCase(DelayCaseStatus.OPEN);
		when(cases.findByStatusAndExplanationDueAtBefore(DelayCaseStatus.OPEN, NOW_UTC)).thenReturn(List.of(delay));

		assertThat(service.expireOverdueExplanations()).isEqualTo(1);

		assertThat(delay.getStatus()).isEqualTo(DelayCaseStatus.CLOSED_SUBJECTIVE);
		assertThat(delay.getCloseReason()).isEqualTo(CloseReason.EXPLANATION_EXPIRED);
		verify(notifications).createNotification(eq(assignee.getId()), eq(NotificationType.TASK),
				eq("Hồ sơ trễ hạn đã đóng"), anyString(), any(), anyString());
	}

	// ------------------------------------------------------------------ reads

	@Test
	void notesAndCommentsAreHiddenFromOtherMembersAndPermissionsFollowTheViewer() {
		TaskDelayCase delay = explainedCase(DelayCauseCategory.PERSONAL_EMERGENCY, Verification.UNVERIFIABLE);
		delay.setEvidenceUrl("https://drive.example/hospital");
		when(cases.findFetchedByProject(project.getId())).thenReturn(List.of(delay));

		DelayCaseResponse asMember = service.list(member.getId(), project.getId(), null, null).getFirst();
		DelayCaseResponse asLeader = service.list(leader.getId(), project.getId(), null, null).getFirst();
		DelayCaseResponse asAssignee = service.list(assignee.getId(), project.getId(), null, null).getFirst();

		assertThat(asMember.explanationNote()).isNull();
		assertThat(asMember.evidenceUrl()).isNull();
		assertThat(asMember.category()).isEqualTo("PERSONAL_EMERGENCY");
		assertThat(asMember.permissions().canLeaderReview()).isFalse();
		assertThat(asLeader.explanationNote()).isEqualTo("note");
		assertThat(asLeader.evidenceUrl()).isEqualTo("https://drive.example/hospital");
		assertThat(asLeader.permissions().canLeaderReview()).isTrue();
		assertThat(asAssignee.explanationNote()).isEqualTo("note");
		assertThat(asAssignee.permissions().canLeaderReview()).isFalse();
		assertThat(service.list(member.getId(), project.getId(), DelayCaseStatus.OPEN, null)).isEmpty();
		assertThat(service.list(member.getId(), project.getId(), null, UUID.randomUUID())).isEmpty();
	}

	@Test
	void theLecturerQueueIsForLecturersOnly() {
		expectCode(() -> service.lecturerQueue(assignee.getId(), null), IntegrationErrorCode.DELAY_CASE_FORBIDDEN);
		when(cases.findFetchedForLecturer(lecturer.getId(), List.of(DelayCaseStatus.AWAITING_LECTURER))).thenReturn(List.of());

		assertThat(service.lecturerQueue(lecturer.getId(), null)).isEmpty();
		verify(cases).findFetchedForLecturer(lecturer.getId(), List.of(DelayCaseStatus.AWAITING_LECTURER));
	}

	@Test
	void onTimeRateCountsExcusedDelaysAsOnTime() {
		Task onTime = task("A1", TaskStatus.DONE, DUE, DUE.plusHours(10), assigneeProfile);
		Task excused = task("A2", TaskStatus.DONE, DUE, DUE.plusDays(1).plusHours(2), assigneeProfile);
		Task late = task("A3", TaskStatus.IN_PROGRESS, DUE, null, assigneeProfile);
		Task notDueYet = task("A4", TaskStatus.TODO, LocalDateTime.of(2026, 10, 9, 0, 0), null, assigneeProfile);
		Task noDue = task("A5", TaskStatus.TODO, null, null, assigneeProfile);
		when(tasks.findActiveFetchedByProject_Id(project.getId())).thenReturn(List.of(onTime, excused, late, notDueYet, noDue));
		when(cases.findObjectiveClosedTaskDues(project.getId()))
				.thenReturn(List.<Object[]>of(new Object[] {excused.getId(), DUE}));

		List<MemberOnTimeRate> rows = service.onTimeRate(member.getId(), project.getId()).members();

		MemberOnTimeRate a = rows.stream().filter(r -> r.studentProfileId().equals(assigneeProfile.getId())).findFirst().orElseThrow();
		assertThat(a.evaluatedTasks()).isEqualTo(3);
		assertThat(a.onTimeTasks()).isEqualTo(1);
		assertThat(a.lateTasks()).isEqualTo(1);
		assertThat(a.excusedLateTasks()).isEqualTo(1);
		assertThat(a.onTimeRate()).isEqualByComparingTo(new BigDecimal("66.7"));
		MemberOnTimeRate b = rows.stream().filter(r -> r.studentProfileId().equals(leaderProfile.getId())).findFirst().orElseThrow();
		assertThat(b.evaluatedTasks()).isZero();
		assertThat(b.onTimeRate()).isNull();
	}

	// ------------------------------------------------------------------ fixtures

	private TaskDelayCase stubCase(DelayCaseStatus status) {
		TaskDelayCase delay = new TaskDelayCase();
		delay.setId(UUID.randomUUID());
		delay.setProject(project);
		delay.setTask(task);
		delay.setStudentProfile(assigneeProfile);
		delay.setDueDate(DUE);
		delay.setOpenedAt(NOW_UTC.minusDays(1));
		delay.setExplanationDueAt(NOW_UTC.plusDays(2));
		delay.setStatus(status);
		when(cases.findFetchedByIdAndProject(delay.getId(), project.getId())).thenReturn(Optional.of(delay));
		return delay;
	}

	private TaskDelayCase explainedCase(DelayCauseCategory category, Verification verification) {
		TaskDelayCase delay = stubCase(DelayCaseStatus.AWAITING_LEADER);
		delay.setCategory(category);
		delay.setVerification(verification);
		delay.setExplanationNote("note");
		delay.setExplainedAt(NOW_UTC.minusHours(2));
		return delay;
	}

	private static ExplainRequest explain(DelayCauseCategory category) {
		return new ExplainRequest(category, null, null, null);
	}

	private static DelaySignals signals() {
		return new DelaySignals(LocalDate.of(2026, 10, 4), DUE.minusDays(10), false, 2, DUE.minusDays(8), DUE.minusDays(1),
				0, null, null, 1, false, false, false, 0);
	}

	private Task task(String key, TaskStatus status, LocalDateTime due, LocalDateTime completedAt, StudentProfile assigneeProfile) {
		Task t = new Task();
		t.setId(UUID.randomUUID());
		t.setProject(project);
		t.setExternalKey(key);
		t.setTitle(key + " title");
		t.setStatus(status);
		t.setDueDate(due);
		t.setCompletedAt(completedAt);
		t.setAssigneeStudent(assigneeProfile);
		return t;
	}

	private UserAccount account(AccountRole role, String name) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setAccountRole(role);
		account.setFullName(name);
		when(users.findById(account.getId())).thenReturn(Optional.of(account));
		return account;
	}

	private static StudentProfile profile(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		return profile;
	}

	private static TeamMember teamMember(StudentProfile profile, RoleInTeam role) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		TeamMember member = new TeamMember();
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private static void expectCode(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, IntegrationErrorCode code) {
		assertThatThrownBy(call)
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(code);
	}
}

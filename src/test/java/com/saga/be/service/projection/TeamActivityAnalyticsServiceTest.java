package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.project.BurndownChartResponse;
import com.saga.be.dto.project.HeatmapResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.repository.CommentRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TeamActivityAnalyticsServiceTest {

	@Mock
	private TeamRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private SprintRepository sprints;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private PeerReviewRepository peerReviews;
	@Mock
	private CommentRepository comments;
	@Mock
	private TaskFileRepository files;
	@Mock
	private TaskWebLinkRepository webLinks;
	@Mock
	private TaskAttachmentRepository attachments;
	@Mock
	private TaskRepository tasks;
	@Mock
	private UserAccountRepository users;
	@Mock
	private ProjectRepository projects;

	private TeamActivityAnalyticsService service;
	private UUID userId;
	private UUID courseId;
	private UUID teamId;
	private UUID projectId;
	private UUID studentId;

	@BeforeEach
	void setUp() {
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new TeamActivityAnalyticsService(
				teams,
				members,
				sprints,
				commits,
				peerReviews,
				comments,
				files,
				webLinks,
				attachments,
				tasks,
				authorization);
		userId = UUID.randomUUID();
		courseId = UUID.randomUUID();
		teamId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		studentId = UUID.randomUUID();
	}

	@Test
	void adminDenied() {
		when(teams.findFetchedByIdAndCourse_Id(teamId, courseId)).thenReturn(Optional.of(teamWithProject()));
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.ADMIN)));
		assertThatThrownBy(() ->
						service.heatmap(userId, courseId, teamId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), null))
				.isInstanceOf(IntegrationException.class);
	}

	@Test
	void missingProjectIs404() {
		Team team = teamWithProject();
		team.setProject(null);
		when(teams.findFetchedByIdAndCourse_Id(teamId, courseId)).thenReturn(Optional.of(team));
		assertThatThrownBy(() ->
						service.heatmap(userId, courseId, teamId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 2), null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	@Test
	void startAfterEndIs400() {
		stubActiveMember();
		assertThatThrownBy(() ->
						service.heatmap(userId, courseId, teamId, LocalDate.of(2026, 8, 10), LocalDate.of(2026, 8, 1), null))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		verify(commits, never()).findAuthorAndCommittedAtByProject(projectId);
	}

	@Test
	void unknownStudentFilterIs404() {
		stubActiveMember();
		when(members.findFetchedByTeam_Id(teamId)).thenReturn(List.of(activeMember(studentId, "SE1", "An")));
		assertThatThrownBy(() -> service.heatmap(
						userId, courseId, teamId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 1), UUID.randomUUID()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
	}

	@Test
	void heatmapScoresCommitPeerReviewCommentDocumentAndTask() {
		stubActiveMember();
		when(members.findFetchedByTeam_Id(teamId)).thenReturn(List.of(activeMember(studentId, "SE1", "An")));
		LocalDate day = LocalDate.of(2026, 8, 2);
		LocalDateTime at = day.atTime(10, 0);
		when(commits.findAuthorAndCommittedAtByProject(projectId))
				.thenReturn(List.<Object[]>of(event(studentId, at), event(studentId, at)));
		when(peerReviews.findReviewerAndCreatedAtByProject(projectId))
				.thenReturn(List.<Object[]>of(event(studentId, at)));
		when(comments.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.<Object[]>of(event(studentId, at)));
		when(files.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.<Object[]>of(event(studentId, at)));
		when(webLinks.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(attachments.findAssigneeAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(tasks.findAssigneeAndCreatedAtByProject(projectId)).thenReturn(List.<Object[]>of(event(studentId, at)));

		HeatmapResponse response =
				service.heatmap(userId, courseId, teamId, LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 3), null);

		assertThat(response.courseId()).isEqualTo(courseId);
		assertThat(response.teamId()).isEqualTo(teamId);
		assertThat(response.studentId()).isNull();
		assertThat(response.students()).hasSize(1);
		HeatmapResponse.StudentHeatmap student = response.students().getFirst();
		assertThat(student.commits()).isEqualTo(2);
		assertThat(student.peerReviews()).isEqualTo(1);
		assertThat(student.comments()).isEqualTo(1);
		assertThat(student.documents()).isEqualTo(1);
		assertThat(student.tasks()).isEqualTo(1);
		assertThat(student.totalActivities()).isEqualTo(6);
		assertThat(student.totalScore())
				.isEqualTo(2 * 3 + 2 + 1 + 1 + 2);
		assertThat(student.cells()).hasSize(3);
		assertThat(student.cells().get(1).date()).isEqualTo(day);
		assertThat(student.cells().get(1).totalScore()).isEqualTo(student.totalScore());
		assertThat(student.cells().getFirst().totalScore()).isZero();
		assertThat(response.days().get(1).commits()).isEqualTo(2);
	}

	@Test
	void heatmapStudentFilterHidesTeammatesAndIgnoresUnmappedEvents() {
		stubAssignedLecturer();
		UUID other = UUID.randomUUID();
		when(members.findFetchedByTeam_Id(teamId))
				.thenReturn(List.of(activeMember(studentId, "SE1", "An"), activeMember(other, "SE2", "Binh")));
		LocalDate day = LocalDate.of(2026, 8, 1);
		when(commits.findAuthorAndCommittedAtByProject(projectId))
				.thenReturn(List.<Object[]>of(event(studentId, day.atTime(9, 0)), event(other, day.atTime(11, 0))));
		when(peerReviews.findReviewerAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(comments.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(files.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(webLinks.findAuthorAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(attachments.findAssigneeAndCreatedAtByProject(projectId)).thenReturn(List.of());
		when(tasks.findAssigneeAndCreatedAtByProject(projectId)).thenReturn(List.of());

		HeatmapResponse response = service.heatmap(userId, courseId, teamId, day, day, studentId);

		assertThat(response.studentId()).isEqualTo(studentId);
		assertThat(response.students()).extracting(HeatmapResponse.StudentHeatmap::studentId).containsExactly(studentId);
		assertThat(response.students().getFirst().commits()).isEqualTo(1);
		assertThat(response.days().getFirst().commits()).isEqualTo(1);
	}

	@Test
	void burndownUsesIntegerIdealLineAndCompletedAt() {
		stubActiveMember();
		Sprint sprint = sprint(
				LocalDateTime.of(2026, 8, 1, 0, 0),
				LocalDateTime.of(2026, 8, 7, 23, 59));
		when(sprints.findActiveByIdAndProject_Id(sprint.getId(), projectId)).thenReturn(Optional.of(sprint));
		when(tasks.findBurndownRowsByProjectAndSprint(projectId, sprint.getId()))
				.thenReturn(List.<Object[]>of(
						burndownRow(TaskStatus.DONE, LocalDateTime.of(2026, 8, 2, 12, 0), null, null),
						burndownRow(TaskStatus.DONE, LocalDateTime.of(2026, 8, 3, 9, 0), null, null),
						burndownRow(TaskStatus.TODO, null, null, LocalDateTime.of(2026, 8, 1, 8, 0))));

		BurndownChartResponse chart = service.burndown(userId, courseId, teamId, sprint.getId());

		assertThat(chart.totalScope()).isEqualTo(3);
		assertThat(chart.points()).hasSize(7);
		assertThat(chart.points().getFirst().date()).isEqualTo(LocalDate.of(2026, 8, 1));
		assertThat(chart.points().getFirst().idealRemaining()).isEqualTo(3);
		assertThat(chart.points().getFirst().actualRemaining()).isEqualTo(3);
		assertThat(chart.points().getFirst().doneCount()).isZero();
		assertThat(chart.points().get(1).idealRemaining()).isEqualTo(2);
		assertThat(chart.points().get(1).doneCount()).isEqualTo(1);
		assertThat(chart.points().get(1).actualRemaining()).isEqualTo(2);
		assertThat(chart.points().get(2).doneCount()).isEqualTo(2);
		assertThat(chart.points().getLast().idealRemaining()).isZero();
		assertThat(chart.points().getLast().actualRemaining()).isEqualTo(1);
	}

	@Test
	void burndownSprintMissingDatesIs400() {
		stubActiveMember();
		Sprint sprint = sprint(null, null);
		when(sprints.findActiveByIdAndProject_Id(sprint.getId(), projectId)).thenReturn(Optional.of(sprint));
		assertThatThrownBy(() -> service.burndown(userId, courseId, teamId, sprint.getId()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void burndownSprintOutsideProjectIs404() {
		stubActiveMember();
		UUID sprintId = UUID.randomUUID();
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.burndown(userId, courseId, teamId, sprintId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	private void stubActiveMember() {
		when(teams.findFetchedByIdAndCourse_Id(teamId, courseId)).thenReturn(Optional.of(teamWithProject()));
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.STUDENT)));
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
	}

	private void stubAssignedLecturer() {
		when(teams.findFetchedByIdAndCourse_Id(teamId, courseId)).thenReturn(Optional.of(teamWithProject()));
		when(users.findById(userId)).thenReturn(Optional.of(account(AccountRole.LECTURER)));
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
	}

	private Team teamWithProject() {
		Course course = new Course();
		course.setId(courseId);
		Project project = new Project();
		project.setId(projectId);
		Team team = new Team();
		team.setId(teamId);
		team.setCourse(course);
		team.setProject(project);
		return team;
	}

	private TeamMember activeMember(UUID profileId, String code, String name) {
		UserAccount account = new UserAccount();
		account.setFullName(name);
		StudentProfile profile = new StudentProfile();
		profile.setId(profileId);
		profile.setStudentCode(code);
		profile.setUserAccount(account);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setStudentProfile(profile);
		TeamMember member = new TeamMember();
		member.setCourseEnrollment(enrollment);
		return member;
	}

	private UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		return account;
	}

	private static Sprint sprint(LocalDateTime start, LocalDateTime end) {
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setName("Sprint 3");
		sprint.setStartDate(start);
		sprint.setEndDate(end);
		return sprint;
	}

	private static Object[] event(UUID studentId, LocalDateTime at) {
		return new Object[] {studentId, at};
	}

	private static Object[] burndownRow(
			TaskStatus status, LocalDateTime completedAt, LocalDateTime resolvedAt, LocalDateTime createdAt) {
		return new Object[] {status, completedAt, resolvedAt, createdAt};
	}
}

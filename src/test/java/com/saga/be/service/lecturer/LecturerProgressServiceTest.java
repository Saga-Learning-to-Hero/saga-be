package com.saga.be.service.lecturer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;

import com.saga.be.dto.team.LecturerCourseProgressResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
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
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LecturerProgressServiceTest {

	@Mock
	private LecturerCourseAuthorization authorization;
	@Mock
	private TeamRepository teams;
	@Mock
	private TaskRepository tasks;
	@Mock
	private GitCommitRepository commits;
	@Mock
	private SprintRepository sprints;

	private LecturerProgressService service;
	private UUID courseId;

	@BeforeEach
	void setUp() {
		service = new LecturerProgressService(authorization, teams, tasks, commits, sprints);
		courseId = UUID.randomUUID();
	}

	@Test
	void authorizationIsDelegatedToLecturerCourseAuthorization() {
		Course course = course();
		when(authorization.requireCourse(null, courseId)).thenReturn(course);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of());

		service.getCourseProgress(null, courseId);

		verify(authorization).requireCourse(null, courseId);
	}

	@Test
	void adminDeniedOnThisEndpointOnly() {
		// LecturerCourseAuthorization itself still lets ADMIN through (unchanged, and other
		// lecturer endpoints like course listing/roster intentionally keep that "support access"
		// behavior) — the denial here is a rule added specifically inside
		// LecturerProgressService, matching ProjectDataAuthorization's ADMIN-denied policy for
		// the equivalent project-level progress data.
		UserAccount admin = new UserAccount();
		admin.setId(UUID.randomUUID());
		admin.setAccountRole(AccountRole.ADMIN);

		assertThatThrownBy(() -> service.getCourseProgress(admin, courseId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		// The ADMIN check runs before any course lookup — courseId existence is never revealed.
		verify(authorization, never()).requireCourse(any(), any());
	}

	@Test
	void lecturerActorAllowed() {
		UserAccount lecturer = new UserAccount();
		lecturer.setId(UUID.randomUUID());
		lecturer.setAccountRole(AccountRole.LECTURER);
		Course course = course();
		when(authorization.requireCourse(lecturer, courseId)).thenReturn(course);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of());

		LecturerCourseProgressResponse response = service.getCourseProgress(lecturer, courseId);

		assertThat(response.courseId()).isEqualTo(course.getId());
	}

	@Test
	void multipleTeamsAggregateWithBoundedQueries() {
		Course course = course();
		when(authorization.requireCourse(null, courseId)).thenReturn(course);
		Project projectA = project();
		Project projectB = project();
		Team teamA = team(1, "Team A", projectA);
		Team teamB = team(2, "Team B", projectB);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(teamA, teamB));
		when(tasks.countGroupedByStatusForProjects(List.of(projectA.getId(), projectB.getId())))
				.thenReturn(List.of(
						new Object[] {projectA.getId(), TaskStatus.DONE, 3L},
						new Object[] {projectA.getId(), TaskStatus.TODO, 2L},
						new Object[] {projectB.getId(), TaskStatus.TODO, 1L}));
		when(commits.countAndMaxCommittedAtGroupedByProjects(List.of(projectA.getId(), projectB.getId())))
				.thenReturn(List.of());
		when(tasks.findMaxUpdatedAtGroupedByProjects(List.of(projectA.getId(), projectB.getId())))
				.thenReturn(List.of());
		when(sprints.findActiveByProjectIdIn(List.of(projectA.getId(), projectB.getId()))).thenReturn(List.of());

		LecturerCourseProgressResponse response = service.getCourseProgress(null, courseId);

		assertThat(response.teams()).hasSize(2);
		LecturerCourseProgressResponse.Entry entryA = response.teams().stream()
				.filter(e -> e.teamId().equals(teamA.getId()))
				.findFirst()
				.orElseThrow();
		assertThat(entryA.totalTasks()).isEqualTo(5);
		assertThat(entryA.completedTasks()).isEqualTo(3);
		assertThat(entryA.taskCompletionPercent()).isEqualTo((3 * 100.0) / 5);
		LecturerCourseProgressResponse.Entry entryB = response.teams().stream()
				.filter(e -> e.teamId().equals(teamB.getId()))
				.findFirst()
				.orElseThrow();
		assertThat(entryB.totalTasks()).isEqualTo(1);
		assertThat(entryB.completedTasks()).isEqualTo(0);
	}

	@Test
	void teamWithoutProjectYieldsNullProjectIdAndZeroCounts() {
		Course course = course();
		when(authorization.requireCourse(null, courseId)).thenReturn(course);
		Team teamWithoutProject = team(1, "No Project Yet", null);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(teamWithoutProject));

		LecturerCourseProgressResponse response = service.getCourseProgress(null, courseId);

		LecturerCourseProgressResponse.Entry entry = response.teams().getFirst();
		assertThat(entry.projectId()).isNull();
		assertThat(entry.totalTasks()).isZero();
		assertThat(entry.taskCompletionPercent()).isNull();
	}

	@Test
	void currentSprintNamePickedDeterministicallyPerProject() {
		Course course = course();
		when(authorization.requireCourse(null, courseId)).thenReturn(course);
		Project projectA = project();
		Team teamA = team(1, "Team A", projectA);
		when(teams.findFetchedByCourse_IdOrderByTeamNoAsc(courseId)).thenReturn(List.of(teamA));
		when(tasks.countGroupedByStatusForProjects(List.of(projectA.getId()))).thenReturn(List.of());
		when(commits.countAndMaxCommittedAtGroupedByProjects(List.of(projectA.getId()))).thenReturn(List.of());
		when(tasks.findMaxUpdatedAtGroupedByProjects(List.of(projectA.getId()))).thenReturn(List.of());
		Sprint activeSprint = sprint(projectA, "active", "Sprint 7");
		Sprint closedSprint = sprint(projectA, "closed", "Sprint 6");
		when(sprints.findActiveByProjectIdIn(List.of(projectA.getId()))).thenReturn(List.of(activeSprint, closedSprint));

		LecturerCourseProgressResponse response = service.getCourseProgress(null, courseId);

		assertThat(response.teams().getFirst().currentSprintName()).isEqualTo("Sprint 7");
	}

	private static Course course() {
		Course course = new Course();
		course.setId(UUID.randomUUID());
		return course;
	}

	private static Project project() {
		Project project = new Project();
		project.setId(UUID.randomUUID());
		return project;
	}

	private static Team team(int teamNo, String name, Project project) {
		Team team = new Team();
		team.setId(UUID.randomUUID());
		team.setTeamNo(teamNo);
		team.setName(name);
		team.setProject(project);
		return team;
	}

	private static Sprint sprint(Project project, String state, String name) {
		JiraIntegration integration = new JiraIntegration();
		integration.setId(UUID.randomUUID());
		integration.setProject(project);
		Sprint sprint = new Sprint();
		sprint.setId(UUID.randomUUID());
		sprint.setJiraIntegration(integration);
		sprint.setState(state);
		sprint.setName(name);
		sprint.setStartDate(LocalDateTime.now());
		return sprint;
	}
}

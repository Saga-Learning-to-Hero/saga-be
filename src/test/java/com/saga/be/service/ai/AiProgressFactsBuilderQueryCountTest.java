package com.saga.be.service.ai;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.TaskDeadlineProperties;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression guard: building progress facts for a bounded task set must never issue one query
 * per task ("findTopByTask_Id...") — only the bulk "findLatestByTaskIds" query, called exactly
 * once per facts build, regardless of how many attention tasks were found.
 */
class AiProgressFactsBuilderQueryCountTest {
	private TaskRepository tasks;
	private AiTaskIntelligenceRepository taskIntelligence;
	private AiRiskAnalysisRepository riskAnalysis;
	private TeamRepository teams;
	private TeamMemberRepository teamMembers;

	@BeforeEach
	void setUp() {
		tasks = mock(TaskRepository.class);
		taskIntelligence = mock(AiTaskIntelligenceRepository.class);
		riskAnalysis = mock(AiRiskAnalysisRepository.class);
		teams = mock(TeamRepository.class);
		teamMembers = mock(TeamMemberRepository.class);
		when(taskIntelligence.findLatestByTaskIds(anyList())).thenReturn(List.of());
		when(riskAnalysis.findLatestByTaskIds(anyList())).thenReturn(List.of());
	}

	private AiProgressFactsBuilder builder() {
		return new AiProgressFactsBuilder(new ObjectMapper(), tasks, taskIntelligence, riskAnalysis, teams, teamMembers, new TaskDeadlineProperties(), Clock.fixed(Instant.parse("2026-01-10T12:00:00Z"), ZoneOffset.UTC));
	}

	@Test
	void studentFactsWithManyAttentionTasksIssuesExactlyOneBulkQueryPerCollectionNotOnePerTask() {
		UUID projectId = UUID.randomUUID();
		Project project = new Project(); project.setId(projectId);
		UUID studentId = UUID.randomUUID();

		List<Task> manyTasks = java.util.stream.IntStream.range(0, 15).mapToObj(i -> { Task t = new Task(); t.setId(UUID.randomUUID()); return t; }).toList();
		when(tasks.countStatusAndStoryPointsForAssignee(eq(projectId), eq(studentId))).thenReturn(List.<Object[]>of(new Object[]{TaskStatus.TODO, 3L, 0L}));
		when(tasks.countOverdueForProject(eq(projectId), eq(studentId), any())).thenReturn(1L);
		when(tasks.countDueSoonForProject(eq(projectId), eq(studentId), any(), any())).thenReturn(2L);
		when(tasks.findAttentionNonDoneByProjectAndAssignee(eq(projectId), eq(studentId), any())).thenReturn(manyTasks);

		builder().buildStudent(project, studentId);

		// Bulk lookups happen exactly once each, covering all 15 tasks in a single round trip.
		verify(taskIntelligence, times(1)).findLatestByTaskIds(anyList());
		verify(riskAnalysis, times(1)).findLatestByTaskIds(anyList());
		// The old N+1-prone per-task method must never be called from the facts builder.
		verify(taskIntelligence, never()).findTopByTask_IdOrderByCreatedAtDesc(any());
	}

	@Test
	void courseFactsAcrossManyProjectsIssuesOneBulkStatusQueryNotOnePerProject() {
		Course course = new Course(); course.setId(UUID.randomUUID());
		List<Project> manyProjects = java.util.stream.IntStream.range(0, 12).mapToObj(i -> { Project p = new Project(); p.setId(UUID.randomUUID()); return p; }).toList();
		when(tasks.countGroupedByStatusForProjects(anyList())).thenReturn(List.of());
		when(tasks.countOverdueForProjects(anyList(), any())).thenReturn(0L);
		when(tasks.countDueSoonForProjects(anyList(), any(), any())).thenReturn(0L);

		builder().buildCourse(course, manyProjects);

		verify(tasks, times(1)).countGroupedByStatusForProjects(anyList());
		verify(tasks, times(1)).countOverdueForProjects(anyList(), any());
		verify(tasks, times(1)).countDueSoonForProjects(anyList(), any(), any());
		verify(tasks, never()).countOverdueForProject(any(), any(), any());
	}
}

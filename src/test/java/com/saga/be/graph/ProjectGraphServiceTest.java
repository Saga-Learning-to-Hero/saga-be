package com.saga.be.graph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class ProjectGraphServiceTest {

	@Mock
	private ProjectDataAuthorization authorization;
	@Mock
	private ProjectGraphProjector projector;
	@Mock
	private ProjectGraphReader reader;
	@Mock
	private SprintRepository sprints;
	@Mock
	private TeamMemberRepository members;

	private ProjectGraphService service;
	private UUID userId;
	private UUID projectId;
	private UUID sprintId;
	private UUID studentId;

	@BeforeEach
	void setUp() {
		service = new ProjectGraphService(authorization, projector, reader, sprints, members);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
		sprintId = UUID.randomUUID();
		studentId = UUID.randomUUID();
	}

	@Test
	void studentOutsideTeamNeverReadsGraph() {
		doThrow(new IntegrationException(
						IntegrationErrorCode.INTEGRATION_FORBIDDEN, HttpStatus.FORBIDDEN, "not a member"))
				.when(authorization)
				.requireReader(userId, projectId);
		assertThatThrownBy(() -> service.overview(userId, projectId, null, GraphViewQuery.none()))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
		verify(projector, never()).ensureFresh(any());
		verify(reader, never()).overview(any(), any());
	}

	@Test
	void lecturerOutsideCourseNeverReadsGraph() {
		doThrow(new AcademicException(
						AcademicErrorCode.LECTURER_COURSE_FORBIDDEN, HttpStatus.FORBIDDEN, "wrong course"))
				.when(authorization)
				.requireReader(userId, projectId);
		assertThatThrownBy(() -> service.overview(userId, projectId, null, GraphViewQuery.none()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		verify(reader, never()).overview(any(), any());
	}

	@Test
	void defaultOverviewOmitsCommitHairball() {
		when(projector.ensureFresh(projectId)).thenReturn(4L);
		when(reader.overview(projectId, null)).thenReturn(graph("student:1", "task:own", "commit:own"));
		GraphRead read = service.overview(userId, projectId, null, GraphViewQuery.none());
		assertThat(read.body().nodes())
				.extracting(node -> node.data().id())
				.containsExactlyInAnyOrder("student:1", "task:own")
				.doesNotContain("commit:own");
		assertThat(read.body().meta()).isNotNull();
		assertThat(read.body().meta().totalNodes()).isEqualTo(3);
		assertThat(read.body().meta().returnedNodes()).isEqualTo(2);
		assertThat(read.etag(projectId)).isNotEqualTo("graph-" + projectId + "-4");
		verify(authorization).requireReader(userId, projectId);
		verify(reader).overview(projectId, null);
	}

	@Test
	void includeCommitsKeepsFullOverview() {
		when(projector.ensureFresh(projectId)).thenReturn(4L);
		when(reader.overview(projectId, null)).thenReturn(graph("student:1", "task:own", "commit:own"));
		GraphRead read = service.overview(userId, projectId, null, GraphViewQuery.full());
		assertThat(read.body().meta()).isNull();
		assertThat(read.body().nodes())
				.extracting(node -> node.data().id())
				.containsExactlyInAnyOrder("student:1", "task:own", "commit:own");
	}

	@Test
	void sprintAndTypeFiltersDoNotRebuildAndStayOnThisProject() {
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.of(new Sprint()));
		when(projector.ensureFresh(projectId)).thenReturn(4L);
		when(reader.overview(projectId, sprintId)).thenReturn(graph("task:own", "commit:own", "student:foreign-looking"));
		GraphRead read = service.overview(
				userId,
				projectId,
				sprintId,
				GraphViewQuery.parse(null, null, "TASK,COMMIT", "EVIDENCED_BY", null, null, null));
		assertThat(read.body().nodes())
				.extracting(node -> node.data().id())
				.containsExactlyInAnyOrder("task:own", "commit:own");
		assertThat(read.body().nodes())
				.extracting(node -> node.data().id())
				.doesNotContain("student:foreign-looking");
		assertThat(read.body().meta().truncated()).isFalse();
		assertThat(read.etag(projectId)).isNotEqualTo("graph-" + projectId + "-4");
		verify(projector).ensureFresh(projectId);
		verify(reader).overview(projectId, sprintId);
		verify(reader, never()).overview(any(), org.mockito.ArgumentMatchers.isNull());
	}

	@Test
	void defaultActivityOmitsCommits() {
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.of(new Sprint()));
		when(projector.ensureFresh(projectId)).thenReturn(4L);
		when(reader.activity(projectId, sprintId)).thenReturn(graph("task:own", "commit:own"));
		GraphRead read = service.activity(userId, projectId, sprintId, GraphViewQuery.none());
		assertThat(read.body().nodes())
				.extracting(node -> node.data().id())
				.containsExactly("task:own")
				.doesNotContain("commit:own");
	}

	@Test
	void contributionRejectsStudentFromAnotherProject() {
		when(members.existsActiveByProjectIdAndStudentProfileId(projectId, studentId)).thenReturn(false);
		assertThatThrownBy(() ->
						service.contribution(userId, projectId, studentId, null, GraphViewQuery.none()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND);
		verify(reader, never()).contribution(any(), any(), any());
	}

	@Test
	void sprintFromAnotherProjectIsRejected() {
		when(sprints.findActiveByIdAndProject_Id(sprintId, projectId)).thenReturn(Optional.empty());
		assertThatThrownBy(() -> service.activity(userId, projectId, sprintId, GraphViewQuery.none()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
		verify(reader, never()).activity(any(), any());
		verify(projector, never()).ensureFresh(any());
	}

	private static CytoscapeGraphResponse graph(String... ids) {
		CytoscapeGraphBuilder builder = new CytoscapeGraphBuilder();
		for (String id : ids) {
			String type = id.startsWith("commit") ? "COMMIT" : id.startsWith("student") ? "STUDENT" : "TASK";
			builder.node(CytoscapeGraphBuilder.nodeData(id, id, null, type, null, null, null, null, null, null));
		}
		if (ids.length >= 2) {
			builder.edge(CytoscapeGraphBuilder.edgeData(
					"EVIDENCED_BY:" + ids[0] + ":" + ids[1], ids[0], ids[1], "EVIDENCED_BY", null, null));
		}
		return builder.build();
	}
}

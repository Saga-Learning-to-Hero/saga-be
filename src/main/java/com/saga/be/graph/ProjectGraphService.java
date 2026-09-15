package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ProjectGraphService {

	private final ProjectDataAuthorization authorization;
	private final ProjectGraphProjector projector;
	private final ProjectGraphReader reader;
	private final SprintRepository sprints;
	private final TeamMemberRepository members;

	public ProjectGraphService(
			ProjectDataAuthorization authorization,
			ProjectGraphProjector projector,
			ProjectGraphReader reader,
			SprintRepository sprints,
			TeamMemberRepository members) {
		this.authorization = authorization;
		this.projector = projector;
		this.reader = reader;
		this.sprints = sprints;
		this.members = members;
	}

	public GraphRead overview(UUID userId, UUID projectId, UUID sprintId, GraphViewQuery view) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		return read(projectId, scope("overview", sprintId, null), view, () -> reader.overview(projectId, sprintId));
	}

	public GraphRead contribution(
			UUID userId, UUID projectId, UUID studentId, UUID sprintId, GraphViewQuery view) {
		authorization.requireReader(userId, projectId);
		if (!members.existsActiveByProjectIdAndStudentProfileId(projectId, studentId)) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Student is not an active member of this project.");
		}
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		return read(
				projectId,
				scope("contribution", sprintId, studentId),
				view,
				() -> reader.contribution(projectId, studentId, sprintId));
	}

	public GraphRead activity(UUID userId, UUID projectId, UUID sprintId, GraphViewQuery view) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		return read(projectId, scope("activity", sprintId, null), view, () -> reader.activity(projectId, sprintId));
	}

	public GraphRead attribution(UUID userId, UUID projectId, UUID sprintId, GraphViewQuery view) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		return read(projectId, scope("attribution", sprintId, null), view, () -> reader.attribution(projectId, sprintId));
	}

	public GraphRead peerReview(UUID userId, UUID projectId, UUID sprintId, GraphViewQuery view) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		return read(projectId, scope("peer-review", sprintId, null), view, () -> reader.peerReview(projectId, sprintId));
	}

	private GraphRead read(
			UUID projectId, String scope, GraphViewQuery view, Supplier<CytoscapeGraphResponse> load) {
		GraphViewQuery query = view == null ? GraphViewQuery.none() : view;
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(
				GraphSubgraphFilter.apply(load.get(), query, revision),
				revision,
				GraphViewQuery.combine(scope, query));
	}

	private static String scope(String graph, UUID sprintId, UUID studentId) {
		if (studentId == null && sprintId == null) {
			return "";
		}
		StringBuilder key = new StringBuilder(graph);
		if (studentId != null) {
			key.append("|student:").append(studentId);
		}
		if (sprintId != null) {
			key.append("|sprint:").append(sprintId);
		}
		return key.toString();
	}

	private void requireSprint(UUID projectId, UUID sprintId) {
		sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Sprint was not found for this project."));
	}
}

package com.saga.be.graph;

import com.saga.be.dto.graph.CytoscapeGraphResponse;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.graph.ProjectGraphSnapshot.StudentNode;
import com.saga.be.repository.SprintRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@Profile("!test")
public class ProjectGraphService {

	private final ProjectDataAuthorization authorization;
	private final ProjectGraphLoader loader;
	private final ProjectGraphWriter writer;
	private final ProjectGraphReader reader;
	private final SprintRepository sprints;

	public ProjectGraphService(
			ProjectDataAuthorization authorization,
			ProjectGraphLoader loader,
			ProjectGraphWriter writer,
			ProjectGraphReader reader,
			SprintRepository sprints) {
		this.authorization = authorization;
		this.loader = loader;
		this.writer = writer;
		this.reader = reader;
		this.sprints = sprints;
	}

	public CytoscapeGraphResponse overview(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		rebuild(projectId);
		return reader.overview(projectId, sprintId);
	}

	public CytoscapeGraphResponse contribution(UUID userId, UUID projectId, UUID studentId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		ProjectGraphSnapshot snapshot = rebuild(projectId);
		boolean member = snapshot.students().stream().map(StudentNode::id).anyMatch(studentId::equals);
		if (!member) {
			throw new AcademicException(
					AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND,
					HttpStatus.NOT_FOUND,
					"Student is not an active member of this project.");
		}
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		return reader.contribution(projectId, studentId, sprintId);
	}

	public CytoscapeGraphResponse activity(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		rebuild(projectId);
		return reader.activity(projectId, sprintId);
	}

	public CytoscapeGraphResponse attribution(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		rebuild(projectId);
		return reader.attribution(projectId, sprintId);
	}

	public CytoscapeGraphResponse peerReview(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		rebuild(projectId);
		return reader.peerReview(projectId, sprintId);
	}

	private ProjectGraphSnapshot rebuild(UUID projectId) {
		ProjectGraphSnapshot snapshot = loader.load(projectId);
		writer.rebuild(snapshot);
		return snapshot;
	}

	private void requireSprint(UUID projectId, UUID sprintId) {
		sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Sprint was not found for this project."));
	}
}

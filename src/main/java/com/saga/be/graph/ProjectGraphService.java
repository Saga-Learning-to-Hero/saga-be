package com.saga.be.graph;

import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.service.projection.ProjectDataAuthorization;
import java.util.UUID;
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

	public GraphRead overview(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(reader.overview(projectId, sprintId), revision);
	}

	public GraphRead contribution(UUID userId, UUID projectId, UUID studentId, UUID sprintId) {
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
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(reader.contribution(projectId, studentId, sprintId), revision);
	}

	public GraphRead activity(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(reader.activity(projectId, sprintId), revision);
	}

	public GraphRead attribution(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		if (sprintId != null) {
			requireSprint(projectId, sprintId);
		}
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(reader.attribution(projectId, sprintId), revision);
	}

	public GraphRead peerReview(UUID userId, UUID projectId, UUID sprintId) {
		authorization.requireReader(userId, projectId);
		requireSprint(projectId, sprintId);
		long revision = projector.ensureFresh(projectId);
		return new GraphRead(reader.peerReview(projectId, sprintId), revision);
	}

	private void requireSprint(UUID projectId, UUID sprintId) {
		sprints.findActiveByIdAndProject_Id(sprintId, projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Sprint was not found for this project."));
	}
}

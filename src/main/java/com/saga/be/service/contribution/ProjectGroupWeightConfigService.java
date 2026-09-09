package com.saga.be.service.contribution;

import com.saga.be.dto.contribution.ProjectGroupWeightsRequest;
import com.saga.be.dto.contribution.ProjectGroupWeightsResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.assessment.ProjectGroupWeightConfig;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.ProjectGroupWeightConfigRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile("!test")
public class ProjectGroupWeightConfigService {

	private static final MathContext MATH = ContributionSliceWeights.MATH;
	private static final BigDecimal TOLERANCE = new BigDecimal("0.00001");

	private final ProjectRepository projects;
	private final TeamByProjectRepository teams;
	private final ProjectGroupWeightConfigRepository configs;
	private final LecturerCourseAuthorization authorization;
	private final AuditService audit;

	public ProjectGroupWeightConfigService(
			ProjectRepository projects,
			TeamByProjectRepository teams,
			ProjectGroupWeightConfigRepository configs,
			LecturerCourseAuthorization authorization,
			AuditService audit) {
		this.projects = projects;
		this.teams = teams;
		this.configs = configs;
		this.authorization = authorization;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public ProjectGroupWeightsResponse get(UserAccount actor, UUID projectId) {
		Team team = requireTeam(actor, projectId);
		ProjectGroupWeightConfig config = configs.findByProject_Id(projectId).orElse(null);
		if (config == null) {
			return new ProjectGroupWeightsResponse(projectId, team.getId(), null, null, null, null, null);
		}
		return toResponse(config);
	}

	@Transactional
	public ProjectGroupWeightsResponse update(
			UserAccount actor, UUID projectId, ProjectGroupWeightsRequest request, AuditRequest auditRequest) {
		Team team = requireTeam(actor, projectId);
		if (request.teamId() != null && !request.teamId().equals(team.getId())) {
			throw new AcademicException(
					AcademicErrorCode.GROUP_PROJECT_MISMATCH,
					HttpStatus.BAD_REQUEST,
					"teamId does not match the project team.");
		}
		validateUnitWeights(request);
		ProjectGroupWeightConfig config = configs.findByProject_Id(projectId).orElseGet(ProjectGroupWeightConfig::new);
		boolean created = config.getId() == null;
		config.setProject(team.getProject());
		config.setTeam(team);
		config.setCodeWeight(request.codeWeight());
		config.setTestWeight(request.testWeight());
		config.setDocumentWeight(request.documentWeight());
		config.setResearchWeight(request.researchWeight());
		config.setNote(request.note());
		config.setUpdatedBy(actor);
		ProjectGroupWeightConfig saved = configs.save(config);
		if (audit != null) {
			audit.record(
					actor,
					team.getProject(),
					team,
					created ? "PROJECT_GROUP_WEIGHTS_CREATED" : "PROJECT_GROUP_WEIGHTS_UPDATED",
					"project_group_weight_config",
					saved.getId(),
					Map.of(),
					Map.of(
							"codeWeight",
							saved.getCodeWeight(),
							"testWeight",
							saved.getTestWeight(),
							"documentWeight",
							saved.getDocumentWeight(),
							"researchWeight",
							saved.getResearchWeight()),
					Map.of("projectId", projectId.toString()),
					AuditSource.API,
					auditRequest == null ? null : auditRequest.requestId(),
					auditRequest == null ? null : auditRequest.ip(),
					auditRequest == null ? null : auditRequest.userAgent());
		}
		return toResponse(saved);
	}

	static void validateUnitWeights(ProjectGroupWeightsRequest request) {
		if (request == null
				|| request.codeWeight() == null
				|| request.testWeight() == null
				|| request.documentWeight() == null
				|| request.researchWeight() == null) {
			throw invalid("Four contribution weights are required.");
		}
		requireUnitRange(request.codeWeight());
		requireUnitRange(request.testWeight());
		requireUnitRange(request.documentWeight());
		requireUnitRange(request.researchWeight());
		BigDecimal sum = request.codeWeight()
				.add(request.testWeight(), MATH)
				.add(request.documentWeight(), MATH)
				.add(request.researchWeight(), MATH);
		if (sum.subtract(BigDecimal.ONE, MATH).abs().compareTo(TOLERANCE) > 0) {
			throw invalid("Project-group contribution weights must sum to 1.0.");
		}
	}

	private Team requireTeam(UserAccount actor, UUID projectId) {
		Project project = projects.findFetchedById(projectId).orElse(null);
		Team team = teams.findByProject_Id(projectId).orElse(null);
		if (project == null || team == null) {
			throw new AcademicException(AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "Project was not found.");
		}
		authorization.requireCourse(actor, team.getCourse().getId());
		return team;
	}

	private static void requireUnitRange(BigDecimal value) {
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw invalid("Project-group contribution weights must be between 0 and 1.");
		}
	}

	private static AcademicException invalid(String message) {
		return new AcademicException(AcademicErrorCode.CONTRIBUTION_WEIGHTS_INVALID, HttpStatus.BAD_REQUEST, message);
	}

	private static ProjectGroupWeightsResponse toResponse(ProjectGroupWeightConfig config) {
		return new ProjectGroupWeightsResponse(
				config.getProject().getId(),
				config.getTeam().getId(),
				config.getCodeWeight(),
				config.getTestWeight(),
				config.getDocumentWeight(),
				config.getResearchWeight(),
				config.getNote());
	}
}

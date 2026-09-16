package com.saga.be.service.student;

import com.saga.be.dto.project.CreateStudentProjectRequest;
import com.saga.be.dto.project.PatchProjectRequest;
import com.saga.be.dto.project.ProjectTypeResponse;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.dto.project.StudentProjectResponse.CreatedBy;
import com.saga.be.dto.project.StudentProjectResponse.ProjectTypeSummary;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.ProjectType;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.ProjectTypeRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.projection.ProjectDataAuthorization;
import com.saga.be.service.student.StudentTeamService.ActiveTeamMembership;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@Profile("!test")
public class StudentProjectService {

	public static final String PROJECT_CREATED = "PROJECT_CREATED";
	public static final String PROJECT_UPDATED = "PROJECT_UPDATED";

	private final StudentTeamService teams;
	private final ProjectRepository projects;
	private final ProjectTypeRepository projectTypes;
	private final TeamRepository lockedTeams;
	private final TeamByProjectRepository teamByProject;
	private final UserAccountRepository users;
	private final ProjectDataAuthorization authorization;
	private final ProjectRealtimePublisher realtime;
	private final AuditService audit;

	public StudentProjectService(
			StudentTeamService teams,
			ProjectRepository projects,
			ProjectTypeRepository projectTypes,
			TeamRepository lockedTeams,
			TeamByProjectRepository teamByProject,
			UserAccountRepository users,
			ProjectDataAuthorization authorization,
			ProjectRealtimePublisher realtime,
			AuditService audit) {
		this.teams = teams;
		this.projects = projects;
		this.projectTypes = projectTypes;
		this.lockedTeams = lockedTeams;
		this.teamByProject = teamByProject;
		this.users = users;
		this.authorization = authorization;
		this.realtime = realtime;
		this.audit = audit;
	}

	@Transactional(readOnly = true)
	public List<ProjectTypeResponse> listTypes() {
		return projectTypes.findAllByOrderByCodeAsc().stream()
				.map(type -> new ProjectTypeResponse(type.getId(), type.getCode(), type.getName(), type.getDescription()))
				.toList();
	}

	@Transactional(readOnly = true)
	public StudentProjectResponse getProject(UUID userId, UUID courseId) {
		ActiveTeamMembership membership = teams.requireActiveMembership(userId, courseId);
		Project project = membership.team().getProject();
		if (project == null) {
			throw new AcademicException(
					AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "This team does not have a project yet.");
		}
		return toResponse(project, membership.team());
	}

	@Transactional
	public StudentProjectResponse create(
			UUID userId, UUID courseId, CreateStudentProjectRequest request, AuditRequest auditRequest) {
		ActiveTeamMembership membership = teams.requireActiveMembership(userId, courseId);
		if (membership.member().getRoleInTeam() != RoleInTeam.LEADER) {
			throw new AcademicException(
					AcademicErrorCode.NOT_TEAM_LEADER,
					HttpStatus.FORBIDDEN,
					"Only the Team Leader can create this team's project.");
		}
		Team locked = lockedTeams
				.findByIdForUpdate(membership.team().getId())
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.TEAM_NOT_FOUND, HttpStatus.NOT_FOUND, "Student is not assigned to a team."));
		if (locked.getProject() != null) {
			throw alreadyExists();
		}
		String name = requireName(request == null ? null : request.name());
		String description = trimToNull(request == null ? null : request.description());
		ProjectType type = resolveType(request == null ? null : request.projectTypeId());
		UserAccount actor = membership.profile().getUserAccount();

		Project project = new Project();
		project.setCourse(locked.getCourse());
		project.setProjectType(type);
		project.setName(name);
		project.setDescription(description);
		project.setRepositoryUrl(null);
		project.setCreatedBy(actor);
		try {
			projects.save(project);
			locked.setProject(project);
			lockedTeams.save(locked);
			lockedTeams.flush();
		} catch (DataIntegrityViolationException ex) {
			if (isTeamProjectUniqueViolation(ex)) {
				throw alreadyExists();
			}
			throw ex;
		}

		audit.record(
				actor,
				project,
				locked,
				PROJECT_CREATED,
				"project",
				project.getId(),
				null,
				afterSnapshot(project, locked),
				Map.of(),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
		return toResponse(project, locked);
	}

	@Transactional
	public StudentProjectResponse patch(
			UUID userId, UUID projectId, PatchProjectRequest request, AuditRequest auditRequest) {
		authorization.requireStudentLeader(userId, projectId);
		Project project = projects
				.findById(projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "This team does not have a project yet."));
		Team team = teamByProject
				.findByProject_Id(projectId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_NOT_FOUND, HttpStatus.NOT_FOUND, "This team does not have a project yet."));
		UserAccount actor = users.findById(userId).orElseThrow();

		Map<String, Object> before = new LinkedHashMap<>();
		Map<String, Object> after = new LinkedHashMap<>();
		boolean nameChanged = false;
		if (request != null && request.name() != null) {
			String nextName = requireName(request.name());
			if (!nextName.equals(project.getName())) {
				before.put("name", project.getName());
				after.put("name", nextName);
				project.setName(nextName);
				nameChanged = true;
			}
		}
		if (request != null && request.description() != null) {
			String nextDescription = trimToNull(request.description());
			if (!Objects.equals(nextDescription, project.getDescription())) {
				before.put("description", project.getDescription());
				after.put("description", nextDescription);
				project.setDescription(nextDescription);
			}
		}
		if (before.isEmpty()) {
			return toResponse(project, team);
		}
		projects.save(project);
		audit.record(
				actor,
				project,
				team,
				PROJECT_UPDATED,
				"project",
				project.getId(),
				before,
				after,
				Map.of(),
				AuditSource.API,
				auditRequest == null ? null : auditRequest.requestId(),
				auditRequest == null ? null : auditRequest.ip(),
				auditRequest == null ? null : auditRequest.userAgent());
		if (nameChanged) {
			realtime.publish(ProjectRealtimeEventType.PROJECT_METADATA_CHANGED, project.getId());
		}
		return toResponse(project, team);
	}

	private ProjectType resolveType(UUID projectTypeId) {
		if (projectTypeId == null) {
			return null;
		}
		return projectTypes
				.findById(projectTypeId)
				.orElseThrow(() -> new AcademicException(
						AcademicErrorCode.PROJECT_TYPE_NOT_FOUND,
						HttpStatus.NOT_FOUND,
						"Project type was not found."));
	}

	private static StudentProjectResponse toResponse(Project project, Team team) {
		ProjectType type = project.getProjectType();
		UserAccount creator = project.getCreatedBy();
		return new StudentProjectResponse(
				project.getId(),
				project.getCourse() == null ? null : project.getCourse().getId(),
				team.getId(),
				team.getTeamNo() == null ? 0 : team.getTeamNo(),
				team.getName(),
				project.getName(),
				project.getDescription(),
				type == null ? null : new ProjectTypeSummary(type.getId(), type.getCode(), type.getName()),
				creator == null ? null : new CreatedBy(creator.getId(), creator.getFullName()),
				project.getCreatedAt());
	}

	private static Map<String, Object> afterSnapshot(Project project, Team team) {
		Map<String, Object> after = new LinkedHashMap<>();
		after.put("projectId", project.getId());
		after.put("name", project.getName());
		after.put("courseId", project.getCourse() == null ? null : project.getCourse().getId());
		after.put("teamId", team.getId());
		after.put("teamNo", team.getTeamNo());
		if (project.getProjectType() != null) {
			after.put("projectTypeId", project.getProjectType().getId());
		}
		return after;
	}

	private static String requireName(String name) {
		if (!StringUtils.hasText(name)) {
			throw new AcademicException(
					AcademicErrorCode.PROJECT_NAME_INVALID, HttpStatus.BAD_REQUEST, "Project name is required.");
		}
		String trimmed = name.trim();
		if (trimmed.length() > 255) {
			throw new AcademicException(
					AcademicErrorCode.PROJECT_NAME_INVALID, HttpStatus.BAD_REQUEST, "Project name is too long.");
		}
		return trimmed;
	}

	private static String trimToNull(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

	private static AcademicException alreadyExists() {
		return new AcademicException(
				AcademicErrorCode.PROJECT_ALREADY_EXISTS,
				HttpStatus.CONFLICT,
				"This team already has a project.");
	}

	private static boolean isTeamProjectUniqueViolation(DataIntegrityViolationException ex) {
		Throwable cause = ex.getMostSpecificCause();
		String message = cause == null ? ex.getMessage() : cause.getMessage();
		return message != null && message.toLowerCase(Locale.ROOT).contains("uk_team_project");
	}
}

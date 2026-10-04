package com.saga.be.service.ai;

import com.saga.be.dto.ai.CourseAiTeamAccessDtos;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiCourseKeyGrant;
import com.saga.be.entity.ai.AiTeamCredential;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiCredentialStatus;
import com.saga.be.entity.enums.AiInvocationOrigin;
import com.saga.be.entity.enums.AiProviderRole;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AiCourseKeyGrantRepository;
import com.saga.be.repository.AiTeamCredentialRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lecturer picks which teams may fall back to the course AI key. Every other team runs AI only
 * with the key its own leader entered. Only the assigned lecturer of the course manages this.
 */
@Service
@Profile("!test")
public class CourseAiTeamAccessService {

	private final LecturerCourseAuthorization authorization;
	private final TeamRepository teams;
	private final AiCourseKeyGrantRepository grants;
	private final AiTeamCredentialRepository teamCredentials;
	private final AiCredentialResolver resolver;
	private final CourseAiSettingsService settings;
	/** The course-key check runs in its own read transaction: a resolver failure must never mark the
	 * caller's transaction rollback-only. */
	private final org.springframework.transaction.support.TransactionTemplate isolated;

	public CourseAiTeamAccessService(
			LecturerCourseAuthorization authorization,
			TeamRepository teams,
			AiCourseKeyGrantRepository grants,
			AiTeamCredentialRepository teamCredentials,
			AiCredentialResolver resolver,
			CourseAiSettingsService settings,
			org.springframework.transaction.PlatformTransactionManager transactionManager) {
		this.authorization = authorization;
		this.teams = teams;
		this.grants = grants;
		this.teamCredentials = teamCredentials;
		this.resolver = resolver;
		this.settings = settings;
		this.isolated = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
		this.isolated.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		this.isolated.setReadOnly(true);
	}

	@Transactional(readOnly = true)
	public CourseAiTeamAccessDtos.Response list(UserAccount actor, UUID courseId) {
		authorization.requireAssignedLecturerStrict(actor, courseId, "Chỉ giảng viên phụ trách lớp mới quản lý key AI của lớp.");
		List<Team> courseTeams = teams.findByCourse_IdOrderByTeamNoAsc(courseId);
		List<UUID> projectIds = courseTeams.stream().map(Team::getProject).filter(Objects::nonNull).map(p -> p.getId()).toList();
		Set<UUID> granted = projectIds.isEmpty() ? Set.of() : new HashSet<>(grants.findGrantedProjectIds(projectIds));
		Map<UUID, AiTeamCredential> keys = new HashMap<>();
		if (!projectIds.isEmpty()) {
			for (AiTeamCredential key : teamCredentials.findByProject_IdIn(projectIds)) {
				if (key.getStatus() != AiCredentialStatus.REVOKED) keys.put(key.getProject().getId(), key);
			}
		}
		boolean courseKey = courseKeyConfigured(courseId);
		List<CourseAiTeamAccessDtos.TeamAccess> rows = courseTeams.stream().map(team -> {
			UUID projectId = team.getProject() == null ? null : team.getProject().getId();
			AiTeamCredential key = projectId == null ? null : keys.get(projectId);
			boolean allowed = projectId != null && granted.contains(projectId);
			boolean teamKeyUsable = key != null && key.getStatus() != AiCredentialStatus.INVALID;
			String effective = teamKeyUsable ? "TEAM" : allowed && courseKey ? "COURSE" : "NONE";
			return new CourseAiTeamAccessDtos.TeamAccess(
					team.getId(),
					team.getTeamNo(),
					team.getName(),
					projectId,
					team.getProject() == null ? null : team.getProject().getName(),
					key == null
							? new CourseAiTeamAccessDtos.TeamKey(false, null, null, null)
							: new CourseAiTeamAccessDtos.TeamKey(true, key.getProvider().name(), key.getModelId(), key.getStatus().name()),
					allowed,
					effective);
		}).toList();
		return new CourseAiTeamAccessDtos.Response(courseKey, settings.get(courseId).automationEnabled(), rows);
	}

	@Transactional
	public CourseAiTeamAccessDtos.Response update(UserAccount actor, UUID courseId, UUID projectId, CourseAiTeamAccessDtos.UpdateRequest request) {
		authorization.requireAssignedLecturerStrict(actor, courseId, "Chỉ giảng viên phụ trách lớp mới quản lý key AI của lớp.");
		if (request == null || request.allowed() == null) {
			throw new IntegrationException(IntegrationErrorCode.AI_CREDENTIAL_INVALID_REQUEST, HttpStatus.BAD_REQUEST, "Thiếu giá trị allowed.");
		}
		Team team = teams.findByCourse_IdOrderByTeamNoAsc(courseId).stream()
				.filter(t -> t.getProject() != null && t.getProject().getId().equals(projectId))
				.findFirst()
				.orElseThrow(() -> new IntegrationException(IntegrationErrorCode.AI_ANALYSIS_TARGET_NOT_FOUND, HttpStatus.NOT_FOUND,
						"Nhóm này không thuộc lớp."));
		var existing = grants.findByProject_Id(projectId);
		if (request.allowed() && existing.isEmpty()) {
			AiCourseKeyGrant grant = new AiCourseKeyGrant();
			grant.setProject(team.getProject());
			grant.setGrantedBy(actor);
			grants.save(grant);
		} else if (!request.allowed()) {
			existing.ifPresent(grants::delete);
		}
		grants.flush();
		return list(actor, courseId);
	}

	private boolean courseKeyConfigured(UUID courseId) {
		try {
			return Boolean.TRUE.equals(isolated.execute(status -> resolver
					.resolve(courseId, AiAnalysisType.PROGRESS_NARRATIVE, AiProviderRole.PRIMARY, AiInvocationOrigin.AUTOMATION)
					.outcome() == AiCredentialResolver.Outcome.COURSE));
		} catch (RuntimeException ex) {
			return false;
		}
	}
}

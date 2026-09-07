package com.saga.be.service.projection;

import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Product data READ authorization for projected Tasks/Commits.
 * Distinct from integration configuration auth (member OR ADMIN).
 */
@Component
@Profile("!test")
public class ProjectDataAuthorization {

	private final UserAccountRepository users;
	private final TeamMemberRepository members;
	private final ProjectRepository projects;

	public ProjectDataAuthorization(
			UserAccountRepository users, TeamMemberRepository members, ProjectRepository projects) {
		this.users = users;
		this.members = members;
		this.projects = projects;
	}

	public void requireReader(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		AccountRole role = account.getAccountRole();
		if (role == AccountRole.ADMIN) {
			throw denied();
		}
		if (role == AccountRole.LECTURER) {
			if (!projects.existsAssignedToLecturerUser(projectId, userId)) {
				throw new AcademicException(
						AcademicErrorCode.LECTURER_COURSE_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Lecturer is not assigned to this course.");
			}
			return;
		}
		if (role == AccountRole.STUDENT) {
			if (!members.existsActiveByProjectIdAndUserId(projectId, userId)) {
				throw new IntegrationException(
						IntegrationErrorCode.INTEGRATION_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Not a member of this team.");
			}
			return;
		}
		throw denied();
	}

	public RoleInTeam requireStudentLeader(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		if (account.getAccountRole() != AccountRole.STUDENT) {
			throw denied();
		}
		RoleInTeam role = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
		if (role == null) {
			throw new IntegrationException(
					IntegrationErrorCode.INTEGRATION_FORBIDDEN,
					HttpStatus.FORBIDDEN,
					"Not a member of this team.");
		}
		if (role != RoleInTeam.LEADER) {
			throw new IntegrationException(
					IntegrationErrorCode.NOT_TEAM_LEADER,
					HttpStatus.FORBIDDEN,
					"Only the Team Leader can trigger project sync.");
		}
		return role;
	}

	private static IntegrationException denied() {
		return new IntegrationException(
				IntegrationErrorCode.ACCESS_DENIED, HttpStatus.FORBIDDEN, "Access denied.");
	}
}

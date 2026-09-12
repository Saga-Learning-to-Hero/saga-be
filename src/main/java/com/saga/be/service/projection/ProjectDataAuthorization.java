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

	/**
	 * Progress-dashboard authorization: assigned LECTURER, or the ACTIVE Team Leader of this
	 * project. Deliberately narrower than {@link #requireReader} — which also allows an ordinary
	 * ACTIVE MEMBER, correctly, since it guards the raw Task/Commit/Sprint lists a member should
	 * see in full. The progress/analytics aggregation is a distinct, more sensitive product
	 * surface where ordinary members must be denied, so this reuses the same underlying repository
	 * checks ({@code existsAssignedToLecturerUser}, {@code findActiveRoleByProjectIdAndUserId})
	 * {@link #requireReader}/{@link #requireStudentLeader} already rely on, recombined under a
	 * policy neither of those two methods expresses on its own — {@code requireReader} is too
	 * broad (allows MEMBER) and {@code requireStudentLeader} is too narrow (denies LECTURER
	 * entirely). Neither existing method's behavior is changed by adding this one.
	 */
	public void requireLecturerOrTeamLeader(UUID userId, UUID projectId) {
		UserAccount account = users.findById(userId).orElseThrow();
		AccountRole role = account.getAccountRole();
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
			RoleInTeam teamRole = members.findActiveRoleByProjectIdAndUserId(projectId, userId).orElse(null);
			if (teamRole == null) {
				throw new IntegrationException(
						IntegrationErrorCode.INTEGRATION_FORBIDDEN,
						HttpStatus.FORBIDDEN,
						"Not a member of this team.");
			}
			if (teamRole != RoleInTeam.LEADER) {
				throw new IntegrationException(
						IntegrationErrorCode.NOT_TEAM_LEADER,
						HttpStatus.FORBIDDEN,
						"Only the Team Leader can view this project's progress dashboard.");
			}
			return;
		}
		// ADMIN and any other role: same denial as requireReader — preserve existing project policy.
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

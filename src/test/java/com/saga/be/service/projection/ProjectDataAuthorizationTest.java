package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectDataAuthorizationTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private ProjectRepository projects;

	private ProjectDataAuthorization authorization;
	private UUID userId;
	private UUID projectId;

	@BeforeEach
	void setUp() {
		authorization = new ProjectDataAuthorization(users, members, projects);
		userId = UUID.randomUUID();
		projectId = UUID.randomUUID();
	}

	@Test
	void studentLeaderAndMemberAllowed() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(true);
		authorization.requireReader(userId, projectId);
	}

	@Test
	void studentCrossTeamDenied() {
		stubRole(AccountRole.STUDENT);
		when(members.existsActiveByProjectIdAndUserId(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> authorization.requireReader(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
	}

	@Test
	void lecturerAssignedAllowed_unrelatedDenied() {
		stubRole(AccountRole.LECTURER);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(true);
		authorization.requireReader(userId, projectId);
		when(projects.existsAssignedToLecturerUser(projectId, userId)).thenReturn(false);
		assertThatThrownBy(() -> authorization.requireReader(userId, projectId))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
	}

	@Test
	void adminDenied() {
		stubRole(AccountRole.ADMIN);
		assertThatThrownBy(() -> authorization.requireReader(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);
	}

	@Test
	void requireStudentLeader_memberDenied() {
		stubRole(AccountRole.STUDENT);
		when(members.findActiveRoleByProjectIdAndUserId(projectId, userId)).thenReturn(Optional.of(RoleInTeam.MEMBER));
		assertThatThrownBy(() -> authorization.requireStudentLeader(userId, projectId))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.NOT_TEAM_LEADER);
	}

	private void stubRole(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setId(userId);
		account.setAccountRole(role);
		when(users.findById(userId)).thenReturn(Optional.of(account));
	}
}

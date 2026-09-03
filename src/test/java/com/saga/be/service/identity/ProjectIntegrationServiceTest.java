package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.github.GitHubAppJwtService;
import com.saga.be.integration.github.GitHubOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.oauth.OAuthState;
import com.saga.be.integration.oauth.OAuthStateService;
import com.saga.be.integration.oauth.PendingJiraConnectStore;
import com.saga.be.messaging.OutboxPublisher;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.audit.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectIntegrationServiceTest {

	@Mock
	private UserAccountRepository users;
	@Mock
	private ProjectRepository projects;
	@Mock
	private TeamByProjectRepository teams;
	@Mock
	private TeamMemberRepository members;
	@Mock
	private IdentityMapRepository identities;
	@Mock
	private GithubInstallationRepository installations;
	@Mock
	private GitRepoRepository repos;
	@Mock
	private JiraIntegrationRepository jiraIntegrations;
	@Mock
	private SyncJobLogRepository syncJobs;
	@Mock
	private OAuthStateService oauthStates;
	@Mock
	private PendingJiraConnectStore pendingJira;
	@Mock
	private IntegrationProperties properties;
	@Mock
	private GitHubOAuthClient github;
	@Mock
	private GitHubAppJwtService githubJwt;
	@Mock
	private JiraOAuthClient jira;
	@Mock
	private TokenEncryptor encryptor;
	@Mock
	private AuditService audit;
	@Mock
	private OutboxPublisher outbox;

	@InjectMocks
	private ProjectIntegrationService service;

	private UserAccount student;
	private UserAccount admin;
	private Course course;
	private Team team;
	private UUID projectId;
	private CourseEnrollment enrollment;
	private TeamMember leaderMember;
	private TeamMember memberRow;

	@BeforeEach
	void setUp() {
		student = account(AccountRole.STUDENT, "leader@gmail.com");
		admin = account(AccountRole.ADMIN, "admin@saga.local");
		course = new Course();
		course.setId(UUID.randomUUID());
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		projectId = UUID.randomUUID();
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(student);
		enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setCourse(course);
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		leaderMember = membership(RoleInTeam.LEADER);
		memberRow = membership(RoleInTeam.MEMBER);
	}

	@Test
	void activeLeaderCanStartGithubAndJira() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY),
						any(),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(github.installationUrl(any())).thenReturn("https://github.com/apps/saga/installations/new");
		OAuthStartResponse githubStart = service.startGithub(student.getId(), projectId, "/projects");
		assertEquals("https://github.com/apps/saga/installations/new", githubStart.authorizationUrl());
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.JIRA_TEAM_CONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(state(OAuthFlowType.JIRA_TEAM_CONNECT));
		when(properties.getJira()).thenReturn(new IntegrationProperties.Jira());
		when(jira.authorizationUrl(any(), any(), any(), eq(true))).thenReturn("https://auth.atlassian.com/authorize");
		OAuthStartResponse jiraStart = service.startJira(student.getId(), projectId, "/projects");
		assertEquals("https://auth.atlassian.com/authorize", jiraStart.authorizationUrl());
	}

	@Test
	void activeMemberCanReadSummaryButCannotConnect() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(memberRow));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		ProjectIntegrationsResponse summary = service.summary(student.getId(), projectId);
		assertNull(summary.github());
		assertNull(summary.jira());
		IntegrationException github = assertThrows(
				IntegrationException.class, () -> service.startGithub(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, github.getCode());
		IntegrationException jiraEx =
				assertThrows(IntegrationException.class, () -> service.startJira(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, jiraEx.getCode());
	}

	@Test
	void withdrawnLeaderIsDeniedGithubAndJira() {
		enrollment.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		assertDenied(service::summary);
		assertDenied(() -> service.startGithub(student.getId(), projectId, null));
		assertDenied(() -> service.startJira(student.getId(), projectId, null));
	}

	@Test
	void completedMemberIsDeniedSummary() {
		enrollment.setEnrollmentStatus(EnrollmentStatus.COMPLETED);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(memberRow));
		assertDenied(service::summary);
	}

	@Test
	void adminBypassRemainsWithoutMembership() {
		when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(repos.findByProject_Id(projectId)).thenReturn(List.of());
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		assertDoesNotThrow(() -> service.summary(admin.getId(), projectId));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(oauthStates.start(
						eq(admin.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY),
						any(),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(github.installationUrl(any())).thenReturn("https://github.com/apps/saga/installations/new");
		assertDoesNotThrow(() -> service.startGithub(admin.getId(), projectId, null));
	}

	@Test
	void omittedReturnPathStillStartsGithubAndJiraForActiveLeader() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY),
						isNull(),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(github.installationUrl(any())).thenReturn("https://github.com/apps/saga/installations/new");
		OAuthStartResponse githubStart = service.startGithub(student.getId(), projectId, null);
		assertEquals("https://github.com/apps/saga/installations/new", githubStart.authorizationUrl());
		assertEquals("state", githubStart.state());
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.JIRA_TEAM_CONNECT),
						isNull(),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(state(OAuthFlowType.JIRA_TEAM_CONNECT));
		when(properties.getJira()).thenReturn(new IntegrationProperties.Jira());
		when(jira.authorizationUrl(any(), any(), any(), eq(true))).thenReturn("https://auth.atlassian.com/authorize");
		OAuthStartResponse jiraStart = service.startJira(student.getId(), projectId, "https://evil.example/phish");
		assertEquals("https://auth.atlassian.com/authorize", jiraStart.authorizationUrl());
	}

	@Test
	void teamWithoutProjectIsDeniedCleanly() {
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.empty());
		IntegrationException github = assertThrows(
				IntegrationException.class, () -> service.startGithub(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, github.getCode());
		IntegrationException jiraEx =
				assertThrows(IntegrationException.class, () -> service.startJira(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, jiraEx.getCode());
	}

	@Test
	void unsafeReturnPathIsIgnoredAndNotStored() {
		assertEquals("/projects", ProjectIntegrationService.safeReturnPath("/projects"));
		assertNull(ProjectIntegrationService.safeReturnPath(null));
		assertNull(ProjectIntegrationService.safeReturnPath("https://evil.example"));
		assertNull(ProjectIntegrationService.safeReturnPath("//evil.example"));
		assertNull(ProjectIntegrationService.safeReturnPath("projects"));
	}

	private void assertDenied(SummaryCall call) {
		IntegrationException ex = assertThrows(IntegrationException.class, () -> call.run(student.getId(), projectId));
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
	}

	private void assertDenied(Runnable call) {
		IntegrationException ex = assertThrows(IntegrationException.class, call::run);
		assertEquals(IntegrationErrorCode.INTEGRATION_FORBIDDEN, ex.getCode());
	}

	private TeamMember membership(RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setId(UUID.randomUUID());
		member.setTeam(team);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private OAuthState state(OAuthFlowType flow) {
		return new OAuthState("state", student.getId(), flow, null, projectId, team.getId(), "verifier", Instant.now());
	}

	private static UserAccount account(AccountRole role, String email) {
		UserAccount account = new UserAccount();
		account.setId(UUID.randomUUID());
		account.setEmail(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	@FunctionalInterface
	private interface SummaryCall {
		void run(UUID userId, UUID projectId);
	}
}

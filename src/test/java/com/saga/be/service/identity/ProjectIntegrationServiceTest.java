package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.RepositoryRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.project.Project;
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
import org.mockito.ArgumentCaptor;
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
	void completeGithubInstallationProgressesBeyondGetInstallation() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("Saga-Learning-to-Hero", "Organization"),
						"https://github.com/settings/installations/158866076",
						"selected"));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.empty());
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> {
			GithubInstallation saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});

		String target = service.completeGithubInstallation(student.getId(), "state", 158866076L, null);

		assertEquals("http://localhost:3000/integrations/success", target);
		verify(installations).save(any(GithubInstallation.class));
		verify(audit)
				.record(
						eq(student),
						eq(project),
						eq(team),
						eq("GITHUB_INSTALLATION_CONNECTED"),
						eq("github_installation"),
						any(),
						any(),
						any(),
						any(),
						eq(com.saga.be.entity.enums.AuditSource.OAUTH),
						isNull(),
						isNull(),
						isNull());
	}

	@Test
	void auditLazyCourseFailurePropagatesFromCallback() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("Saga-Learning-to-Hero", "Organization"),
						"https://github.com/settings/installations/158866076",
						"selected"));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		when(properties.getGithub()).thenReturn(githubProps);
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.empty());
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> {
			GithubInstallation saved = invocation.getArgument(0);
			saved.setId(UUID.randomUUID());
			return saved;
		});
		when(audit.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
				.thenThrow(new org.hibernate.LazyInitializationException("could not initialize proxy [Course]"));

		org.hibernate.LazyInitializationException ex = assertThrows(
				org.hibernate.LazyInitializationException.class,
				() -> service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
		assertEquals("could not initialize proxy [Course]", ex.getMessage());
	}

	@Test
	void completeGithubInstallationKeepsPersistAndAuditInOneTransaction() throws Exception {
		var complete = ProjectIntegrationService.class.getMethod(
				"completeGithubInstallation", UUID.class, String.class, Long.class, String.class);
		var persist = ProjectIntegrationService.class.getDeclaredMethod(
				"persistVerifiedInstallation",
				UUID.class,
				UUID.class,
				Long.class,
				GitHubOAuthClient.GitHubInstallationResponse.class);
		assertEquals(
				org.springframework.transaction.annotation.Transactional.class,
				complete.getAnnotation(org.springframework.transaction.annotation.Transactional.class).annotationType());
		assertEquals(
				org.springframework.transaction.annotation.Transactional.class,
				persist.getAnnotation(org.springframework.transaction.annotation.Transactional.class).annotationType());
	}

	@Test
	void selectGithubReposPersistsFrontendBackendAndOtherRoles() {
		stubSelectGithubRepos(
				repo(1_338_790_015L, "saga-fe"),
				repo(1_339_720_224L, "saga-be"),
				repo(99L, "other"));
		service.selectGithubRepos(
				student.getId(),
				projectId,
				List.of(
						new SelectGitHubRepositoryRequest(1_338_790_015L, RepositoryRole.FRONTEND),
						new SelectGitHubRepositoryRequest(1_339_720_224L, RepositoryRole.BACKEND),
						new SelectGitHubRepositoryRequest(99L, RepositoryRole.OTHER)));
		ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
		verify(repos, times(3)).save(captor.capture());
		assertEquals(RepositoryRole.FRONTEND, captor.getAllValues().get(0).getRepositoryRole());
		assertEquals(RepositoryRole.BACKEND, captor.getAllValues().get(1).getRepositoryRole());
		assertEquals(RepositoryRole.OTHER, captor.getAllValues().get(2).getRepositoryRole());
	}

	@Test
	void selectGithubReposOmitsRoleWhenNull() {
		stubSelectGithubRepos(repo(1_338_790_015L, "saga-fe"));
		service.selectGithubRepos(
				student.getId(),
				projectId,
				List.of(new SelectGitHubRepositoryRequest(1_338_790_015L, null)));
		ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
		verify(repos).save(captor.capture());
		assertNull(captor.getValue().getRepositoryRole());
	}

	@Test
	void selectGithubReposRejectsUnknownRepositoryId() {
		stubSelectGithubRepos(repo(1_338_790_015L, "saga-fe"));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.selectGithubRepos(
						student.getId(),
						projectId,
						List.of(new SelectGitHubRepositoryRequest(404L, RepositoryRole.FRONTEND))));
		assertEquals(IntegrationErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE, ex.getCode());
		verify(repos, never()).save(any());
	}

	@Test
	void selectGithubReposAcceptsTwoRepositoriesTogether() {
		stubSelectGithubRepos(repo(1_338_790_015L, "saga-fe"), repo(1_339_720_224L, "saga-be"));
		service.selectGithubRepos(
				student.getId(),
				projectId,
				List.of(
						new SelectGitHubRepositoryRequest(1_338_790_015L, RepositoryRole.FRONTEND),
						new SelectGitHubRepositoryRequest(1_339_720_224L, RepositoryRole.BACKEND)));
		verify(repos, times(2)).save(any(GitRepo.class));
	}

	@Test
	void selectGithubReposEmptyListStillStartsSyncWithoutSavingRepos() {
		stubSelectGithubRepos(repo(1_338_790_015L, "saga-fe"));
		service.selectGithubRepos(student.getId(), projectId, List.of());
		verify(repos, never()).save(any());
		verify(syncJobs).save(any());
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

	private void stubSelectGithubRepos(GitHubOAuthClient.RepoSummary... accessible) {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158868603L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.of(installation));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.createInstallationToken("app-jwt", 158868603L)).thenReturn("inst-token");
		when(github.listInstallationRepos("inst-token")).thenReturn(null);
		when(github.parseRepos(null)).thenReturn(List.of(accessible));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		lenient().when(repos.findByProviderAndRepositoryId(eq(GitProvider.GITHUB), any())).thenReturn(Optional.empty());
		lenient().when(repos.save(any(GitRepo.class))).thenAnswer(invocation -> {
			GitRepo saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
	}

	private static GitHubOAuthClient.RepoSummary repo(long id, String name) {
		return new GitHubOAuthClient.RepoSummary(
				id, name, "Saga-Learning-to-Hero/" + name, "Saga-Learning-to-Hero", "main", false);
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

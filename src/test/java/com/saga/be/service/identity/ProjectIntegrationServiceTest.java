package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.dto.integration.ProjectIntegrationsResponse;
import com.saga.be.dto.integration.SelectGitHubRepositoryRequest;
import com.saga.be.dto.integration.SelectJiraIntegrationRequest;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.RepositoryRole;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.integration.IdentityMap;
import com.saga.be.entity.jira.JiraIntegration;
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
import com.saga.be.integration.oauth.PendingJiraClaim;
import com.saga.be.integration.oauth.PendingJiraConnect;
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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
	private com.saga.be.integration.oauth.GithubReconnectCandidateStore reconnectCandidates;
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
	@Mock
	private PlatformTransactionManager transactionManager;
	@Mock
	private com.saga.be.service.sync.IntegrationInitialSyncLauncher initialSyncLauncher;
	@Mock
	private JiraTaskProjectionHardReset taskProjectionReset;
	@Mock
	private com.saga.be.service.jira.JiraDynamicWebhookService jiraWebhooks;

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
		lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		lenient().when(taskProjectionReset.protectedEvidenceExists(any())).thenReturn(false);
		lenient().when(repos.findByProject_IdWithInstallation(any())).thenReturn(List.of());
		lenient().when(reconnectCandidates.find(any(), any())).thenReturn(List.of());
		lenient().when(properties.getOauthStateTtl()).thenReturn(Duration.ofMinutes(10));
		lenient()
				.when(installations.findByInstallationIdForUpdate(any()))
				.thenAnswer(invocation -> installations.findByInstallationId(invocation.getArgument(0)));
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
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any(),
						isNull()))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						"/projects",
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						null));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?discover=1");
		OAuthStartResponse githubStart = service.startGithub(student.getId(), projectId, "/projects");
		assertEquals("https://github.com/login/oauth/authorize?discover=1", githubStart.authorizationUrl());
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
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any(),
						isNull()))
				.thenReturn(new OAuthState(
						"state",
						admin.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						null));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize");
		assertDoesNotThrow(() -> service.startGithub(admin.getId(), projectId, null));
	}

	@Test
	void omittedReturnPathStillStartsGithubAndJiraForActiveLeader() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						isNull(),
						eq(projectId),
						eq(team.getId()),
						any(),
						isNull()))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						null));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?omit=1");
		OAuthStartResponse githubStart = service.startGithub(student.getId(), projectId, null);
		assertEquals("https://github.com/login/oauth/authorize?omit=1", githubStart.authorizationUrl());
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
	void persistPhaseMethodsAreNotIndependentlyTransactional() throws Exception {
		var complete = ProjectIntegrationService.class.getMethod(
				"completeGithubInstallation", UUID.class, String.class, Long.class, String.class);
		var persist = ProjectIntegrationService.class.getDeclaredMethod(
				"persistVerifiedInstallation",
				UUID.class,
				UUID.class,
				Long.class,
				GitHubOAuthClient.GitHubInstallationResponse.class);
		var reposPersist = ProjectIntegrationService.class.getDeclaredMethod(
				"persistSelectedRepos",
				UUID.class,
				UUID.class,
				GithubInstallation.class,
				List.class,
				List.class);
		var jiraPersist = ProjectIntegrationService.class.getDeclaredMethod(
				"persistJiraIntegration",
				UUID.class,
				UUID.class,
				JiraOAuthClient.AccessibleResource.class,
				JiraOAuthClient.JiraProjectResponse.class,
				String.class);
		assertNull(complete.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
		assertNull(persist.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
		assertNull(reposPersist.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
		assertNull(jiraPersist.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
		assertTrue(List.of(ProjectIntegrationService.class.getConstructors()[0].getParameterTypes())
				.contains(PlatformTransactionManager.class));
	}

	@Test
	void completeGithubInstallationLeavesDbUnchangedWhenProviderFails() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L)).thenReturn(null);
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, ex.getCode());
		verify(installations, never()).save(any());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void completeGithubInstallationRevalidatesLeaderAfterProviderHttp() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId()))
				.thenReturn(List.of(leaderMember))
				.thenReturn(List.of(memberRow));
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

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, ex.getCode());
		verify(installations, never()).save(any());
		verify(audit, never())
				.record(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void selectGithubReposRevalidatesLeaderAfterProviderHttp() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId()))
				.thenReturn(List.of(leaderMember))
				.thenReturn(List.of(memberRow));
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158868603L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.of(installation));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.createInstallationToken("app-jwt", 158868603L)).thenReturn("inst-token");
		when(github.listInstallationRepos("inst-token")).thenReturn(null);
		when(github.parseRepos(null)).thenReturn(List.of(repo(1_338_790_015L, "saga-fe")));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.selectGithubRepos(
						student.getId(),
						projectId,
						List.of(new SelectGitHubRepositoryRequest(1_338_790_015L, RepositoryRole.FRONTEND))));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, ex.getCode());
		verify(repos, never()).save(any());
	}

	@Test
	void saveJiraSelectionRevalidatesLeaderAfterProviderHttp() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId()))
				.thenReturn(List.of(leaderMember))
				.thenReturn(List.of(memberRow));
		PendingJiraConnect pending = new PendingJiraConnect(
				student.getId(), projectId, "jira-access", null, "read:jira-work", Instant.now());
		when(pendingJira.get(student.getId(), projectId)).thenReturn(Optional.of(pending));
		lenient()
				.when(pendingJira.claim(student.getId(), projectId))
				.thenReturn(Optional.of(new PendingJiraClaim(pending, Instant.now().plus(Duration.ofMinutes(10)), "opaque")));
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", null)));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, ex.getCode());
		verify(jiraIntegrations, never()).save(any());
		verify(pendingJira, never()).claim(any(), any());
	}

	@Test
	void persistPhaseKeepsAdminBypassAfterProviderHttp() {
		when(users.findById(admin.getId())).thenReturn(Optional.of(admin));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of());
		when(oauthStates.consumeForUser(eq("state"), eq(admin.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(new OAuthState(
						"state", admin.getId(), OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, null, projectId, team.getId(), "verifier", Instant.now()));
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
			saved.setId(UUID.randomUUID());
			return saved;
		});

		String target = service.completeGithubInstallation(admin.getId(), "state", 158866076L, null);

		assertEquals("http://localhost:3000/integrations/success", target);
		verify(installations).save(any(GithubInstallation.class));
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
		verify(initialSyncLauncher).enqueueGithubInitialSync(projectId);
		verify(initialSyncLauncher, never()).enqueueJiraInitialSync(any(), any());
	}

	@Test
	void saveJiraSelectionPersistsTypedRequestWithBoard() {
		stubJiraSelection();
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));
		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68"));
		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, times(1)).save(captor.capture());
		assertEquals("10067", captor.getValue().getJiraProjectId());
		assertEquals("SAGA", captor.getValue().getProjectKey());
		assertEquals("68", captor.getValue().getJiraBoardId());
		assertEquals(IntegrationStatus.ACTIVE, captor.getValue().getConnectionStatus());
		verify(initialSyncLauncher).enqueueJiraInitialSync(eq(projectId), eq("jira-access"));
		verify(jiraWebhooks).ensureRegistered(eq(projectId), eq("jira-access"));
	}

	@Test
	void disconnectJiraSoftRevokesAndClearsCredentialsButKeepsSelection() {
		stubLeaderOnly();
		JiraIntegration existing = revokedReadyIntegration("cloud", "10067", "SAGA", "68");
		existing.setConnectionStatus(IntegrationStatus.ACTIVE);
		existing.setEncryptedAccessToken("enc-access");
		existing.setEncryptedRefreshToken("enc-refresh");
		existing.setTokenExpiresAt(java.time.LocalDateTime.now().plusHours(1));
		existing.setWebhookId("42");
		existing.setWebhookExpiresAt(java.time.LocalDateTime.now().plusDays(20));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(existing));
		when(jiraIntegrations.save(any(JiraIntegration.class))).thenAnswer(invocation -> invocation.getArgument(0));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));

		service.disconnectJira(student.getId(), projectId);

		verify(jiraWebhooks).unregisterIfPresent(existing, null);
		assertEquals(IntegrationStatus.REVOKED, existing.getConnectionStatus());
		assertNull(existing.getEncryptedAccessToken());
		assertNull(existing.getEncryptedRefreshToken());
		assertNull(existing.getTokenExpiresAt());
		assertEquals("cloud", existing.getCloudId());
		assertEquals("SAGA", existing.getProjectKey());
		assertEquals("68", existing.getJiraBoardId());
	}

	@Test
	void revokedJiraReconnectSameSelectionBecomesActive() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));

		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68"));

		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		JiraIntegration saved = captor.getValue();
		assertEquals(IntegrationStatus.ACTIVE, saved.getConnectionStatus());
		assertEquals("10067", saved.getJiraProjectId());
		assertEquals("SAGA", saved.getProjectKey());
		assertEquals("68", saved.getJiraBoardId());
		assertNull(saved.getLastErrorCode());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(saved));
		ProjectIntegrationsResponse summary = service.summary(student.getId(), projectId);
		assertEquals("ACTIVE", summary.jira().status());
	}

	@Test
	void boardOnlyChangeDoesNotWipeTasks() {
		JiraIntegration existing = revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68");
		existing.setConnectionStatus(IntegrationStatus.ACTIVE);
		stubJiraSelectionWithExisting(existing);
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "99"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("99", "Other board", "simple"));

		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "99"));

		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		assertEquals("99", captor.getValue().getJiraBoardId());
		assertEquals(IntegrationStatus.ACTIVE, captor.getValue().getConnectionStatus());
	}

	@Test
	void revokedJiraReconnectDifferentSelectionBecomesActive() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "99"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("99", "Other board", "simple"));

		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", "99"));

		verify(taskProjectionReset).hardDeleteAllTasksForProject(projectId);
		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		JiraIntegration saved = captor.getValue();
		assertEquals(IntegrationStatus.ACTIVE, saved.getConnectionStatus());
		assertEquals("20001", saved.getJiraProjectId());
		assertEquals("OTHER", saved.getProjectKey());
		assertEquals("99", saved.getJiraBoardId());
		verify(initialSyncLauncher).enqueueJiraInitialSync(eq(projectId), eq("jira-access"));
	}

	@Test
	void differentSourceSameProjectKeyIsRejectedWithoutMutation() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"cloud-a", "10067", "SAGA", "68"));
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"cloud-b", "https://other.atlassian.net", "Other")));
		when(jira.getProject("jira-access", "cloud-b", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "saga", "Same key elsewhere"));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(), projectId, new SelectJiraIntegrationRequest("cloud-b", "20001", null)));
		assertEquals(IntegrationErrorCode.JIRA_PROJECT_KEY_AMBIGUOUS, ex.getCode());
		assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
		verify(pendingJira, never()).claim(any(), any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		verify(jiraIntegrations, never()).save(any());
		verify(initialSyncLauncher, never()).enqueueJiraInitialSync(any(), any());
	}

	@Test
	void differentSourceWithContributionConfirmationEvidenceIsRejectedWithoutMutation() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		when(taskProjectionReset.protectedEvidenceExists(projectId)).thenReturn(true);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", null)));
		assertEquals(IntegrationErrorCode.JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE, ex.getCode());
		verify(pendingJira, never()).claim(any(), any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		verify(jiraIntegrations, never()).save(any());
	}

	@Test
	void differentSourceWithWorkSessionEvidenceIsRejectedWithoutMutation() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		when(taskProjectionReset.protectedEvidenceExists(projectId)).thenReturn(true);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", null)));
		assertEquals(IntegrationErrorCode.JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE, ex.getCode());
		assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
		verify(pendingJira, never()).claim(any(), any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		verify(jiraIntegrations, never()).save(any());
	}

	@Test
	void concurrentProtectedEvidenceDuringHardDeleteMapsToConflictAndSkipsClaim() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("fk_work_session_task"))
				.when(taskProjectionReset)
				.hardDeleteAllTasksForProject(projectId);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", null)));
		assertEquals(IntegrationErrorCode.JIRA_SOURCE_REPLACE_BLOCKED_BY_EVIDENCE, ex.getCode());
		verify(pendingJira, never()).claim(any(), any());
		verify(jiraIntegrations, never()).save(any());
	}

	@Test
	void successfulReplaceThenSyncEnqueueFailureKeepsNewActiveSource() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		org.mockito.Mockito.doThrow(new RuntimeException("sync broker down"))
				.when(initialSyncLauncher)
				.enqueueJiraInitialSync(eq(projectId), eq("jira-access"));

		RuntimeException ex = assertThrows(
				RuntimeException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", null)));
		assertEquals("sync broker down", ex.getMessage());
		verify(taskProjectionReset).hardDeleteAllTasksForProject(projectId);
		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		assertEquals(IntegrationStatus.ACTIVE, captor.getValue().getConnectionStatus());
		assertEquals("20001", captor.getValue().getJiraProjectId());
		assertEquals("OTHER", captor.getValue().getProjectKey());
		verify(pendingJira, never()).restoreIfAbsent(any());
	}

	@Test
	void dbFailureDuringReplacementRestoresPendingClaim() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20001", "OTHER", "Other Project"));
		when(jiraIntegrations.save(any(JiraIntegration.class))).thenThrow(new RuntimeException("db write failed"));

		RuntimeException ex = assertThrows(
				RuntimeException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "20001", null)));
		assertEquals("db write failed", ex.getMessage());
		verify(taskProjectionReset).hardDeleteAllTasksForProject(projectId);
		verify(pendingJira).claim(student.getId(), projectId);
		verify(pendingJira).restoreIfAbsent(any(PendingJiraClaim.class));
		verify(initialSyncLauncher, never()).enqueueJiraInitialSync(any(), any());
		verify(transactionManager).rollback(any());
	}

	@Test
	void sameSourceDbFailureRestoresPendingClaimWithoutTaskWipe() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));
		when(jiraIntegrations.save(any(JiraIntegration.class))).thenThrow(new RuntimeException("db write failed"));

		assertThrows(
				RuntimeException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68")));
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		verify(pendingJira).claim(student.getId(), projectId);
		verify(pendingJira).restoreIfAbsent(any(PendingJiraClaim.class));
		verify(initialSyncLauncher, never()).enqueueJiraInitialSync(any(), any());
	}

	@Test
	void successfulSameSourceCommitDoesNotRestorePending() {
		stubJiraSelectionWithExisting(revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68"));
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));

		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68"));

		verify(pendingJira).claim(student.getId(), projectId);
		verify(pendingJira, never()).restoreIfAbsent(any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
	}

	@Test
	void revokedJiraRemainsRevokedWhenReconnectOAuthCancelled() {
		JiraIntegration existing = revokedReadyIntegration("cloud", "10067", "SAGA", "68");
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenReturn(stateWithReturn(OAuthFlowType.JIRA_TEAM_CONNECT, "/projects/123/integrations"));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeJiraTeamCallback(student.getId(), null, "state", "access_denied"));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CANCELLED, ex.getCode());
		verify(jira, never()).exchange(any(), any(), any());
		verify(jiraIntegrations, never()).save(any());
		assertEquals(IntegrationStatus.REVOKED, existing.getConnectionStatus());
	}

	@Test
	void activeJiraRemainsActiveWhenReauthCancelled() {
		JiraIntegration existing = revokedReadyIntegration("cloud", "10067", "SAGA", "68");
		existing.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenReturn(stateWithReturn(OAuthFlowType.JIRA_TEAM_CONNECT, null));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeJiraTeamCallback(student.getId(), null, "state", "access_denied"));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CANCELLED, ex.getCode());
		verify(jiraIntegrations, never()).save(any());
		assertEquals(IntegrationStatus.ACTIVE, existing.getConnectionStatus());
	}

	@Test
	void failedSelectionDoesNotConsumePendingOrActivateRevokedRow() {
		JiraIntegration existing = revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68");
		stubJiraLeaderAndPendingPeekOnly();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067")).thenReturn(null);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68")));
		assertEquals(IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, ex.getCode());
		verify(pendingJira, never()).claim(any(), any());
		verify(jiraIntegrations, never()).save(any());
		assertEquals(IntegrationStatus.REVOKED, existing.getConnectionStatus());
	}

	@Test
	void reconnectDoesNotCreateDuplicateJiraIntegrationForSameProject() {
		JiraIntegration existing = revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68");
		stubJiraSelectionWithExisting(existing);
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));

		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68"));

		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		assertEquals(existing.getId(), captor.getValue().getId());
		assertEquals(IntegrationStatus.ACTIVE, captor.getValue().getConnectionStatus());
	}

	@Test
	void saveJiraSelectionAllowsOmittedBoard() {
		stubJiraSelection();
		service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", null));
		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations).save(captor.capture());
		assertNull(captor.getValue().getJiraBoardId());
		verify(jira, never()).getBoard(any(), any(), any());
	}

	@Test
	void saveJiraSelectionAllowsSameProjectKeyOnDifferentCloud() {
		stubJiraLeaderAndPending();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "https://other.atlassian.net", "Other")));
		when(jira.getProject("jira-access", "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "20002"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("20002", "SAGA", "Other site SAGA"));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(jiraIntegrations.findByCloudIdAndJiraProjectId(
						"bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "20002"))
				.thenReturn(Optional.empty());
		when(jiraIntegrations.save(any(JiraIntegration.class))).thenAnswer(invocation -> {
			JiraIntegration saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});

		assertDoesNotThrow(() -> service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", "20002", null)));

		ArgumentCaptor<JiraIntegration> captor = ArgumentCaptor.forClass(JiraIntegration.class);
		verify(jiraIntegrations, atLeastOnce()).save(captor.capture());
		assertEquals("SAGA", captor.getValue().getProjectKey());
		assertEquals("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb", captor.getValue().getCloudId());
		verify(pendingJira).claim(student.getId(), projectId);
	}

	@Test
	void saveJiraSelectionRejectsCloudProjectAlreadyBoundToAnotherSagaProject() {
		stubJiraLeaderAndPending();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		UUID otherProjectId = UUID.randomUUID();
		Project other = new Project();
		other.setId(otherProjectId);
		JiraIntegration ownedElsewhere = new JiraIntegration();
		ownedElsewhere.setId(UUID.randomUUID());
		ownedElsewhere.setProject(other);
		ownedElsewhere.setCloudId("aeb21465-f2da-4923-b356-f6f1cfa4fd13");
		ownedElsewhere.setJiraProjectId("10067");
		ownedElsewhere.setProjectKey("SAGA");
		ownedElsewhere.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findByCloudIdAndJiraProjectId(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(Optional.of(ownedElsewhere));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", null)));

		assertEquals(IntegrationErrorCode.JIRA_PROJECT_IN_USE, ex.getCode());
		assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
		assertEquals("Jira project này đang được liên kết với một dự án SAGA khác.", ex.getMessage());
		verify(pendingJira, never()).claim(any(), any());
		verify(jiraIntegrations, never()).save(any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
	}

	@Test
	void saveJiraSelectionMapsConcurrentUkJiraCloudProjectToConflict() {
		stubJiraSelection();
		when(jiraIntegrations.findByCloudIdAndJiraProjectId(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(Optional.empty());
		when(jiraIntegrations.save(any(JiraIntegration.class)))
				.thenThrow(new org.springframework.dao.DataIntegrityViolationException(
						"Duplicate entry for key 'uk_jira_cloud_project'"));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", null)));

		assertEquals(IntegrationErrorCode.JIRA_PROJECT_IN_USE, ex.getCode());
		assertEquals(org.springframework.http.HttpStatus.CONFLICT, ex.getStatus());
		verify(pendingJira).claim(student.getId(), projectId);
		verify(pendingJira).restoreIfAbsent(any());
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
	}

	@Test
	void saveJiraSelectionAllowsSameSagaProjectToReconnectSameJiraSource() {
		JiraIntegration existing = revokedReadyIntegration(
				"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "SAGA", "68");
		existing.setConnectionStatus(IntegrationStatus.ACTIVE);
		stubJiraSelectionWithExisting(existing);
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68"))
				.thenReturn(new JiraOAuthClient.JiraBoardResponse("68", "SAGA board", "simple"));

		assertDoesNotThrow(() -> service.saveJiraSelection(
				student.getId(),
				projectId,
				new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68")));

		verify(jiraIntegrations, atLeastOnce()).save(any(JiraIntegration.class));
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
	}

	@Test
	void saveJiraSelectionConflictDoesNotMutateTasksOrExistingOwner() {
		stubJiraLeaderAndPending();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		UUID otherProjectId = UUID.randomUUID();
		Project other = new Project();
		other.setId(otherProjectId);
		JiraIntegration ownedElsewhere = new JiraIntegration();
		ownedElsewhere.setId(UUID.randomUUID());
		ownedElsewhere.setProject(other);
		ownedElsewhere.setCloudId("aeb21465-f2da-4923-b356-f6f1cfa4fd13");
		ownedElsewhere.setJiraProjectId("10067");
		ownedElsewhere.setProjectKey("SAGA");
		ownedElsewhere.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(jiraIntegrations.findByCloudIdAndJiraProjectId(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(Optional.of(ownedElsewhere));

		assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", null)));

		assertEquals(other, ownedElsewhere.getProject());
		assertEquals(IntegrationStatus.ACTIVE, ownedElsewhere.getConnectionStatus());
		verify(jiraIntegrations, never()).save(ownedElsewhere);
		verify(taskProjectionReset, never()).hardDeleteAllTasksForProject(any());
		verify(pendingJira, never()).claim(any(), any());
	}

	@Test
	void saveJiraSelectionRejectsInaccessibleProject() {
		stubJiraLeaderAndPendingPeekOnly();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067")).thenReturn(null);
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68")));
		assertEquals(IntegrationErrorCode.JIRA_PROJECT_NOT_ACCESSIBLE, ex.getCode());
		verify(pendingJira, never()).claim(any(), any());
	}

	@Test
	void saveJiraSelectionRejectsInaccessibleBoard() {
		stubJiraLeaderAndPendingPeekOnly();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		when(jira.getBoard("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "68")).thenReturn(null);
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.saveJiraSelection(
						student.getId(),
						projectId,
						new SelectJiraIntegrationRequest("aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067", "68")));
		assertEquals(IntegrationErrorCode.JIRA_BOARD_NOT_ACCESSIBLE, ex.getCode());
		verify(pendingJira, never()).claim(any(), any());
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

	@Test
	void githubCallbackResolvesSafeReturnPathAgainstFrontendSuccessOrigin() {
		stubGithubCallbackSuccess(stateWithReturn("/projects/123/integrations"));
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		assertEquals(
				"http://localhost:3000/projects/123/integrations",
				service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
	}

	@Test
	void githubCallbackWithoutReturnPathUsesSuccessUrl() {
		stubGithubCallbackSuccess(state(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY));
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		assertEquals(
				"http://localhost:3000/integrations/success",
				service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
	}

	@Test
	void githubCallbackIgnoresUnsafeReturnPathAndUsesSuccessUrl() {
		stubGithubCallbackSuccess(stateWithReturn("//evil.example"));
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		assertEquals(
				"http://localhost:3000/integrations/success",
				service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
	}

	@Test
	void githubCallbackResolvesAgainstProductionFrontendOrigin() {
		stubGithubCallbackSuccess(stateWithReturn("/projects/123"));
		when(properties.getSuccessUrl()).thenReturn("https://app.saga.vn/integrations/success");
		assertEquals(
				"https://app.saga.vn/projects/123",
				service.completeGithubInstallation(student.getId(), "state", 158866076L, null));
	}

	@Test
	void jiraTeamCallbackResolvesSafeReturnPathAgainstFrontendSuccessOrigin() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenReturn(stateWithReturn(OAuthFlowType.JIRA_TEAM_CONNECT, "/projects/123/integrations"));
		when(properties.getJira()).thenReturn(new IntegrationProperties.Jira());
		when(properties.getOauthStateTtl()).thenReturn(java.time.Duration.ofMinutes(10));
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(properties.getPublicBaseUrl()).thenReturn("https://saga-be-production.up.railway.app");
		when(jira.exchange(eq("code"), eq("verifier"), any()))
				.thenReturn(new JiraOAuthClient.TokenResponse("access", "refresh", 3600, "read:jira-work"));
		when(jira.accessibleResources("access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource("cloud", "https://ex.atlassian.net", "Ex")));
		when(jira.myself("access", "cloud"))
				.thenReturn(new JiraOAuthClient.Myself("acct-1", "Leader", "leader@gmail.com", null));
		IdentityMap identity = new IdentityMap();
		identity.setExternalAccountId("acct-1");
		identity.setMappingStatus(com.saga.be.entity.enums.IdentityMappingStatus.ACTIVE);
		when(identities.findByUserAccount_IdAndProvider(student.getId(), com.saga.be.entity.enums.IntegrationProvider.JIRA))
				.thenReturn(List.of(identity));
		assertEquals(
				"http://localhost:3000/projects/123/integrations",
				service.completeJiraTeamCallback(student.getId(), "code", "state"));
	}

	@Test
	void jiraTeamAccessDeniedConsumesStateAndDoesNotExchangeOrPersistPending() {
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenReturn(stateWithReturn(OAuthFlowType.JIRA_TEAM_CONNECT, "/projects/123/integrations"));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeJiraTeamCallback(student.getId(), null, "state", "access_denied"));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CANCELLED, ex.getCode());
		verify(oauthStates).consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT));
		verify(jira, never()).exchange(any(), any(), any());
		verify(pendingJira, never()).save(any(), any());
		verify(jiraIntegrations, never()).save(any());
	}

	@Test
	void jiraTeamRejectsPersonalFlowStateOnCancel() {
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenThrow(new IntegrationException(
						IntegrationErrorCode.OAUTH_STATE_INVALID,
						org.springframework.http.HttpStatus.BAD_REQUEST,
						"wrong flow"));
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeJiraTeamCallback(student.getId(), null, "state", "access_denied"));
		assertEquals(IntegrationErrorCode.OAUTH_STATE_INVALID, ex.getCode());
		verify(jira, never()).exchange(any(), any(), any());
		verify(pendingJira, never()).save(any(), any());
	}

	@Test
	void jiraTeamMissingCodeWithoutErrorDoesNotExchange() {
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.JIRA_TEAM_CONNECT)))
				.thenReturn(stateWithReturn(OAuthFlowType.JIRA_TEAM_CONNECT, null));
		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> service.completeJiraTeamCallback(student.getId(), null, "state", null));
		assertEquals(IntegrationErrorCode.JIRA_OAUTH_CALLBACK_INVALID, ex.getCode());
		verify(jira, never()).exchange(any(), any(), any());
	}

	private void stubJiraLeaderAndPending() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		PendingJiraConnect pending = new PendingJiraConnect(
				student.getId(), projectId, "jira-access", null, "read:jira-work", Instant.now());
		when(pendingJira.get(student.getId(), projectId)).thenReturn(Optional.of(pending));
		lenient()
				.when(pendingJira.claim(student.getId(), projectId))
				.thenReturn(Optional.of(new PendingJiraClaim(pending, Instant.now().plus(Duration.ofMinutes(10)), "opaque")));
	}

	private void stubJiraLeaderAndPendingPeekOnly() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		PendingJiraConnect pending = new PendingJiraConnect(
				student.getId(), projectId, "jira-access", null, "read:jira-work", Instant.now());
		when(pendingJira.get(student.getId(), projectId)).thenReturn(Optional.of(pending));
	}

	private void stubLeaderOnly() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
	}

	private void stubJiraSelection() {
		stubJiraLeaderAndPending();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		lenient()
				.when(jiraIntegrations.findByCloudIdAndJiraProjectId(any(), any()))
				.thenReturn(Optional.empty());
		lenient().when(jiraIntegrations.save(any(JiraIntegration.class))).thenAnswer(invocation -> {
			JiraIntegration saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
	}

	private void stubJiraSelectionWithExisting(JiraIntegration existing) {
		stubJiraLeaderAndPending();
		when(jira.accessibleResources("jira-access"))
				.thenReturn(List.of(new JiraOAuthClient.AccessibleResource(
						"aeb21465-f2da-4923-b356-f6f1cfa4fd13", "https://example.atlassian.net", "Saga")));
		lenient()
				.when(jira.getProject("jira-access", "aeb21465-f2da-4923-b356-f6f1cfa4fd13", "10067"))
				.thenReturn(new JiraOAuthClient.JiraProjectResponse("10067", "SAGA", "Saga Learning to Hero"));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(jiraIntegrations.findByProject_Id(projectId)).thenReturn(Optional.of(existing));
		when(jiraIntegrations.lockById(existing.getId())).thenReturn(Optional.of(existing));
		lenient()
				.when(jiraIntegrations.findByCloudIdAndJiraProjectId(any(), any()))
				.thenReturn(Optional.empty());
		lenient()
				.when(jiraIntegrations.findByCloudIdAndJiraProjectId(
						eq(existing.getCloudId()), eq(existing.getJiraProjectId())))
				.thenReturn(Optional.of(existing));
		lenient()
				.when(jiraIntegrations.save(any(JiraIntegration.class)))
				.thenAnswer(invocation -> invocation.getArgument(0));
		lenient().when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		lenient().when(repos.findByProject_Id(projectId)).thenReturn(List.of());
	}

	private JiraIntegration revokedReadyIntegration(
			String cloudId, String jiraProjectId, String projectKey, String boardId) {
		JiraIntegration existing = new JiraIntegration();
		existing.setId(UUID.randomUUID());
		existing.setCloudId(cloudId);
		existing.setSiteName("Saga");
		existing.setJiraProjectId(jiraProjectId);
		existing.setProjectKey(projectKey);
		existing.setJiraBoardId(boardId);
		existing.setConnectionStatus(IntegrationStatus.REVOKED);
		existing.setEncryptedAccessToken("stale-access");
		existing.setEncryptedRefreshToken(null);
		existing.setConsecutiveFailures(2);
		existing.setLastErrorCode("JIRA_SYNC_FAILED");
		existing.setVersion(3L);
		Project project = new Project();
		project.setId(projectId);
		existing.setProject(project);
		return existing;
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

	private OAuthState stateWithReturn(String returnPath) {
		return stateWithReturn(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, returnPath);
	}

	private OAuthState stateWithReturn(OAuthFlowType flow, String returnPath) {
		return new OAuthState(
				"state", student.getId(), flow, returnPath, projectId, team.getId(), "verifier", Instant.now());
	}

	private void stubGithubCallbackSuccess(OAuthState oauthState) {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(oauthState);
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
		when(installations.findByInstallationIdForUpdate(158866076L)).thenReturn(Optional.empty());
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> {
			GithubInstallation saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
	}

	@Test
	void startGithubWithRepoProvenanceUsesUserOAuthReconnectNotInstallationsNew() {
		GithubInstallation prior = suspendedInstallation(158866076L);
		GitRepo revoked = revokedRepo(projectId, prior, 99L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(revoked));
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.of(prior));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any(),
						eq(158866076L)))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						158866076L));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/api/integrations/github/oauth/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?reconnect=1");

		OAuthStartResponse start = service.startGithub(student.getId(), projectId, "/projects");

		assertEquals("https://github.com/login/oauth/authorize?reconnect=1", start.authorizationUrl());
		verify(github, never()).installationUrl(any());
	}

	@Test
	void startGithubWithoutRepoProvenanceUsesUserOAuthDiscoveryNotInstallationsNew() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId)).thenReturn(List.of());
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any(),
						isNull()))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						null));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?zero=1");

		OAuthStartResponse start = service.startGithub(student.getId(), projectId, null);

		assertEquals("https://github.com/login/oauth/authorize?zero=1", start.authorizationUrl());
		verify(github, never()).installationUrl(any());
		verify(installations, never()).findByInstallationId(any());
	}

	@Test
	void startGithubInstallNewModeReturnsInstallationsNewUrl() {
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
		when(github.installationUrl(any())).thenReturn("https://github.com/apps/saga/installations/new?state=abc");

		OAuthStartResponse start = service.startGithub(student.getId(), projectId, null, null, "install_new");

		assertEquals("https://github.com/apps/saga/installations/new?state=abc", start.authorizationUrl());
		verify(github, never()).authorizationUrl(any(), any(), any());
		verify(repos, never()).findByProject_IdWithInstallation(any());
		verify(repos, never()).delete(any());
	}

	@Test
	void recoverGithubReconnectCandidatesReturnsMultipleWithoutThrowing() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));
		assertEquals(Set.of(111L, 222L), service.recoverGithubReconnectCandidates(projectId));
		assertTrue(service.recoverGithubReconnectCandidate(projectId).isEmpty());
	}

	@Test
	void startGithubWithMultipleHistoricalInstallationsRequiresSelectionWithoutOAuthLoop() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));

		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> service.startGithub(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_SELECTION_REQUIRED, ex.getCode());
		verify(oauthStates, never()).start(any(), any(), any(), any(), any(), any(), any());
		verify(github, never()).authorizationUrl(any(), any(), any());
		verify(github, never()).installationUrl(any());
	}

	@Test
	void completeGithubReconnectWithTwoHistoricalBindsOnlyUserAccessibleCandidate() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(222L))));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 222L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(222L, 123456L, null, null, "selected"));
		when(installations.findByInstallationId(222L)).thenReturn(Optional.of(two));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(222L)).thenReturn(Optional.of(two));
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals("http://localhost:3000/integrations/success", target);
		assertEquals(GitHubInstallationStatus.ACTIVE, two.getInstallationStatus());
		assertEquals(project, two.getProject());
		verify(installations, never()).findByInstallationIdForUpdate(111L);
	}

	@Test
	void completeGithubReconnectExcludesCandidateBoundToAnotherProject() {
		UUID otherProjectId = UUID.randomUUID();
		Project other = new Project();
		other.setId(otherProjectId);
		GithubInstallation stolen = suspendedInstallation(111L);
		stolen.setProject(other);
		stolen.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GithubInstallation free = suspendedInstallation(222L);
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, stolen, 1L), revokedRepo(projectId, free, 2L)));
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(List.of(
						new GitHubOAuthClient.GitHubInstallationIdResponse(111L),
						new GitHubOAuthClient.GitHubInstallationIdResponse(222L))));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 111L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(111L, 123456L, null, null, "selected"));
		when(github.getInstallation("app-jwt", 222L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(222L, 123456L, null, null, "selected"));
		when(installations.findByInstallationId(111L)).thenReturn(Optional.of(stolen));
		when(installations.findByInstallationId(222L)).thenReturn(Optional.of(free));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(222L)).thenReturn(Optional.of(free));
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals(project, free.getProject());
		assertEquals(other, stolen.getProject());
		verify(installations, never()).save(stolen);
	}

	@Test
	void completeGithubReconnectWithTwoEligibleRequiresSelectionAndStoresCandidates() {
		GithubInstallation one = suspendedInstallation(111L);
		one.setAccountLogin("org-a");
		one.setAccountType("Organization");
		GithubInstallation two = suspendedInstallation(222L);
		two.setAccountLogin("org-b");
		two.setAccountType("Organization");
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				"/projects",
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(List.of(
						new GitHubOAuthClient.GitHubInstallationIdResponse(111L),
						new GitHubOAuthClient.GitHubInstallationIdResponse(222L))));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 111L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						111L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("org-a", "Organization"),
						null,
						"selected"));
		when(github.getInstallation("app-jwt", 222L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						222L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("org-b", "Organization"),
						null,
						"selected"));
		when(installations.findByInstallationId(111L)).thenReturn(Optional.of(one));
		when(installations.findByInstallationId(222L)).thenReturn(Optional.of(two));

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertTrue(target.contains("GITHUB_INSTALLATION_SELECTION_REQUIRED"));
		assertTrue(target.startsWith("http://localhost:3000"));
		verify(reconnectCandidates).save(eq(student.getId()), eq(projectId), any(), any());
		verify(installations, never()).save(any());
	}

	@Test
	void completeGithubReconnectWithZeroEligibleRedirectsToInstallPath() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				"/projects",
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(List.of()));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY),
						eq("/projects"),
						eq(projectId),
						eq(team.getId()),
						any()))
				.thenReturn(stateWithReturn(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY, "/projects"));
		when(github.installationUrl(any())).thenReturn("https://github.com/apps/saga/installations/new");

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals("https://github.com/apps/saga/installations/new", target);
		verify(installations, never()).save(any());
	}

	@Test
	void startGithubRejectsSpoofedInstallationIdOutsideHistoricalProvenance() {
		GithubInstallation one = suspendedInstallation(111L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(revokedRepo(projectId, one, 1L)));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.startGithub(student.getId(), projectId, null, 999L));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, ex.getCode());
		verify(oauthStates, never()).start(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void startGithubWithSelectedHistoricalInstallationStartsReconnectOAuth() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, one, 1L), revokedRepo(projectId, two, 2L)));
		when(installations.findByInstallationId(222L)).thenReturn(Optional.of(two));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectId),
						eq(team.getId()),
						any(),
						eq(222L)))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectId,
						team.getId(),
						"verifier",
						Instant.now(),
						222L));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?pick=222");

		OAuthStartResponse start = service.startGithub(student.getId(), projectId, null, 222L);

		assertEquals("https://github.com/login/oauth/authorize?pick=222", start.authorizationUrl());
	}

	@Test
	void listGithubReconnectCandidatesKeepsRevokedRepoHistoryIntact() {
		GithubInstallation one = suspendedInstallation(111L);
		GithubInstallation two = suspendedInstallation(222L);
		one.setAccountLogin("org-a");
		two.setAccountLogin("org-b");
		GitRepo repoOne = revokedRepo(projectId, one, 1L);
		GitRepo repoTwo = revokedRepo(projectId, two, 2L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId)).thenReturn(List.of(repoOne, repoTwo));
		when(installations.findByInstallationId(111L)).thenReturn(Optional.of(one));
		when(installations.findByInstallationId(222L)).thenReturn(Optional.of(two));

		var candidates = service.listGithubReconnectCandidates(student.getId(), projectId);

		assertEquals(2, candidates.size());
		assertEquals(IntegrationStatus.REVOKED, repoOne.getConnectionStatus());
		assertEquals(IntegrationStatus.REVOKED, repoTwo.getConnectionStatus());
		verify(repos, never()).delete(any());
		verify(repos, never()).save(any());
	}

	@Test
	void listGithubReconnectCandidatesPrefersOAuthVerifiedPendingOverHistorical() {
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(reconnectCandidates.find(student.getId(), projectId))
				.thenReturn(List.of(new com.saga.be.dto.integration.GithubReconnectCandidateResponse(
						999L, "verified-org", "Organization")));

		var candidates = service.listGithubReconnectCandidates(student.getId(), projectId);

		assertEquals(1, candidates.size());
		assertEquals(999L, candidates.getFirst().installationId());
		verify(repos, never()).findByProject_IdWithInstallation(any());
	}

	@Test
	void completeGithubReconnectWithZeroProvenanceAndOneEligibleBindsWithoutUninstall() {
		when(repos.findByProject_IdWithInstallation(projectId)).thenReturn(List.of());
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		GithubInstallation free = suspendedInstallation(555L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(555L))));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 555L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						555L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("existing-org", "Organization"),
						null,
						"selected"));
		when(installations.findByInstallationId(555L)).thenReturn(Optional.of(free));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(555L)).thenReturn(Optional.of(free));
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals("http://localhost:3000/integrations/success", target);
		assertEquals(project, free.getProject());
		assertEquals(GitHubInstallationStatus.ACTIVE, free.getInstallationStatus());
		verify(reconnectCandidates).clear(student.getId(), projectId);
		verify(github, never()).installationUrl(any());
	}

	@Test
	void disconnectGithubRevokesReposSuspendsInstallationKeepsInstallationId() {
		Project project = new Project();
		project.setId(projectId);
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158866076L);
		installation.setProject(project);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GitRepo repo = revokedRepo(projectId, installation, 42L);
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(repos.findByProject_Id(projectId)).thenReturn(List.of(repo));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.of(installation));

		service.disconnectGithub(student.getId(), projectId);

		assertEquals(IntegrationStatus.REVOKED, repo.getConnectionStatus());
		assertEquals(GitHubInstallationStatus.SUSPENDED, installation.getInstallationStatus());
		assertNull(installation.getProject());
		assertEquals(158866076L, installation.getInstallationId());
		assertEquals(installation, repo.getInstallation());
	}

	@Test
	void completeGithubReconnectRebindsActiveWithMembershipAndAppVerify() {
		GithubInstallation prior = suspendedInstallation(158866076L);
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				158866076L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
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
		githubProps.setOauthCallbackUrl("http://localhost/api/integrations/github/oauth/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(158866076L))));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(158866076L)).thenReturn(Optional.of(prior));
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals("http://localhost:3000/integrations/success", target);
		assertEquals(GitHubInstallationStatus.ACTIVE, prior.getInstallationStatus());
		assertEquals(project, prior.getProject());
		verify(github, never()).installationUrl(any());
	}

	@Test
	void completeGithubReconnectRejectsWhenInstallationMissingAtProvider() {
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				158866076L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L)).thenReturn(null);

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubReconnect(student.getId(), "code", reconnectState));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, ex.getCode());
		verify(installations, never()).save(any());
	}

	@Test
	void completeGithubReconnectRejectsWhenUserLacksInstallationMembership() {
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				158866076L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L, 123456L, null, null, "selected"));
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(999L))));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubReconnect(student.getId(), "code", reconnectState));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_NOT_AUTHORIZED, ex.getCode());
		verify(installations, never()).save(any());
	}

	@Test
	void completeGithubReconnectRejectsInstallationBoundToAnotherProject() {
		UUID otherProjectId = UUID.randomUUID();
		Project other = new Project();
		other.setId(otherProjectId);
		GithubInstallation boundElsewhere = suspendedInstallation(158866076L);
		boundElsewhere.setProject(other);
		boundElsewhere.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				158866076L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.of(boundElsewhere));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubReconnect(student.getId(), "code", reconnectState));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_IN_USE, ex.getCode());
		verify(installations, never()).save(any());
	}

	@Test
	void startGithubRejectsReconnectCandidateAlreadyBoundToAnotherProject() {
		UUID otherProjectId = UUID.randomUUID();
		Project other = new Project();
		other.setId(otherProjectId);
		GithubInstallation boundElsewhere = suspendedInstallation(158866076L);
		boundElsewhere.setProject(other);
		boundElsewhere.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(repos.findByProject_IdWithInstallation(projectId))
				.thenReturn(List.of(revokedRepo(projectId, boundElsewhere, 1L)));
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.of(boundElsewhere));

		IntegrationException ex = assertThrows(
				IntegrationException.class, () -> service.startGithub(student.getId(), projectId, null));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_IN_USE, ex.getCode());
		verify(oauthStates, never()).start(any(), any(), any(), any(), any(), any(), any());
	}

	@Test
	void completeGithubReconnectWithoutStateInstallationUsesVerifiedUserListNotSuspendedGuess() {
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				null);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(158866076L))));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L, 123456L, null, null, "selected"));
		when(installations.findByInstallationId(158866076L)).thenReturn(Optional.empty());
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(158866076L)).thenReturn(Optional.empty());
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> {
			GithubInstallation saved = invocation.getArgument(0);
			saved.setId(UUID.randomUUID());
			return saved;
		});

		String target = service.completeGithubReconnect(student.getId(), "code", reconnectState);

		assertEquals("http://localhost:3000/integrations/success", target);
		verify(installations, never()).findByProject_Id(isNull());
		ArgumentCaptor<GithubInstallation> captor = ArgumentCaptor.forClass(GithubInstallation.class);
		verify(installations).save(captor.capture());
		assertEquals(158866076L, captor.getValue().getInstallationId());
	}

	@Test
	void completeGithubReconnectIgnoresClientSpoofedInstallationByUsingStateOnly() {
		// State carries 158866076; membership/App checks apply to that id only (not a client query param).
		OAuthState reconnectState = new OAuthState(
				"state",
				student.getId(),
				OAuthFlowType.GITHUB_TEAM_RECONNECT,
				null,
				projectId,
				team.getId(),
				"verifier",
				Instant.now(),
				158866076L);
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		when(github.exchangeUserToken(eq("code"), eq("verifier"), any())).thenReturn("user-token");
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L, 123456L, null, null, "selected"));
		when(github.listUserInstallations("user-token"))
				.thenReturn(new GitHubOAuthClient.GitHubUserInstallationsResponse(
						List.of(new GitHubOAuthClient.GitHubInstallationIdResponse(158866076L))));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.empty());
		when(installations.findByInstallationIdForUpdate(158866076L)).thenReturn(Optional.empty());
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> {
			GithubInstallation saved = invocation.getArgument(0);
			saved.setId(UUID.randomUUID());
			return saved;
		});

		service.completeGithubReconnect(student.getId(), "code", reconnectState);

		verify(github).getInstallation("app-jwt", 158866076L);
		verify(github, never()).getInstallation(eq("app-jwt"), eq(999999L));
		verify(github).listUserInstallations("user-token");
	}

	@Test
	void selectGithubReposReusesExistingRepoRowOnReconnect() {
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158866076L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GitRepo existing = revokedRepo(projectId, installation, 1_338_790_015L);
		existing.setId(UUID.randomUUID());
		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectId)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(installations.findByProject_Id(projectId)).thenReturn(Optional.of(installation));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.createInstallationToken("app-jwt", 158866076L)).thenReturn("inst-token");
		when(github.listInstallationRepos("inst-token")).thenReturn(null);
		when(github.parseRepos(null)).thenReturn(List.of(repo(1_338_790_015L, "saga-fe")));
		Project project = new Project();
		project.setId(projectId);
		when(projects.findFetchedById(projectId)).thenReturn(Optional.of(project));
		when(repos.findByProviderAndRepositoryId(GitProvider.GITHUB, 1_338_790_015L)).thenReturn(Optional.of(existing));
		when(repos.save(any(GitRepo.class))).thenAnswer(invocation -> invocation.getArgument(0));

		service.selectGithubRepos(
				student.getId(),
				projectId,
				List.of(new SelectGitHubRepositoryRequest(1_338_790_015L, RepositoryRole.FRONTEND)));

		ArgumentCaptor<GitRepo> captor = ArgumentCaptor.forClass(GitRepo.class);
		verify(repos).save(captor.capture());
		assertEquals(existing.getId(), captor.getValue().getId());
		assertEquals(IntegrationStatus.ACTIVE, captor.getValue().getConnectionStatus());
		assertEquals(1_338_790_015L, captor.getValue().getRepositoryId());
	}

	private GithubInstallation suspendedInstallation(long installationId) {
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(installationId);
		installation.setInstallationStatus(GitHubInstallationStatus.SUSPENDED);
		installation.setProject(null);
		return installation;
	}

	private GitRepo revokedRepo(UUID projectId, GithubInstallation installation, long repositoryId) {
		Project project = new Project();
		project.setId(projectId);
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setInstallation(installation);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(repositoryId);
		repo.setConnectionStatus(IntegrationStatus.REVOKED);
		repo.setConsecutiveFailures(0);
		return repo;
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

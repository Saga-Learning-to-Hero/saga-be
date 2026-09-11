package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.config.IntegrationProperties;
import com.saga.be.dto.integration.OAuthStartResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
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
import com.saga.be.repository.GithubProjectInstallationRepository;
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

/**
 * Focused shared-installation scenarios (V12 membership) beyond the broad ProjectIntegrationServiceTest suite.
 */
@ExtendWith(MockitoExtension.class)
class GithubSharedInstallationTest {

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
	private GithubProjectInstallationRepository projectInstallations;
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
	private Course course;
	private Team team;
	private UUID projectA;
	private UUID projectB;
	private CourseEnrollment enrollment;
	private TeamMember leaderMember;

	@BeforeEach
	void setUp() {
		lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
				.thenReturn(new SimpleTransactionStatus());
		lenient().when(taskProjectionReset.protectedEvidenceExists(any())).thenReturn(false);
		lenient().when(repos.findByProject_IdWithInstallation(any())).thenReturn(List.of());
		lenient().when(reconnectCandidates.find(any(), any())).thenReturn(List.of());
		lenient().when(projectInstallations.findByProject_IdWithInstallation(any())).thenReturn(List.of());
		lenient().when(projectInstallations.existsByProject_IdAndInstallation_Id(any(), any())).thenReturn(false);
		lenient().when(projectInstallations.save(any(GithubProjectInstallation.class))).thenAnswer(invocation -> {
			GithubProjectInstallation saved = invocation.getArgument(0);
			if (saved.getId() == null) {
				saved.setId(UUID.randomUUID());
			}
			return saved;
		});
		lenient().when(properties.getOauthStateTtl()).thenReturn(Duration.ofMinutes(10));
		lenient()
				.when(installations.findByInstallationIdForUpdate(any()))
				.thenAnswer(invocation -> installations.findByInstallationId(invocation.getArgument(0)));

		student = new UserAccount();
		student.setId(UUID.randomUUID());
		student.setEmail("leader@gmail.com");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		course = new Course();
		course.setId(UUID.randomUUID());
		team = new Team();
		team.setId(UUID.randomUUID());
		team.setCourse(course);
		projectA = UUID.randomUUID();
		projectB = UUID.randomUUID();
		StudentProfile profile = new StudentProfile();
		profile.setId(UUID.randomUUID());
		profile.setUserAccount(student);
		enrollment = new CourseEnrollment();
		enrollment.setId(UUID.randomUUID());
		enrollment.setCourse(course);
		enrollment.setStudentProfile(profile);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		leaderMember = new TeamMember();
		leaderMember.setId(UUID.randomUUID());
		leaderMember.setTeam(team);
		leaderMember.setCourseEnrollment(enrollment);
		leaderMember.setRoleInTeam(RoleInTeam.LEADER);
	}

	@Test
	void sharedConnectProjectBAddsMembershipWithoutClaimingLegacyProjectId() {
		GithubInstallation existing = new GithubInstallation();
		existing.setId(UUID.randomUUID());
		existing.setInstallationId(158866076L);
		existing.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		Project other = new Project();
		other.setId(projectA);
		existing.setProject(other);

		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectB)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(oauthStates.consumeForUser(eq("state"), eq(student.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
						null,
						projectB,
						team.getId(),
						"verifier",
						Instant.now()));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L, 123456L, null, null, "selected"));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setAppId("123456");
		when(properties.getGithub()).thenReturn(githubProps);
		when(properties.getSuccessUrl()).thenReturn("http://localhost:3000/integrations/success");
		Project project = new Project();
		project.setId(projectB);
		when(projects.findFetchedById(projectB)).thenReturn(Optional.of(project));
		when(installations.findByInstallationIdForUpdate(158866076L)).thenReturn(Optional.of(existing));
		when(installations.save(any(GithubInstallation.class))).thenAnswer(invocation -> invocation.getArgument(0));

		String target = service.completeGithubInstallation(student.getId(), "state", 158866076L, null);

		assertEquals("http://localhost:3000/integrations/success", target);
		assertEquals(other, existing.getProject());
		ArgumentCaptor<GithubProjectInstallation> captor = ArgumentCaptor.forClass(GithubProjectInstallation.class);
		verify(projectInstallations).save(captor.capture());
		assertEquals(projectB, captor.getValue().getProject().getId());
		assertEquals(existing.getId(), captor.getValue().getInstallation().getId());
	}

	@Test
	void preRepoMembershipProvenanceStartsReconnectOAuth() {
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158866076L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GithubProjectInstallation membership = new GithubProjectInstallation();
		membership.setId(UUID.randomUUID());
		Project project = new Project();
		project.setId(projectA);
		membership.setProject(project);
		membership.setInstallation(installation);

		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectA)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(projectInstallations.findByProject_IdWithInstallation(projectA)).thenReturn(List.of(membership));
		when(oauthStates.start(
						eq(student.getId()),
						eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
						any(),
						eq(projectA),
						eq(team.getId()),
						any(),
						eq(158866076L)))
				.thenReturn(new OAuthState(
						"state",
						student.getId(),
						OAuthFlowType.GITHUB_TEAM_RECONNECT,
						null,
						projectA,
						team.getId(),
						"verifier",
						Instant.now(),
						158866076L));
		IntegrationProperties.GitHub githubProps = new IntegrationProperties.GitHub();
		githubProps.setOauthCallbackUrl("http://localhost/callback");
		when(properties.getGithub()).thenReturn(githubProps);
		when(github.authorizationUrl(any(), any(), any())).thenReturn("https://github.com/login/oauth/authorize?m=1");

		OAuthStartResponse start = service.startGithub(student.getId(), projectA, null);

		assertEquals("https://github.com/login/oauth/authorize?m=1", start.authorizationUrl());
		verify(oauthStates).start(
				eq(student.getId()),
				eq(OAuthFlowType.GITHUB_TEAM_RECONNECT),
				any(),
				eq(projectA),
				eq(team.getId()),
				any(),
				eq(158866076L));
	}

	@Test
	void disconnectProjectADeletesOnlyThatMembership() {
		Project project = new Project();
		project.setId(projectA);
		GithubInstallation installation = new GithubInstallation();
		installation.setId(UUID.randomUUID());
		installation.setInstallationId(158866076L);
		installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
		GitRepo repo = new GitRepo();
		repo.setId(UUID.randomUUID());
		repo.setProject(project);
		repo.setInstallation(installation);
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);

		when(users.findById(student.getId())).thenReturn(Optional.of(student));
		when(teams.findByProject_Id(projectA)).thenReturn(Optional.of(team));
		when(members.findFetchedByTeam_Id(team.getId())).thenReturn(List.of(leaderMember));
		when(projects.findFetchedById(projectA)).thenReturn(Optional.of(project));
		when(repos.findByProject_Id(projectA)).thenReturn(List.of(repo));

		service.disconnectGithub(student.getId(), projectA);

		assertEquals(IntegrationStatus.REVOKED, repo.getConnectionStatus());
		assertEquals(GitHubInstallationStatus.ACTIVE, installation.getInstallationStatus());
		verify(projectInstallations).deleteByProject_Id(projectA);
		verify(projectInstallations, never()).deleteByProject_Id(projectB);
		verify(installations, never()).save(any());
	}
}

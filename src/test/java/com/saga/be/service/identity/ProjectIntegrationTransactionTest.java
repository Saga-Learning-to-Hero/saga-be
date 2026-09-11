package com.saga.be.service.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.config.IntegrationProperties;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.OAuthFlowType;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.github.GithubInstallation;
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
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.IdentityMapRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
import com.saga.be.repository.SyncJobLogRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.audit.AuditRedactor;
import com.saga.be.service.audit.AuditService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.mockito.Mockito;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false",
			"saga.integration.github.app-id=123456",
			"SAGA_GITHUB_APP_ID=123456"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import(ProjectIntegrationTransactionTest.Config.class)
class ProjectIntegrationTransactionTest {

	@SpringBootConfiguration
	@EnableAutoConfiguration(
			excludeName = {
				"org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration",
				"org.springframework.boot.data.redis.autoconfigure.DataRedisRepositoriesAutoConfiguration",
				"org.springframework.boot.session.autoconfigure.SessionAutoConfiguration",
				"org.springframework.boot.session.data.redis.autoconfigure.SessionDataRedisAutoConfiguration",
				"org.springframework.boot.neo4j.autoconfigure.Neo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jAutoConfiguration",
				"org.springframework.boot.data.neo4j.autoconfigure.DataNeo4jRepositoriesAutoConfiguration",
				"org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration",
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@Autowired
	private ProjectIntegrationService service;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository classes;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private SubjectSyllabusVersionRepository syllabi;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private GithubInstallationRepository installations;
	@Autowired
	private AuditLogRepository audits;
	@Autowired
	private OAuthStateService oauthStates;
	@Autowired
	private GitHubOAuthClient github;
	@Autowired
	private GitHubAppJwtService githubJwt;
	@Autowired
	private ToggleAuditService audit;

	private TransactionTemplate tx;
	private UserAccount leader;
	private Project project;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		ToggleAuditService.fail.set(false);
		Mockito.reset(oauthStates, github, githubJwt);
		tx.executeWithoutResult(status -> {
			installations.deleteAll();
			audits.deleteAll();
			members.deleteAll();
			teams.deleteAll();
			projects.deleteAll();
			enrollments.deleteAll();
			courses.deleteAll();
			syllabi.deleteAll();
			classes.deleteAll();
			subjects.deleteAll();
			students.deleteAll();
			users.deleteAll();
			semesters.deleteAll();
			entityManager.flush();
			entityManager.clear();

			leader = new UserAccount();
			leader.setEmail("leader-" + UUID.randomUUID() + "@gmail.com");
			leader.setFullName("Team Leader");
			leader.setAccountRole(AccountRole.STUDENT);
			leader.setAccountStatus(AccountStatus.ACTIVE);
			leader = users.save(leader);

			StudentProfile profile = new StudentProfile();
			profile.setUserAccount(leader);
			profile.setStudentCode("SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
			profile.setVersion(0L);
			profile = students.save(profile);

			String suffix = UUID.randomUUID().toString().substring(0, 8);
			Semester semester = new Semester();
			semester.setCode("FA" + suffix);
			semester.setName("Fall 2026");
			semester = semesters.save(semester);

			AcademicClass academicClass = new AcademicClass();
			academicClass.setSemester(semester);
			academicClass.setClassCode("SE" + suffix);
			academicClass.setName("SE" + suffix);
			academicClass = classes.save(academicClass);

			Subject subject = new Subject();
			subject.setSubjectCode("SWP" + suffix);
			subject.setName("Software Project");
			subject.setStatus(SubjectStatus.ACTIVE);
			subject = subjects.save(subject);

			SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
			syllabus.setSubject(subject);
			syllabus.setVersionLabel("1.0");
			syllabus.setStatus(SyllabusStatus.PUBLISHED);
			entityManager.persist(syllabus);

			Course course = new Course();
			course.setName("SWP391 · SE1705");
			course.setSubject(subject);
			course.setAcademicClass(academicClass);
			course.setSemester(semester);
			course.setSyllabusVersion(syllabus);
			course = courses.save(course);

			CourseEnrollment enrollment = new CourseEnrollment();
			enrollment.setStudentProfile(profile);
			enrollment.setCourse(course);
			enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
			enrollment.setEnrolledAt(LocalDateTime.now());
			enrollment = enrollments.save(enrollment);

			project = new Project();
			project.setName("Saga");
			project.setCourse(course);
			project.setCreatedBy(leader);
			project = projects.save(project);

			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(1);
			team.setName("Alpha");
			team = teams.save(team);

			TeamMember member = new TeamMember();
			member.setTeam(team);
			member.setCourse(course);
			member.setCourseEnrollment(enrollment);
			member.setRoleInTeam(RoleInTeam.LEADER);
			members.save(member);
		});
	}

	@Test
	void transactionManagerIsRequiredAndActiveDuringPersistPhase() {
		AtomicBoolean active = new AtomicBoolean(false);
		service.persistAtomically(() -> active.set(TransactionSynchronizationManager.isActualTransactionActive()));
		assertTrue(active.get());
		assertTrue(List.of(ProjectIntegrationService.class.getConstructors()[0].getParameterTypes())
				.contains(PlatformTransactionManager.class));
	}

	@Test
	void successfulGithubCallbackPersistsInstallationAndAudit() {
		stubGithubProviderSuccess();
		String target = service.completeGithubInstallation(leader.getId(), "state", 158866076L, null);
		assertEquals("http://localhost:3000/integrations/success", target);
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			GithubInstallation saved = installations.findByInstallationId(158866076L).orElseThrow();
			assertEquals(project.getId(), saved.getProject().getId());
			assertEquals(GitHubInstallationStatus.ACTIVE, saved.getInstallationStatus());
			List<AuditLog> logs = audits.findAll();
			assertEquals(1, logs.size());
			assertEquals("GITHUB_INSTALLATION_CONNECTED", logs.getFirst().getAction());
			assertEquals(saved.getId(), logs.getFirst().getEntityId());
		});
	}

	@Test
	void auditFailureRollsBackGithubInstallation() {
		stubGithubProviderSuccess();
		ToggleAuditService.fail.set(true);
		assertThrows(RuntimeException.class, () -> service.completeGithubInstallation(leader.getId(), "state", 158866076L, null));
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			assertTrue(installations.findAll().isEmpty());
			assertTrue(audits.findAll().isEmpty());
		});
	}

	@Test
	void providerFailureLeavesDatabaseUnchanged() {
		when(oauthStates.consumeForUser(eq("state"), eq(leader.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state());
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L)).thenReturn(null);
		IntegrationException providerEx = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubInstallation(leader.getId(), "state", 158866076L, null));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID, providerEx.getCode());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			assertTrue(installations.findAll().isEmpty());
			assertTrue(audits.findAll().isEmpty());
		});
	}

	@Test
	void persistRevalidatesAuthorizationAfterProviderHttp() {
		when(oauthStates.consumeForUser(eq("state"), eq(leader.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state());
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L)).thenAnswer(invocation -> {
			tx.executeWithoutResult(status -> {
				TeamMember member = members.findAll().getFirst();
				member.setRoleInTeam(RoleInTeam.MEMBER);
				members.save(member);
			});
			return new GitHubOAuthClient.GitHubInstallationResponse(
					158866076L,
					123456L,
					new GitHubOAuthClient.GitHubAccountResponse("Saga-Learning-to-Hero", "Organization"),
					"https://github.com/settings/installations/158866076",
					"selected");
		});
		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubInstallation(leader.getId(), "state", 158866076L, null));
		assertEquals(IntegrationErrorCode.NOT_TEAM_LEADER, ex.getCode());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			assertTrue(installations.findAll().isEmpty());
			assertTrue(audits.findAll().isEmpty());
		});
	}

	@Test
	void concurrentBindOfSameInstallationSecondWriterGetsConflict() {
		stubGithubProviderSuccess();
		String first = service.completeGithubInstallation(leader.getId(), "state", 158866076L, null);
		assertEquals("http://localhost:3000/integrations/success", first);

		UserAccount admin = tx.execute(status -> {
			UserAccount account = new UserAccount();
			account.setEmail("admin-" + UUID.randomUUID() + "@saga.local");
			account.setFullName("Admin");
			account.setAccountRole(AccountRole.ADMIN);
			account.setAccountStatus(AccountStatus.ACTIVE);
			return users.save(account);
		});
		Project otherProject = tx.execute(status -> {
			Project p = new Project();
			p.setName("Other");
			p.setCourse(project.getCourse());
			p.setCreatedBy(admin);
			p = projects.save(p);
			Team otherTeam = new Team();
			otherTeam.setCourse(project.getCourse());
			otherTeam.setProject(p);
			otherTeam.setTeamNo(2);
			otherTeam.setName("Beta");
			teams.save(otherTeam);
			return p;
		});

		when(oauthStates.consumeForUser(eq("state-b"), eq(admin.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(new OAuthState(
						"state-b",
						admin.getId(),
						OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
						null,
						otherProject.getId(),
						null,
						"verifier",
						Instant.now()));
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("Saga-Learning-to-Hero", "Organization"),
						"https://github.com/settings/installations/158866076",
						"selected"));

		IntegrationException ex = assertThrows(
				IntegrationException.class,
				() -> service.completeGithubInstallation(admin.getId(), "state-b", 158866076L, null));
		assertEquals(IntegrationErrorCode.GITHUB_INSTALLATION_IN_USE, ex.getCode());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			GithubInstallation saved = installations.findByInstallationId(158866076L).orElseThrow();
			assertEquals(project.getId(), saved.getProject().getId());
		});
	}

	private void stubGithubProviderSuccess() {
		when(oauthStates.consumeForUser(eq("state"), eq(leader.getId()), eq(OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY)))
				.thenReturn(state());
		when(githubJwt.createJwt()).thenReturn("app-jwt");
		when(github.getInstallation("app-jwt", 158866076L))
				.thenReturn(new GitHubOAuthClient.GitHubInstallationResponse(
						158866076L,
						123456L,
						new GitHubOAuthClient.GitHubAccountResponse("Saga-Learning-to-Hero", "Organization"),
						"https://github.com/settings/installations/158866076",
						"selected"));
	}

	private OAuthState state() {
		return new OAuthState(
				"state",
				leader.getId(),
				OAuthFlowType.GITHUB_TEAM_INSTALL_VERIFY,
				null,
				project.getId(),
				null,
				"verifier",
				Instant.now());
	}

	@TestConfiguration
	static class Config {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		@Primary
		IntegrationProperties integrationProperties() {
			IntegrationProperties properties = new IntegrationProperties();
			properties.getGithub().setAppId("123456");
			return properties;
		}

		@Bean
		GitHubOAuthClient gitHubOAuthClient() {
			return Mockito.mock(GitHubOAuthClient.class);
		}

		@Bean
		GitHubAppJwtService gitHubAppJwtService() {
			return Mockito.mock(GitHubAppJwtService.class);
		}

		@Bean
		JiraOAuthClient jiraOAuthClient() {
			return Mockito.mock(JiraOAuthClient.class);
		}

		@Bean
		OAuthStateService oauthStateService() {
			return Mockito.mock(OAuthStateService.class);
		}

		@Bean
		PendingJiraConnectStore pendingJiraConnectStore() {
			return Mockito.mock(PendingJiraConnectStore.class);
		}

		@Bean
		TokenEncryptor tokenEncryptor() {
			return new TokenEncryptor("");
		}

		@Bean
		OutboxPublisher outboxPublisher() {
			OutboxPublisher publisher = Mockito.mock(OutboxPublisher.class);
			Mockito.doAnswer(invocation -> {
						assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
						return null;
					})
					.when(publisher)
					.publish(any(), any(), any(), any());
			return publisher;
		}

		@Bean
		ToggleAuditService toggleAuditService(
				com.saga.be.repository.AuditLogRepository auditLogs,
				StudentProfileRepository students,
				ObjectMapper mapper) {
			return new ToggleAuditService(auditLogs, students, new AuditRedactor(mapper), mapper);
		}

		@Bean
		@Primary
		ProjectIntegrationService projectIntegrationService(
				UserAccountRepository users,
				ProjectRepository projects,
				TeamByProjectRepository teams,
				TeamMemberRepository members,
				IdentityMapRepository identities,
				GithubInstallationRepository installations,
				GitRepoRepository repos,
				JiraIntegrationRepository jiraIntegrations,
				SyncJobLogRepository syncJobs,
				OAuthStateService oauthStates,
				PendingJiraConnectStore pendingJira,
				IntegrationProperties properties,
				GitHubOAuthClient github,
				GitHubAppJwtService githubJwt,
				JiraOAuthClient jira,
				TokenEncryptor encryptor,
				ToggleAuditService audit,
				OutboxPublisher outbox,
				PlatformTransactionManager transactionManager) {
			return new ProjectIntegrationService(
					users,
					projects,
					teams,
					members,
					identities,
					installations,
					repos,
					jiraIntegrations,
					syncJobs,
					oauthStates,
					pendingJira,
					Mockito.mock(com.saga.be.integration.oauth.GithubReconnectCandidateStore.class),
					properties,
					github,
					githubJwt,
					jira,
					encryptor,
					audit,
					outbox,
					Mockito.mock(com.saga.be.service.sync.IntegrationInitialSyncLauncher.class),
					Mockito.mock(com.saga.be.service.identity.JiraTaskProjectionHardReset.class),
					Mockito.mock(com.saga.be.service.jira.JiraDynamicWebhookService.class),
					transactionManager);
		}
	}

	static class ToggleAuditService extends AuditService {
		static final AtomicBoolean fail = new AtomicBoolean(false);

		ToggleAuditService(
				com.saga.be.repository.AuditLogRepository auditLogs,
				StudentProfileRepository students,
				AuditRedactor redactor,
				ObjectMapper mapper) {
			super(auditLogs, students, redactor, mapper);
		}

		@Override
		public com.saga.be.entity.audit.AuditLog record(
				UserAccount actor,
				Project project,
				Team team,
				String action,
				String entityType,
				UUID entityId,
				java.util.Map<String, Object> before,
				java.util.Map<String, Object> after,
				java.util.Map<String, Object> metadata,
				com.saga.be.entity.enums.AuditSource source,
				String requestId,
				String ip,
				String userAgent) {
			assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
			com.saga.be.entity.audit.AuditLog saved = super.record(
					actor, project, team, action, entityType, entityId, before, after, metadata, source, requestId, ip, userAgent);
			if (fail.get()) {
				throw new RuntimeException("forced audit failure");
			}
			return saved;
		}
	}
}

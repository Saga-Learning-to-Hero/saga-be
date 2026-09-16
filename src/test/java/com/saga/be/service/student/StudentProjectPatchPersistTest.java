package com.saga.be.service.student;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.project.PatchProjectRequest;
import com.saga.be.dto.project.StudentProjectResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitHubInstallationStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.github.GithubInstallation;
import com.saga.be.entity.github.GithubProjectInstallation;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.ProjectType;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.realtime.ProjectRealtimePublisher;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.GithubInstallationRepository;
import com.saga.be.repository.GithubProjectInstallationRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.ProjectTypeRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
import com.saga.be.repository.TeamByProjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditRedactor;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.projection.ProjectDataAuthorization;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.event.EventListener;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentProjectPatchPersistTest {

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
	static class TxSlice {
		@Bean
		ProjectEventSink projectEventSink() {
			return new ProjectEventSink();
		}

		@Bean
		ProjectRealtimePublisher projectRealtimePublisher(ApplicationEventPublisher events) {
			return new ProjectRealtimePublisher(events);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}

		@Bean
		AuditRedactor auditRedactor(ObjectMapper mapper) {
			return new AuditRedactor(mapper);
		}

		@Bean
		AuditService auditService(
				AuditLogRepository auditLogs,
				StudentProfileRepository students,
				AuditRedactor redactor,
				ObjectMapper mapper) {
			return new AuditService(auditLogs, students, redactor, mapper);
		}

		@Bean
		StudentTeamService studentTeamService(
				StudentProfileRepository students, CourseEnrollmentRepository enrollments, TeamMemberRepository members) {
			return new StudentTeamService(students, enrollments, members);
		}

		@Bean
		ProjectDataAuthorization projectDataAuthorization(
				UserAccountRepository users, TeamMemberRepository members, ProjectRepository projects) {
			return new ProjectDataAuthorization(users, members, projects);
		}

		@Bean
		StudentProjectService studentProjectService(
				StudentTeamService teams,
				ProjectRepository projects,
				ProjectTypeRepository projectTypes,
				TeamRepository lockedTeams,
				TeamByProjectRepository teamByProject,
				UserAccountRepository users,
				ProjectDataAuthorization authorization,
				ProjectRealtimePublisher realtime,
				AuditService audit) {
			return new StudentProjectService(
					teams, projects, projectTypes, lockedTeams, teamByProject, users, authorization, realtime, audit);
		}
	}

	static class ProjectEventSink {
		final List<ProjectRealtimeEvent> events = new CopyOnWriteArrayList<>();

		@EventListener
		void on(ProjectRealtimeEvent event) {
			events.add(event);
		}

		void clear() {
			events.clear();
		}
	}

	@Autowired
	private StudentProjectService service;
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
	private ProjectTypeRepository projectTypes;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private GithubInstallationRepository installations;
	@Autowired
	private GithubProjectInstallationRepository projectInstallations;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private AuditLogRepository audits;
	@Autowired
	private ProjectEventSink events;
	@Autowired
	private ObjectMapper mapper;

	private TransactionTemplate tx;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		events.clear();
		fixture = seed();
	}

	@Test
	void leaderNameChangePersistsAuditAndSchedulesGraphAfterCommit() {
		StudentProjectResponse[] holder = new StudentProjectResponse[1];
		tx.executeWithoutResult(status -> {
			holder[0] = service.patch(
					fixture.leaderId,
					fixture.projectId,
					new PatchProjectRequest("  Renamed Project  ", null),
					auditReq());
			assertTrue(events.events.isEmpty());
		});
		StudentProjectResponse response = holder[0];
		assertEquals("Renamed Project", response.name());
		assertEquals("original body", response.description());
		assertEquals(fixture.projectTypeId, response.projectType().id());
		assertEquals(fixture.courseId, response.courseId());
		assertEquals(fixture.teamId, response.teamId());
		assertEquals(fixture.leaderId, response.createdBy().userId());

		tx.executeWithoutResult(status -> {
			entityManager.clear();
			Project reloaded = projects.findById(fixture.projectId).orElseThrow();
			assertEquals("Renamed Project", reloaded.getName());
			assertEquals("original body", reloaded.getDescription());
			assertEquals(fixture.projectTypeId, reloaded.getProjectType().getId());
			assertEquals(fixture.courseId, reloaded.getCourse().getId());
			assertEquals(fixture.leaderId, reloaded.getCreatedBy().getId());
			assertEquals("https://github.com/org/legacy", reloaded.getRepositoryUrl());
			assertIntegrationsUnchanged();
			List<AuditLog> logs = audits.findAll();
			assertEquals(1, logs.size());
			assertEquals(StudentProjectService.PROJECT_UPDATED, logs.getFirst().getAction());
			assertEquals(fixture.projectId, logs.getFirst().getContextProjectId());
			assertEquals(fixture.teamId, logs.getFirst().getContextTeamId());
			assertEquals(fixture.leaderId, logs.getFirst().getActorUser().getId());
			JsonNode before = json(logs.getFirst().getBeforeData());
			JsonNode after = json(logs.getFirst().getAfterData());
			assertEquals("Original Project", before.get("name").asText());
			assertEquals("Renamed Project", after.get("name").asText());
			assertTrue(before.path("description").isMissingNode());
			assertTrue(after.path("description").isMissingNode());
		});
		assertEquals(1, events.events.size());
		assertEquals(ProjectRealtimeEventType.PROJECT_METADATA_CHANGED, events.events.getFirst().type());
		assertEquals(fixture.projectId, events.events.getFirst().projectId());
	}

	@Test
	void descriptionOnlyClearsToNullWithoutGraphEvent() {
		service.patch(fixture.leaderId, fixture.projectId, new PatchProjectRequest(null, "   "), auditReq());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			Project reloaded = projects.findById(fixture.projectId).orElseThrow();
			assertEquals("Original Project", reloaded.getName());
			assertNull(reloaded.getDescription());
			assertEquals(fixture.projectTypeId, reloaded.getProjectType().getId());
			assertIntegrationsUnchanged();
			AuditLog log = audits.findAll().getFirst();
			assertEquals(StudentProjectService.PROJECT_UPDATED, log.getAction());
			assertTrue(json(log.getBeforeData()).has("description"));
			assertTrue(json(log.getAfterData()).get("description").isNull());
			assertTrue(json(log.getBeforeData()).path("name").isMissingNode());
		});
		assertTrue(events.events.isEmpty());
	}

	@Test
	void emptyPatchIsNoOpWithoutAuditOrGraph() {
		StudentProjectResponse response =
				service.patch(fixture.leaderId, fixture.projectId, new PatchProjectRequest(null, null), auditReq());
		assertEquals("Original Project", response.name());
		assertEquals("original body", response.description());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			Project reloaded = projects.findById(fixture.projectId).orElseThrow();
			assertEquals("Original Project", reloaded.getName());
			assertEquals("original body", reloaded.getDescription());
			assertTrue(audits.findAll().isEmpty());
			assertIntegrationsUnchanged();
		});
		assertTrue(events.events.isEmpty());
	}

	@Test
	void rollbackDoesNotPersistOrPublishGraphEvent() {
		assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
			service.patch(
					fixture.leaderId, fixture.projectId, new PatchProjectRequest("Should Roll Back", null), auditReq());
			assertTrue(events.events.isEmpty());
			throw new IllegalStateException("boom");
		}));
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			Project reloaded = projects.findById(fixture.projectId).orElseThrow();
			assertEquals("Original Project", reloaded.getName());
			assertTrue(audits.findAll().isEmpty());
			assertIntegrationsUnchanged();
		});
		assertTrue(events.events.isEmpty());
	}

	@Test
	void memberLecturerAdminForeignAndInactiveAreDenied() {
		assertEquals(
				IntegrationErrorCode.NOT_TEAM_LEADER,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.memberId,
										fixture.projectId,
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		assertEquals(
				IntegrationErrorCode.ACCESS_DENIED,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.lecturerId,
										fixture.projectId,
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		assertEquals(
				IntegrationErrorCode.ACCESS_DENIED,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.adminId,
										fixture.projectId,
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		assertEquals(
				IntegrationErrorCode.INTEGRATION_FORBIDDEN,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.foreignLeaderId,
										fixture.projectId,
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		assertEquals(
				IntegrationErrorCode.INTEGRATION_FORBIDDEN,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.inactiveLeaderId,
										fixture.projectId,
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		assertEquals(
				IntegrationErrorCode.INTEGRATION_FORBIDDEN,
				assertThrows(
								IntegrationException.class,
								() -> service.patch(
										fixture.leaderId,
										UUID.randomUUID(),
										new PatchProjectRequest("Nope", null),
										auditReq()))
						.getCode());
		tx.executeWithoutResult(status -> {
			entityManager.clear();
			assertEquals("Original Project", projects.findById(fixture.projectId).orElseThrow().getName());
			assertTrue(audits.findAll().isEmpty());
		});
		assertTrue(events.events.isEmpty());
	}

	private void assertIntegrationsUnchanged() {
		GithubInstallation installation = installations.findAll().getFirst();
		assertEquals(158866076L, installation.getInstallationId());
		assertEquals(GitHubInstallationStatus.ACTIVE, installation.getInstallationStatus());
		List<GithubProjectInstallation> memberships =
				projectInstallations.findByProject_IdWithInstallation(fixture.projectId);
		assertEquals(1, memberships.size());
		GitRepo repo = repos.findByProject_Id(fixture.projectId).getFirst();
		assertEquals("org/saga", repo.getFullName());
		assertEquals(IntegrationStatus.ACTIVE, repo.getConnectionStatus());
		JiraIntegration jira = jiraIntegrations.findByProject_Id(fixture.projectId).orElseThrow();
		assertEquals("cloud-1", jira.getCloudId());
		assertEquals("SAGA", jira.getProjectKey());
		assertEquals("enc-access", jira.getEncryptedAccessToken());
	}

	private Fixture seed() {
		return tx.execute(status -> {
			events.clear();
			audits.deleteAll();
			jiraIntegrations.deleteAll();
			repos.deleteAll();
			projectInstallations.deleteAll();
			installations.deleteAll();
			members.deleteAll();
			teams.deleteAll();
			projects.deleteAll();
			projectTypes.deleteAll();
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

			UserAccount leader = account("leader-" + UUID.randomUUID() + "@gmail.com", AccountRole.STUDENT);
			UserAccount member = account("member-" + UUID.randomUUID() + "@gmail.com", AccountRole.STUDENT);
			UserAccount lecturer = account("lecturer-" + UUID.randomUUID() + "@fe.edu.vn", AccountRole.LECTURER);
			UserAccount admin = account("admin-" + UUID.randomUUID() + "@fe.edu.vn", AccountRole.ADMIN);
			UserAccount foreignLeader = account("foreign-" + UUID.randomUUID() + "@gmail.com", AccountRole.STUDENT);
			UserAccount inactiveLeader = account("inactive-" + UUID.randomUUID() + "@gmail.com", AccountRole.STUDENT);

			StudentProfile leaderProfile = profile(leader, "SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
			StudentProfile memberProfile = profile(member, "SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
			StudentProfile foreignProfile = profile(foreignLeader, "SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
			StudentProfile inactiveProfile = profile(inactiveLeader, "SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));

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

			Subject foreignSubject = new Subject();
			foreignSubject.setSubjectCode("SWD" + suffix);
			foreignSubject.setName("Other subject");
			foreignSubject.setStatus(SubjectStatus.ACTIVE);
			foreignSubject = subjects.save(foreignSubject);

			SubjectSyllabusVersion foreignSyllabus = new SubjectSyllabusVersion();
			foreignSyllabus.setSubject(foreignSubject);
			foreignSyllabus.setVersionLabel("1.0");
			foreignSyllabus.setStatus(SyllabusStatus.PUBLISHED);
			entityManager.persist(foreignSyllabus);

			Course foreignCourse = new Course();
			foreignCourse.setName("Other course");
			foreignCourse.setSubject(foreignSubject);
			foreignCourse.setAcademicClass(academicClass);
			foreignCourse.setSemester(semester);
			foreignCourse.setSyllabusVersion(foreignSyllabus);
			foreignCourse = courses.save(foreignCourse);

			ProjectType type = new ProjectType();
			type.setCode("RESEARCH");
			type.setName("Research");
			type = projectTypes.save(type);

			Project project = new Project();
			project.setCourse(course);
			project.setProjectType(type);
			project.setName("Original Project");
			project.setDescription("original body");
			project.setRepositoryUrl("https://github.com/org/legacy");
			project.setCreatedBy(leader);
			project = projects.save(project);

			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(1);
			team.setName("Alpha");
			team = teams.save(team);

			members.save(memberOf(team, course, enroll(leaderProfile, course, EnrollmentStatus.ACTIVE), RoleInTeam.LEADER));
			members.save(memberOf(team, course, enroll(memberProfile, course, EnrollmentStatus.ACTIVE), RoleInTeam.MEMBER));
			members.save(memberOf(
					team, course, enroll(inactiveProfile, course, EnrollmentStatus.WITHDRAWN), RoleInTeam.LEADER));

			Project foreignProject = new Project();
			foreignProject.setCourse(foreignCourse);
			foreignProject.setName("Foreign");
			foreignProject.setCreatedBy(foreignLeader);
			foreignProject = projects.save(foreignProject);
			Team foreignTeam = new Team();
			foreignTeam.setCourse(foreignCourse);
			foreignTeam.setProject(foreignProject);
			foreignTeam.setTeamNo(1);
			foreignTeam.setName("Bravo");
			foreignTeam = teams.save(foreignTeam);
			members.save(memberOf(
					foreignTeam,
					foreignCourse,
					enroll(foreignProfile, foreignCourse, EnrollmentStatus.ACTIVE),
					RoleInTeam.LEADER));

			GithubInstallation installation = new GithubInstallation();
			installation.setInstallationId(158866076L);
			installation.setAppId(1L);
			installation.setAccountLogin("org");
			installation.setInstallationStatus(GitHubInstallationStatus.ACTIVE);
			installation.setConsecutiveFailures(0);
			installation = installations.save(installation);
			GithubProjectInstallation membership = new GithubProjectInstallation();
			membership.setProject(project);
			membership.setInstallation(installation);
			projectInstallations.save(membership);

			GitRepo repo = new GitRepo();
			repo.setProject(project);
			repo.setInstallation(installation);
			repo.setName("saga");
			repo.setProvider(GitProvider.GITHUB);
			repo.setRepositoryId(99L);
			repo.setOwnerLogin("org");
			repo.setFullName("org/saga");
			repo.setConnectionStatus(IntegrationStatus.ACTIVE);
			repo.setConsecutiveFailures(0);
			repos.save(repo);

			JiraIntegration jira = new JiraIntegration();
			jira.setProject(project);
			jira.setName("SAGA board");
			jira.setCloudId("cloud-1");
			jira.setSiteUrl("https://example.atlassian.net");
			jira.setJiraProjectId("10000");
			jira.setProjectKey("SAGA");
			jira.setEncryptedAccessToken("enc-access");
			jira.setEncryptedRefreshToken("enc-refresh");
			jira.setEncryptedWebhookSecret("enc-webhook");
			jira.setConnectionStatus(IntegrationStatus.ACTIVE);
			jira.setConnectedBy(leader);
			jira.setConsecutiveFailures(0);
			jiraIntegrations.save(jira);

			entityManager.flush();
			return new Fixture(
					leader.getId(),
					member.getId(),
					lecturer.getId(),
					admin.getId(),
					foreignLeader.getId(),
					inactiveLeader.getId(),
					project.getId(),
					course.getId(),
					team.getId(),
					type.getId());
		});
	}

	private UserAccount account(String email, AccountRole role) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return users.save(account);
	}

	private StudentProfile profile(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		return students.save(profile);
	}

	private CourseEnrollment enroll(StudentProfile profile, Course course, EnrollmentStatus status) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(status);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollments.save(enrollment);
	}

	private static TeamMember memberOf(Team team, Course course, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private JsonNode json(String value) {
		try {
			return mapper.readTree(value);
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-patch", "127.0.0.1", "test");
	}

	private record Fixture(
			UUID leaderId,
			UUID memberId,
			UUID lecturerId,
			UUID adminId,
			UUID foreignLeaderId,
			UUID inactiveLeaderId,
			UUID projectId,
			UUID courseId,
			UUID teamId,
			UUID projectTypeId) {}
}

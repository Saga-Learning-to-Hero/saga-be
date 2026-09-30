package com.saga.be.service.sprint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.config.AuthProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.warning.BusinessWarning;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.realtime.ProjectRealtimeEvent;
import com.saga.be.realtime.ProjectRealtimeEventType;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.BusinessWarningRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import com.saga.be.service.mail.EmailOutboxService;
import com.saga.be.service.notification.NotificationService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real H2 + real repositories/queries/transactions (project row lock, fetched team roster,
 * BusinessWarning pair dedupe, real NotificationService with its per-recipient event key). Only
 * the email outbox is mocked so the recipients and payload can be asserted.
 */
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
class SprintOverlapAlertPersistTest {

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

	@Autowired private UserAccountRepository users;
	@Autowired private StudentProfileRepository students;
	@Autowired private LecturerProfileRepository lecturers;
	@Autowired private SubjectRepository subjects;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SemesterRepository semesters;
	@Autowired private CourseRepository courses;
	@Autowired private CourseEnrollmentRepository enrollments;
	@Autowired private ProjectRepository projects;
	@Autowired private TeamRepository teams;
	@Autowired private TeamMemberRepository teamMembers;
	@Autowired private JiraIntegrationRepository jiraIntegrations;
	@Autowired private SprintRepository sprints;
	@Autowired private BusinessWarningRepository warnings;
	@Autowired private UserNotificationRepository notificationRows;
	@Autowired private PlatformTransactionManager transactionManager;

	private EmailOutboxService emails;
	private SprintOverlapAlertService service;
	private UserAccount lecturer;
	private UserAccount leader;
	private UserAccount member;
	private Project project;
	private JiraIntegration siteA;
	private JiraIntegration siteB;

	@BeforeEach
	void setUp() {
		emails = mock(EmailOutboxService.class);
		NotificationService notifications =
				new NotificationService(notificationRows, users, mock(ApplicationEventPublisher.class));
		service = new SprintOverlapAlertService(
				projects,
				sprints,
				teams,
				teamMembers,
				warnings,
				notifications,
				emails,
				new EmailTemplateService(new AuthProperties()),
				transactionManager,
				Runnable::run,
				Clock.fixed(Instant.parse("2026-09-30T05:00:00Z"), ZoneOffset.UTC));

		lecturer = users.save(account(AccountRole.LECTURER, "lecturer"));
		LecturerProfile lecturerProfile = new LecturerProfile();
		lecturerProfile.setUserAccount(lecturer);
		lecturerProfile = lecturers.save(lecturerProfile);
		Course course = course(lecturerProfile);
		project = new Project();
		project.setCourse(course);
		project.setName("SAGA Project");
		project = projects.save(project);
		Team team = new Team();
		team.setCourse(course);
		team.setProject(project);
		team.setTeamNo(1);
		team.setName("Alpha");
		team = teams.save(team);
		leader = users.save(account(AccountRole.STUDENT, "leader"));
		member = users.save(account(AccountRole.STUDENT, "member"));
		teamMembers.save(teamMember(team, course, enroll(leader, course), RoleInTeam.LEADER));
		teamMembers.save(teamMember(team, course, enroll(member, course), RoleInTeam.MEMBER));
		siteA = jiraIntegrations.save(site("cloud-a", "site-a"));
		siteB = jiraIntegrations.save(site("cloud-b", "site-b"));
	}

	@Test
	void overlappingSprintsOfTwoSitesAlertLeaderAndLecturerOnceByNotificationAndEmail() {
		sprints.save(sprint(siteA, "SAGA Sprint 5", "closed", day(9, 1), day(9, 14), day(9, 14)));
		sprints.save(sprint(siteB, "B Sprint 1", "closed", day(9, 10), day(9, 24), day(9, 24)));

		assertThat(service.checkProject(project.getId())).isEqualTo(1);

		List<BusinessWarning> saved = projectWarnings();
		assertThat(saved).hasSize(1);
		assertThat(saved.getFirst().getEventKey()).startsWith(SprintOverlapAlertService.EVENT_KEY_PREFIX);
		assertThat(saved.getFirst().getEvidenceSummary()).contains("SAGA Sprint 5").contains("B Sprint 1");
		assertThat(notificationRows.countByRecipientUser_Id(leader.getId())).isEqualTo(1);
		assertThat(notificationRows.countByRecipientUser_Id(lecturer.getId())).isEqualTo(1);
		assertThat(notificationRows.countByRecipientUser_Id(member.getId())).isZero();
		ArgumentCaptor<EmailEnqueueRequest> mails = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails, times(2)).enqueue(mails.capture());
		assertThat(mails.getAllValues())
				.extracting(EmailEnqueueRequest::recipientUserId)
				.containsExactlyInAnyOrder(leader.getId(), lecturer.getId());
		assertThat(mails.getAllValues()).allSatisfy(mail -> {
			assertThat(mail.emailType()).isEqualTo("SPRINT_PERIOD_OVERLAP");
			assertThat(String.valueOf(mail.payload().get("subject"))).contains("SAGA Project");
			assertThat(String.valueOf(mail.payload().get("htmlBody"))).contains("B Sprint 1").contains("site-b");
		});

		// Every later sync sees the same pair again: no second warning, notification or email.
		assertThat(service.checkProject(project.getId())).isZero();
		verify(emails, times(2)).enqueue(any());
		assertThat(projectWarnings()).hasSize(1);
		assertThat(notificationRows.countByRecipientUser_Id(leader.getId())).isEqualTo(1);
	}

	@Test
	void sprintsHandingOverOnTheSameDayOrOnlyPlannedOverlapsAreNotAlerted() {
		sprints.save(sprint(siteA, "A1", "closed", day(9, 1), day(9, 14), day(9, 14)));
		sprints.save(sprint(siteB, "B1", "closed", day(9, 14), day(9, 28), day(9, 28)));
		sprints.save(sprint(siteA, "A2 planned", "future", day(9, 20), day(10, 4), null));

		assertThat(service.checkProject(project.getId())).isZero();
		verify(emails, never()).enqueue(any());
		assertThat(projectWarnings()).isEmpty();
	}

	@Test
	void onlySprintsChangedEventsTriggerTheCheck() {
		sprints.save(sprint(siteA, "A1", "closed", day(9, 1), day(9, 14), day(9, 14)));
		sprints.save(sprint(siteB, "B1", "closed", day(9, 10), day(9, 24), day(9, 24)));

		service.onProjectEvent(ProjectRealtimeEvent.of(ProjectRealtimeEventType.TASKS_CHANGED, project.getId()));
		assertThat(projectWarnings()).isEmpty();

		service.onProjectEvent(ProjectRealtimeEvent.of(ProjectRealtimeEventType.SPRINTS_CHANGED, project.getId()));
		assertThat(projectWarnings()).hasSize(1);
	}

	@Test
	void unknownProjectIsANoOpAndSafeCheckNeverThrows() {
		assertThat(service.checkProject(UUID.randomUUID())).isZero();
		service.checkProjectSafely(null);
		service.checkProjectAsync(null);
	}

	@Test
	void aFullQueueOnlySkipsTheCheckInsteadOfFailingTheCaller() {
		SprintOverlapAlertService saturated = new SprintOverlapAlertService(
				projects,
				sprints,
				teams,
				teamMembers,
				warnings,
				new NotificationService(notificationRows, users, mock(ApplicationEventPublisher.class)),
				emails,
				new EmailTemplateService(new AuthProperties()),
				transactionManager,
				task -> {
					throw new java.util.concurrent.RejectedExecutionException("full");
				},
				Clock.systemUTC());

		saturated.onProjectEvent(ProjectRealtimeEvent.of(ProjectRealtimeEventType.SPRINTS_CHANGED, project.getId()));

		verify(emails, never()).enqueue(any());
	}

	/** Tests share one H2 database without rollback, so only count this test's project. */
	private List<BusinessWarning> projectWarnings() {
		return warnings.findAll().stream()
				.filter(row -> row.getProject() != null && project.getId().equals(row.getProject().getId()))
				.toList();
	}

	private static LocalDateTime day(int month, int dayOfMonth) {
		return LocalDateTime.of(2026, month, dayOfMonth, 9, 0);
	}

	private Sprint sprint(
			JiraIntegration site, String name, String state, LocalDateTime start, LocalDateTime end, LocalDateTime complete) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(site);
		sprint.setExternalSprintId(UUID.randomUUID().toString().substring(0, 8));
		sprint.setName(name);
		sprint.setState(state);
		sprint.setStartDate(start);
		sprint.setEndDate(end);
		sprint.setCompleteDate(complete);
		return sprint;
	}

	private JiraIntegration site(String cloudId, String siteName) {
		JiraIntegration jira = new JiraIntegration();
		jira.setProject(project);
		jira.setName("board-" + cloudId);
		jira.setCloudId(cloudId);
		jira.setSiteName(siteName);
		jira.setJiraProjectId(cloudId + "-p");
		jira.setProjectKey("SAGA");
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setConsecutiveFailures(0);
		jira.setVersion(0L);
		return jira;
	}

	private CourseEnrollment enroll(UserAccount account, Course course) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(("SE" + account.getId()).substring(0, 12));
		profile = students.save(profile);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollments.save(enrollment);
	}

	private static TeamMember teamMember(Team team, Course course, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember row = new TeamMember();
		row.setTeam(team);
		row.setCourse(course);
		row.setCourseEnrollment(enrollment);
		row.setRoleInTeam(role);
		return row;
	}

	private static UserAccount account(AccountRole role, String label) {
		UserAccount account = new UserAccount();
		account.setEmail(label + "-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName(label);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private Course course(LecturerProfile instructor) {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Test Class");
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);
		Course created = new Course();
		created.setName("SWP391 · " + academicClass.getClassCode());
		created.setAcademicClass(academicClass);
		created.setSemester(semester);
		created.setSubject(subject);
		created.setInstructor(instructor);
		return courses.save(created);
	}
}

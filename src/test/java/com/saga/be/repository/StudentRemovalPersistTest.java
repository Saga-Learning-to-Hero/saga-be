package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailEnqueueRequest;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.dto.team.LecturerCourseTeamsResponse;
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
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.mail.template.EmailTemplateService;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.mail.EmailOutboxService;
import com.saga.be.service.notification.NotificationService;
import com.saga.be.service.notification.StudentRemovalNotifier;
import com.saga.be.service.roster.CourseRosterService;
import com.saga.be.service.roster.JpaCourseRosterStore;
import com.saga.be.service.roster.RosterPreviewStore;
import com.saga.be.service.team.JpaLecturerTeamStore;
import com.saga.be.service.team.LecturerTeamService;
import com.saga.be.service.team.TeamPreviewStore;
import java.time.LocalDateTime;
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
 * Real H2 + real JPA stores + real transactions + real NotificationService for removing a student
 * from a team and from a course with a reason. Proves the membership row really goes, the student
 * gets exactly one notification (with the reason) and one email per removal, all committed with
 * the removal, and a refused removal writes nothing at all.
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
class StudentRemovalPersistTest {

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
	@Autowired private LecturerProfileRepository lecturerProfiles;
	@Autowired private SubjectRepository subjects;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SemesterRepository semesters;
	@Autowired private CourseRepository courses;
	@Autowired private CourseEnrollmentRepository enrollments;
	@Autowired private StudentCourseInvitationRepository invitations;
	@Autowired private TeamRepository teams;
	@Autowired private TeamMemberRepository teamMembers;
	@Autowired private UserNotificationRepository notificationRows;
	@Autowired private PlatformTransactionManager transactionManager;

	private EmailOutboxService emails;
	private LecturerTeamService teamService;
	private CourseRosterService rosterService;
	private UserAccount lecturer;
	private Course course;
	private Team team;
	private UserAccount leaderAccount;
	private UserAccount memberAccount;
	private CourseEnrollment leaderEnrollment;
	private CourseEnrollment memberEnrollment;
	private TeamMember leader;
	private TeamMember member;

	@BeforeEach
	void setUp() {
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		emails = mock(EmailOutboxService.class);
		when(emails.enqueue(any())).thenReturn(outboxRecord());
		StudentRemovalNotifier notifier = new StudentRemovalNotifier(
				new NotificationService(notificationRows, users, mock(ApplicationEventPublisher.class)),
				emails,
				new EmailTemplateService(authProperties));

		teamService = new LecturerTeamService(
				new LecturerCourseAuthorization(courses, lecturerProfiles),
				new JpaLecturerTeamStore(courses, enrollments, teams, teamMembers),
				mock(TeamPreviewStore.class),
				new RosterProperties(),
				authProperties,
				emails,
				mock(AuditService.class),
				null,
				transactionManager);
		teamService.setRemovalNotifier(notifier);
		rosterService = new CourseRosterService(
				new JpaCourseRosterStore(courses, users, students, enrollments, invitations, teamMembers, teams),
				mock(RosterPreviewStore.class),
				new RosterProperties(),
				authProperties,
				new InstitutionalEmailPolicy(authProperties),
				emails,
				mock(AuditService.class),
				null,
				transactionManager);
		rosterService.setRemovalNotifier(notifier);

		lecturer = users.save(account(AccountRole.LECTURER, "lecturer"));
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(lecturer);
		course = courses.save(course(lecturerProfiles.save(profile)));
		team = new Team();
		team.setCourse(course);
		team.setTeamNo(1);
		team.setName("SAGA Team");
		team = teams.save(team);
		leaderAccount = users.save(account(AccountRole.STUDENT, "leader"));
		memberAccount = users.save(account(AccountRole.STUDENT, "member"));
		leaderEnrollment = enroll(leaderAccount);
		memberEnrollment = enroll(memberAccount);
		leader = teamMembers.save(teamMember(leaderEnrollment, RoleInTeam.LEADER));
		member = teamMembers.save(teamMember(memberEnrollment, RoleInTeam.MEMBER));
	}

	@Test
	void teamRemovalDeletesTheMembershipKeepsTheEnrollmentAndNotifiesOnce() {
		LecturerCourseTeamsResponse after =
				teamService.removeMember(lecturer, course.getId(), member.getId(), "Moved to a smaller team", auditReq());

		assertThat(teamMembers.findById(member.getId())).isEmpty();
		assertThat(enrollments.findById(memberEnrollment.getId()).orElseThrow().getEnrollmentStatus())
				.isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(after.unassignedStudents()).extracting(row -> row.courseEnrollmentId())
				.containsExactly(memberEnrollment.getId());
		assertThat(notificationRows.countByRecipientUser_Id(memberAccount.getId())).isEqualTo(1);
		assertThat(notificationRows.countByRecipientUser_Id(leaderAccount.getId())).isZero();
		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		assertThat(mail.getValue().recipientUserId()).isEqualTo(memberAccount.getId());
		assertThat(mail.getValue().emailType()).isEqualTo("TEAM_REMOVED");
		assertThat(String.valueOf(mail.getValue().payload().get("textBody"))).contains("Reason: Moved to a smaller team");
	}

	@Test
	void courseWithdrawalByTheLecturerWithdrawsLeavesTheTeamAndNotifiesOnce() {
		rosterService.removeEnrollment(course.getId(), memberEnrollment.getId(), lecturer, "Transferred to SE1803", auditReq());

		assertThat(enrollments.findById(memberEnrollment.getId()).orElseThrow().getEnrollmentStatus())
				.isEqualTo(EnrollmentStatus.WITHDRAWN);
		assertThat(teamMembers.findById(member.getId())).isEmpty();
		assertThat(notificationRows.countByRecipientUser_Id(memberAccount.getId())).isEqualTo(1);
		ArgumentCaptor<EmailEnqueueRequest> mail = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(mail.capture());
		assertThat(mail.getValue().emailType()).isEqualTo("COURSE_WITHDRAWN");
		assertThat(String.valueOf(mail.getValue().payload().get("textBody")))
				.contains("Removed by: Lecturer")
				.contains("Reason: Transferred to SE1803");
	}

	@Test
	void refusedRemovalsWriteNothingAndNotifyNobody() {
		assertThatThrownBy(() -> teamService.removeMember(lecturer, course.getId(), leader.getId(), "Leaving", auditReq()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_LEADER_INVALID);
		assertThatThrownBy(() -> rosterService.removeEnrollment(
						course.getId(), leaderEnrollment.getId(), lecturer, "Leaving", auditReq()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT);
		assertThatThrownBy(() -> teamService.removeMember(lecturer, course.getId(), member.getId(), "  ", auditReq()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);

		assertThat(teamMembers.findById(leader.getId())).isPresent();
		assertThat(teamMembers.findById(member.getId())).isPresent();
		assertThat(enrollments.findById(leaderEnrollment.getId()).orElseThrow().getEnrollmentStatus())
				.isEqualTo(EnrollmentStatus.ACTIVE);
		assertThat(notificationRows.countByRecipientUser_Id(leaderAccount.getId())).isZero();
		assertThat(notificationRows.countByRecipientUser_Id(memberAccount.getId())).isZero();
		verify(emails, never()).enqueue(any());
	}

	@Test
	void anotherLecturerCannotRemoveFromThisCourse() {
		UserAccount stranger = users.save(account(AccountRole.LECTURER, "stranger"));
		LecturerProfile strangerProfile = new LecturerProfile();
		strangerProfile.setUserAccount(stranger);
		lecturerProfiles.save(strangerProfile);

		assertThatThrownBy(() -> teamService.removeMember(stranger, course.getId(), member.getId(), "Leaving", auditReq()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
		assertThat(teamMembers.findById(member.getId())).isPresent();
	}

	private CourseEnrollment enroll(UserAccount account) {
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

	private TeamMember teamMember(CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember row = new TeamMember();
		row.setTeam(team);
		row.setCourse(course);
		row.setCourseEnrollment(enrollment);
		row.setRoleInTeam(role);
		return row;
	}

	private Course course(LecturerProfile instructor) {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Fall 2026");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Test Class");
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWR" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Requirement");
		subject = subjects.save(subject);
		Course created = new Course();
		created.setName("SWR302 · " + academicClass.getClassCode());
		created.setAcademicClass(academicClass);
		created.setSemester(semester);
		created.setSubject(subject);
		created.setInstructor(instructor);
		return created;
	}

	private static UserAccount account(AccountRole role, String label) {
		UserAccount account = new UserAccount();
		account.setEmail(label + "-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName(label);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-remove", "127.0.0.1", "JUnit");
	}

	private static EmailOutboxRecord outboxRecord() {
		return new EmailOutboxRecord(
				UUID.randomUUID(),
				"x@fpt.edu.vn",
				"TEAM_REMOVED",
				"team-removed",
				EmailDeliveryStatus.PENDING,
				0,
				LocalDateTime.now(),
				null,
				null,
				null,
				LocalDateTime.now(),
				LocalDateTime.now());
	}
}

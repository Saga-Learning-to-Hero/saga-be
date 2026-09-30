package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditService;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.mail.EmailOutboxService;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real H2 + real JPA store + real transaction manager for {@code POST .../teams/{teamId}/members}:
 * proves the TEAM_ASSIGNED email can read the course/student graph inside the write transaction
 * (no lazy-loading failure once the transaction is real), the unique-per-enrollment membership
 * holds, and the unassigned list comes from the real fetched roster query. Lives in
 * {@code com.saga.be.repository} for the same TxSlice isolation reason as
 * {@link RosterRemovalVsLeaderReassignmentConcurrencyTest}.
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
class LecturerTeamAssignPersistTest {

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
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private AcademicClassRepository academicClasses;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository teamMembers;
	@Autowired
	private LecturerProfileRepository lecturerProfiles;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private LecturerTeamService teamService;
	private EmailOutboxService emails;
	private UserAccount admin;
	private Course course;
	private int teamNoSeq;

	@BeforeEach
	void setUp() {
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		emails = mock(EmailOutboxService.class);
		when(emails.enqueue(any())).thenReturn(sentRecord());
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
		admin = users.save(account(AccountRole.ADMIN, "admin"));
		course = courses.save(course());
	}

	@Test
	void unassignedStudentJoinsOccupiedTeamAsMemberAndGetsTeamAssignedEmail() {
		Team team = teams.save(team());
		teamMembers.save(member(team, activeEnrollment("leader"), RoleInTeam.LEADER));
		CourseEnrollment newcomer = activeEnrollment("newcomer");

		LecturerCourseTeamsResponse before = teamService.listTeams(admin, course.getId());
		assertThat(before.unassignedStudents())
				.extracting(row -> row.courseEnrollmentId())
				.containsExactly(newcomer.getId());

		LecturerCourseTeamsResponse after =
				teamService.assignStudent(admin, course.getId(), team.getId(), newcomer.getId(), auditReq());

		TeamMember saved = teamMembers.findByCourseEnrollment_Id(newcomer.getId()).orElseThrow();
		assertThat(saved.getRoleInTeam()).isEqualTo(RoleInTeam.MEMBER);
		assertThat(after.unassignedStudents()).isEmpty();
		assertThat(after.teams().getFirst().members()).hasSize(2);
		ArgumentCaptor<EmailEnqueueRequest> captor = ArgumentCaptor.forClass(EmailEnqueueRequest.class);
		verify(emails).enqueue(captor.capture());
		assertThat(captor.getValue().emailType()).isEqualTo("TEAM_ASSIGNED");
		assertThat(captor.getValue().recipientEmail()).startsWith("newcomer-");
	}

	@Test
	void unassignedStudentJoiningAnEmptyTeamBecomesLeader() {
		Team empty = teams.save(team());
		CourseEnrollment first = activeEnrollment("first");

		teamService.assignStudent(admin, course.getId(), empty.getId(), first.getId(), auditReq());

		assertThat(teamMembers.findByCourseEnrollment_Id(first.getId()).orElseThrow().getRoleInTeam())
				.isEqualTo(RoleInTeam.LEADER);
	}

	@Test
	void studentOnAnotherTeamIsMovedKeepingOneMembershipRow() {
		Team source = teams.save(team());
		teamMembers.save(member(source, activeEnrollment("sourceLeader"), RoleInTeam.LEADER));
		CourseEnrollment mover = activeEnrollment("mover");
		teamMembers.save(member(source, mover, RoleInTeam.MEMBER));
		Team target = teams.save(team());
		teamMembers.save(member(target, activeEnrollment("targetLeader"), RoleInTeam.LEADER));

		teamService.assignStudent(admin, course.getId(), target.getId(), mover.getId(), auditReq());

		TeamMember moved = teamMembers.findByCourseEnrollment_Id(mover.getId()).orElseThrow();
		assertThat(moved.getTeam().getId()).isEqualTo(target.getId());
		assertThat(moved.getRoleInTeam()).isEqualTo(RoleInTeam.MEMBER);
		assertThat(teamMembers.findAll().stream()
						.filter(row -> mover.getId().equals(row.getCourseEnrollment().getId()))
						.count())
				.isEqualTo(1);
	}

	@Test
	void withdrawnStudentIsNotFoundAndNothingIsWritten() {
		Team team = teams.save(team());
		CourseEnrollment gone = activeEnrollment("gone");
		gone.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
		enrollments.save(gone);

		assertThatThrownBy(() -> teamService.assignStudent(admin, course.getId(), team.getId(), gone.getId(), auditReq()))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.ROSTER_STUDENT_NOT_FOUND);
		assertThat(teamMembers.findByCourseEnrollment_Id(gone.getId())).isEmpty();
	}

	private CourseEnrollment activeEnrollment(String label) {
		UserAccount account = users.save(account(AccountRole.STUDENT, label));
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(("SE_" + label + "_" + account.getId()).substring(0, 20));
		profile = students.save(profile);
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollments.save(enrollment);
	}

	private UserAccount account(AccountRole role, String label) {
		UserAccount account = new UserAccount();
		account.setEmail(label + "-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName(label);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private Team team() {
		Team team = new Team();
		team.setCourse(course);
		team.setTeamNo(++teamNoSeq);
		team.setName("Team " + teamNoSeq);
		return team;
	}

	private TeamMember member(Team team, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private Course course() {
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
		return created;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-assign", "127.0.0.1", "JUnit");
	}

	private static EmailOutboxRecord sentRecord() {
		return new EmailOutboxRecord(
				UUID.randomUUID(),
				"assign@fpt.edu.vn",
				"TEAM_ASSIGNED",
				"team-assigned",
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

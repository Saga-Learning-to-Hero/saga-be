package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saga.be.auth.InstitutionalEmailPolicy;
import com.saga.be.config.AuthProperties;
import com.saga.be.config.RosterProperties;
import com.saga.be.dto.mail.EmailOutboxRecord;
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
import com.saga.be.service.roster.CourseRosterService;
import com.saga.be.service.roster.JpaCourseRosterStore;
import com.saga.be.service.roster.RosterPreviewStore;
import com.saga.be.service.team.JpaLecturerTeamStore;
import com.saga.be.service.team.LecturerTeamService;
import com.saga.be.service.team.TeamPreviewStore;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
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
 * Real H2 + real {@link PlatformTransactionManager} + the real {@link TeamRepository#findByIdForUpdate}
 * row lock. Proves the exact race described in the "Roster Removal Team Safety" audit cannot leave a
 * team with a LEADER whose enrollment is WITHDRAWN:
 *
 * <pre>
 * TX1 (ADMIN):    CourseRosterService.removeEnrollment(A)      — reads A as MEMBER, intends WITHDRAWN
 * TX2 (LECTURER): LecturerTeamService.replaceLeader(A)          — L -> MEMBER, A -> LEADER
 * </pre>
 *
 * Without a shared lock these two transactions touch disjoint rows (course_enrollment vs
 * team_member) and can interleave freely. The fix makes {@code removeEnrollment} take the same
 * {@code Team} pessimistic write lock {@code replaceLeader}/{@code moveMember} already take before
 * mutating any {@code roleInTeam}, then re-reads the membership under that lock. This serializes the
 * two transactions into exactly one of two safe outcomes — never a third, unsafe one.
 *
 * <p>Deliberately placed in {@code com.saga.be.repository} (not {@code com.saga.be.service.roster} or
 * {@code com.saga.be.security}): this class's nested {@code @SpringBootConfiguration TxSlice} would
 * otherwise be auto-detected by any plain {@code @SpringBootTest} in the same package that does not
 * declare {@code classes=}, hijacking their root application context — see
 * {@link PasswordResetConcurrencyTest} for the same precaution.
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
class RosterRemovalVsLeaderReassignmentConcurrencyTest {

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
	private StudentCourseInvitationRepository invitations;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository teamMembers;
	@Autowired
	private LecturerProfileRepository lecturerProfiles;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private CourseRosterService rosterService;
	private LecturerTeamService teamService;
	private UserAccount admin;
	private Course course;
	private int teamNoSeq;

	@BeforeEach
	void setUp() {
		AuthProperties authProperties = new AuthProperties();
		authProperties.setFrontendOrigins(List.of("http://localhost:3000"));
		EmailOutboxService emails = mock(EmailOutboxService.class);
		when(emails.enqueue(any())).thenReturn(sentRecord());

		JpaCourseRosterStore rosterStore = new JpaCourseRosterStore(
				courses, users, students, enrollments, invitations, teamMembers, teams);
		rosterService = new CourseRosterService(
				rosterStore,
				mock(RosterPreviewStore.class),
				new RosterProperties(),
				authProperties,
				new InstitutionalEmailPolicy(authProperties),
				emails,
				mock(AuditService.class),
				null,
				transactionManager);

		JpaLecturerTeamStore teamStore = new JpaLecturerTeamStore(courses, enrollments, teams, teamMembers);
		LecturerCourseAuthorization authorization = new LecturerCourseAuthorization(courses, lecturerProfiles);
		teamService = new LecturerTeamService(
				authorization,
				teamStore,
				mock(TeamPreviewStore.class),
				new RosterProperties(),
				authProperties,
				emails,
				mock(AuditService.class),
				null,
				transactionManager);

		admin = users.save(adminAccount());
		course = courses.save(course());
	}

	@RepeatedTest(15)
	void concurrentRemoveAndLeaderReassignmentNeverLeavesTeamWithoutAnActiveLeader() throws Exception {
		UserAccount leaderAccount = users.save(studentAccount("leader"));
		StudentProfile leaderProfile = students.save(studentProfile(leaderAccount, "SE_L_" + leaderAccount.getId()));
		CourseEnrollment leaderEnrollment = enrollments.save(activeEnrollment(leaderProfile));

		UserAccount memberAccount = users.save(studentAccount("member"));
		StudentProfile memberProfile = students.save(studentProfile(memberAccount, "SE_M_" + memberAccount.getId()));
		CourseEnrollment memberEnrollment = enrollments.save(activeEnrollment(memberProfile));

		Team team = teams.save(team(++teamNoSeq));
		TeamMember leaderMember = teamMembers.save(teamMember(team, leaderEnrollment, RoleInTeam.LEADER));
		TeamMember memberMember = teamMembers.save(teamMember(team, memberEnrollment, RoleInTeam.MEMBER));

		AtomicReference<AcademicException> removeFailure = new AtomicReference<>();
		AtomicReference<AcademicException> replaceFailure = new AtomicReference<>();
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);

		Thread removeThread = Thread.ofVirtual().start(() -> {
			try {
				start.await();
				rosterService.removeEnrollment(course.getId(), memberEnrollment.getId(), admin, auditReq());
			} catch (AcademicException ex) {
				removeFailure.set(ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} finally {
				done.countDown();
			}
		});
		Thread replaceThread = Thread.ofVirtual().start(() -> {
			try {
				start.await();
				teamService.replaceLeader(admin, course.getId(), team.getId(), memberMember.getId(), auditReq());
			} catch (AcademicException ex) {
				replaceFailure.set(ex);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			} finally {
				done.countDown();
			}
		});
		start.countDown();
		assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();
		removeThread.join();
		replaceThread.join();

		CourseEnrollment finalEnrollment = enrollments.findById(memberEnrollment.getId()).orElseThrow();
		var finalMembership = teamMembers.findByCourseEnrollment_Id(memberEnrollment.getId());

		// The invariant the audit asked us to protect: an occupied team must never end up with a
		// LEADER whose enrollment is not ACTIVE.
		boolean isLeaderButWithdrawn = finalMembership.isPresent()
				&& finalMembership.get().getRoleInTeam() == RoleInTeam.LEADER
				&& finalEnrollment.getEnrollmentStatus() != EnrollmentStatus.ACTIVE;
		assertThat(isLeaderButWithdrawn).isFalse();

		if (removeFailure.get() == null) {
			// removeEnrollment won the race: the former MEMBER's TeamMember row is gone and their
			// enrollment is WITHDRAWN; replaceLeader must have lost (the row it targeted no
			// longer exists once it acquires the lock).
			assertThat(finalEnrollment.getEnrollmentStatus()).isEqualTo(EnrollmentStatus.WITHDRAWN);
			assertThat(finalMembership).isEmpty();
			assertThat(replaceFailure.get()).isNotNull();
			assertThat(replaceFailure.get().getCode()).isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND);
		} else {
			// replaceLeader won the race: the member is now LEADER and still ACTIVE; removal must
			// have lost, refused by the fresh LEADER re-check under the same lock.
			assertThat(finalEnrollment.getEnrollmentStatus()).isEqualTo(EnrollmentStatus.ACTIVE);
			assertThat(finalMembership).isPresent();
			assertThat(finalMembership.get().getRoleInTeam()).isEqualTo(RoleInTeam.LEADER);
			assertThat(removeFailure.get().getCode())
					.isEqualTo(AcademicErrorCode.TEAM_LEADER_REMOVAL_REQUIRES_REASSIGNMENT);
		}

		// The original Leader (L) is never silently left as the sole occupant without a role
		// change unless replaceLeader actually ran that path — sanity-check L's row still exists.
		assertThat(teamMembers.findById(leaderMember.getId())).isPresent();
	}

	private UserAccount adminAccount() {
		UserAccount account = new UserAccount();
		account.setEmail("admin-" + UUID.randomUUID() + "@saga.local");
		account.setAccountRole(AccountRole.ADMIN);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private UserAccount studentAccount(String label) {
		UserAccount account = new UserAccount();
		account.setEmail(label + "-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName(label);
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private StudentProfile studentProfile(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code.length() > 64 ? code.substring(0, 64) : code);
		return profile;
	}

	private CourseEnrollment activeEnrollment(StudentProfile profile) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollment;
	}

	private Team team(int teamNo) {
		Team team = new Team();
		team.setCourse(course);
		team.setTeamNo(teamNo);
		team.setName("Team " + teamNo);
		return team;
	}

	private TeamMember teamMember(Team team, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private Course course() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course created = new Course();
		created.setName("SWP391 · " + academicClass.getClassCode());
		created.setAcademicClass(academicClass);
		created.setSemester(semester);
		created.setSubject(subject);
		return created;
	}

	private Semester semester() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		return semester;
	}

	private AcademicClass academicClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE170" + (int) (Math.random() * 1000));
		academicClass.setName("Test Class");
		return academicClass;
	}

	private Subject subject() {
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		return subject;
	}

	private static AuditRequest auditReq() {
		return new AuditRequest("req-race", "127.0.0.1", "JUnit");
	}

	private static EmailOutboxRecord sentRecord() {
		return new EmailOutboxRecord(
				UUID.randomUUID(),
				"race@fpt.edu.vn",
				"COURSE_ENROLLED",
				"course-enrolled",
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

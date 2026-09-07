package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.project.ProjectCommitResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves GET /commits read path query count does not grow with commit cardinality (no per-commit
 * lazy loads of repo / authorStudent / linked tasks).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProjectCommitListQueryCountTest {

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
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
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
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private TransactionTemplate tx;
	private UserAccount student;
	private Project project;
	private GitRepo repo;
	private StudentProfile author;
	private ProjectProjectionReadService readService;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		readService = new ProjectProjectionReadService(tasks, commits, links, authorization);
		tx.executeWithoutResult(status -> seedGraph());
	}

	@Test
	void listCommits_queryCountStableFor10And100() {
		seedCommits(10);
		long queriesFor10 = measureListCommits(10);
		seedCommits(90);
		long queriesFor100 = measureListCommits(100);
		assertThat(queriesFor10).as("exact query count for 10 commits").isEqualTo(3L);
		assertThat(queriesFor100).as("exact query count for 100 commits").isEqualTo(3L);
		assertThat(queriesFor100).isEqualTo(queriesFor10);
	}

	private long measureListCommits(int expectedSize) {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		List<ProjectCommitResponse> rows = tx.execute(status -> readService.listCommits(student.getId(), project.getId()));
		assertThat(rows).hasSize(expectedSize);
		rows.forEach(row -> {
			assertThat(row.repoId()).isNotNull();
			assertThat(row.repositoryFullName()).isEqualTo("org/demo");
			assertThat(row.authorStudentId()).isEqualTo(author.getId());
			assertThat(row.sha()).doesNotContain("token");
		});
		return stats.getPrepareStatementCount();
	}

	private void seedCommits(int count) {
		tx.executeWithoutResult(status -> {
			long existing = commits.countByRepo_Project_Id(project.getId());
			for (int i = 0; i < count; i++) {
				GitCommit commit = new GitCommit();
				commit.setRepo(repo);
				commit.setAuthorStudent(author);
				commit.setShaHash(String.format("%040d", existing + i + 1));
				commit.setMessage("SAGA-" + (existing + i + 1) + " work");
				commit.setAuthorExternalId("gh-" + (existing + i));
				commit.setCommittedAt(LocalDateTime.now().minusMinutes(existing + i));
				commits.save(commit);
			}
			entityManager.flush();
		});
	}

	private void seedGraph() {
		student = new UserAccount();
		student.setEmail("student-" + UUID.randomUUID() + "@fe.edu.vn");
		student.setFullName("Student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student = users.save(student);

		author = new StudentProfile();
		author.setUserAccount(student);
		author.setStudentCode("SE" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
		author.setVersion(0L);
		author = students.save(author);

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Semester semester = new Semester();
		semester.setCode("FA" + suffix);
		semester.setName("Fall");
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
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		course = courses.save(course);

		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(author);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		enrollment = enrollments.save(enrollment);

		project = new Project();
		project.setName("Saga");
		project.setCourse(course);
		project.setCreatedBy(student);
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
		member.setRoleInTeam(RoleInTeam.MEMBER);
		members.save(member);

		repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(System.nanoTime());
		repo.setOwnerLogin("org");
		repo.setName("demo");
		repo.setFullName("org/demo");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		repo = repos.save(repo);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.project.TaskParentOptionItem;
import com.saga.be.dto.project.TaskParentOptionsResponse;
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
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.JiraIntegrationRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /tasks/parent-options: DB paging, title ASC / id ASC, prefix q, and no page clamping.
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
class ParentOptionsPagingQueryCountTest {

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
	private TaskRepository tasks;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private TransactionTemplate tx;
	private UserAccount student;
	private Project project;
	private Project otherProject;
	private JiraIntegration jiraIntegration;
	private JiraIntegration otherJiraIntegration;
	private ProjectProjectionReadService readService;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		readService = new ProjectProjectionReadService(
				tasks,
				commits,
				links,
				org.mockito.Mockito.mock(com.saga.be.repository.SprintRepository.class),
				authorization,
				new TaskHierarchyService(projects, tasks, transactionManager),
				org.mockito.Mockito.mock(com.saga.be.repository.JiraTaskFailoverItemRepository.class));
		tx.executeWithoutResult(status -> seedGraph());
	}

	@Test
	void queryCountStableForEmptyOneAndFullPage() {
		Measured empty = measure(0, 20);
		assertThat(empty.page.items()).isEmpty();
		assertThat(empty.page.total()).isZero();
		assertThat(empty.queries).as("empty: auth + id page, count skipped").isEqualTo(3L);

		seedTasks(1);
		Measured one = measure(0, 20);
		assertThat(one.page.items()).hasSize(1);
		assertThat(one.page.total()).isEqualTo(1);
		assertThat(one.queries).as("1-row short page matches empty (count skipped)").isEqualTo(empty.queries);

		seedTasks(19);
		Measured full = measure(0, 20);
		assertThat(full.page.items()).hasSize(20);
		assertThat(full.page.total()).isEqualTo(20);
		assertThat(full.queries).as("full page of 20: auth + page + count").isEqualTo(4L);
	}

	@Test
	void pagesEdgesDefaultsAndInvalid() {
		seedNamedOrder();
		TaskParentOptionsResponse defaults = list(null, 0, 20);
		assertThat(defaults.page()).isZero();
		assertThat(defaults.size()).isEqualTo(20);
		assertThat(defaults.total()).isEqualTo(5);
		assertThat(defaults.items()).extracting(TaskParentOptionItem::title)
				.containsExactly("Alpha", "Alpha", "Beta", "Gamma", "Zeta");

		TaskParentOptionsResponse page0 = list(null, 0, 2);
		assertThat(page0.items()).extracting(TaskParentOptionItem::externalKey).containsExactly("SAGA-A1", "SAGA-A2");

		TaskParentOptionsResponse second = list(null, 1, 2);
		assertThat(second.items()).extracting(TaskParentOptionItem::externalKey).containsExactly("SAGA-B", "SAGA-G");
		assertThat(second.page()).isEqualTo(1);
		assertThat(second.total()).isEqualTo(5);

		TaskParentOptionsResponse lastPartial = list(null, 2, 2);
		assertThat(lastPartial.items()).extracting(TaskParentOptionItem::externalKey).containsExactly("SAGA-Z");

		TaskParentOptionsResponse beyond = list(null, 9, 20);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.total()).isEqualTo(5);

		assertThat(list(null, 0, 1).items()).extracting(TaskParentOptionItem::externalKey).containsExactly("SAGA-A1");
		assertThat(list(null, 0, 50).items()).hasSize(5);
		assertThat(list(null, 0, 50).size()).isEqualTo(50);

		assertThatThrownBy(() -> list(null, -1, 20)).satisfies(this::assertInvalid);
		assertThatThrownBy(() -> list(null, 0, 0)).satisfies(this::assertInvalid);
		assertThatThrownBy(() -> list(null, 0, 51)).satisfies(this::assertInvalid);
	}

	@Test
	void identicalTitlesOrderByIdAscAndPagesAreStable() {
		seedNamedOrder();
		List<UUID> first = list(null, 0, 20).items().stream().map(TaskParentOptionItem::id).toList();
		List<UUID> second = list(null, 0, 20).items().stream().map(TaskParentOptionItem::id).toList();
		assertThat(first).containsExactly(
				UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
				UUID.fromString("00000000-0000-4000-8000-0000000000a2"),
				UUID.fromString("00000000-0000-4000-8000-0000000000b0"),
				UUID.fromString("00000000-0000-4000-8000-0000000000c0"),
				UUID.fromString("00000000-0000-4000-8000-0000000000d0"));
		assertThat(second).containsExactlyElementsOf(first);
		assertThat(list(null, 0, 2).items()).extracting(TaskParentOptionItem::id)
				.containsExactly(first.get(0), first.get(1));
		assertThat(list(null, 1, 2).items()).extracting(TaskParentOptionItem::id)
				.containsExactly(first.get(2), first.get(3));
	}

	@Test
	void qRemainsPrefixOnTitleAndExternalKey() {
		seedNamedOrder();
		assertThat(list("Al", 0, 20).items()).extracting(TaskParentOptionItem::externalKey)
				.containsExactly("SAGA-A1", "SAGA-A2");
		assertThat(list("SAGA-B", 0, 20).items()).extracting(TaskParentOptionItem::externalKey)
				.containsExactly("SAGA-B");
		assertThat(list("lpha", 0, 20).items()).isEmpty();
		assertThat(list("AGA-B", 0, 20).items()).isEmpty();
		assertThat(list("  ", 0, 20).total()).isEqualTo(5);
	}

	@Test
	void excludesDeletedForeignAndDescendantsWithoutFullGraphLoad() {
		UUID childId = UUID.fromString("00000000-0000-4000-8000-0000000000c1");
		tx.executeWithoutResult(status -> {
			Task root = persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000aa"),
					"Root",
					"SAGA-R",
					null);
			Task child = persistTask(childId, "Child", "SAGA-C", root);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000bb"),
					"Grand",
					"SAGA-G",
					child);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000cc"),
					"Other",
					"SAGA-O",
					null);
			Task deleted = persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000dd"),
					"Deleted",
					"SAGA-X",
					null);
			deleted.setDeletedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
			tasks.save(deleted);
			Task foreign = new Task();
			foreign.setId(UUID.fromString("00000000-0000-4000-8000-0000000000ee"));
			foreign.setProject(otherProject);
			foreign.setJiraIntegration(otherJiraIntegration);
			foreign.setTitle("Foreign");
			foreign.setStatus(TaskStatus.TODO);
			foreign.setExternalKey("SAGA-F");
			foreign.setExternalId("ext-f");
			tasks.save(foreign);
			entityManager.flush();
		});

		TaskParentOptionsResponse all = list(null, 0, 20);
		assertThat(all.items()).extracting(TaskParentOptionItem::externalKey)
				.containsExactly("SAGA-C", "SAGA-G", "SAGA-O", "SAGA-R")
				.doesNotContain("SAGA-X", "SAGA-F");

		TaskParentOptionsResponse excluded = list(null, 0, 20, childId);
		assertThat(excluded.items()).extracting(TaskParentOptionItem::externalKey)
				.containsExactly("SAGA-O", "SAGA-R")
				.doesNotContain("SAGA-C", "SAGA-G");
		assertThat(excluded.total()).isEqualTo(2);
	}

	private void assertInvalid(Throwable ex) {
		assertThat(ex).isInstanceOf(AcademicException.class);
		AcademicException academic = (AcademicException) ex;
		assertThat(academic.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThat(academic.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private Measured measure(int page, int size) {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		TaskParentOptionsResponse response = list(null, page, size);
		return new Measured(response, stats.getPrepareStatementCount());
	}

	private TaskParentOptionsResponse list(String q, int page, int size) {
		return list(q, page, size, null);
	}

	private TaskParentOptionsResponse list(String q, int page, int size, UUID excludeTaskId) {
		return tx.execute(
				status -> readService.listParentOptions(student.getId(), project.getId(), q, page, size, excludeTaskId));
	}

	private void seedTasks(int count) {
		tx.executeWithoutResult(status -> {
			long existing = tasks.count();
			Project managed = projects.findById(project.getId()).orElseThrow();
			for (int i = 0; i < count; i++) {
				long n = existing + i + 1;
				Task task = new Task();
				task.setProject(managed);
				task.setJiraIntegration(jiraIntegration);
				task.setTitle("T" + n);
				task.setStatus(TaskStatus.TODO);
				task.setExternalKey("SAGA-" + n);
				task.setExternalId("ext-" + n);
				tasks.save(task);
			}
			entityManager.flush();
		});
	}

	private void seedNamedOrder() {
		tx.executeWithoutResult(status -> {
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000a2"),
					"Alpha",
					"SAGA-A2",
					null);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000a1"),
					"Alpha",
					"SAGA-A1",
					null);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000b0"),
					"Beta",
					"SAGA-B",
					null);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000d0"),
					"Zeta",
					"SAGA-Z",
					null);
			persistTask(
					UUID.fromString("00000000-0000-4000-8000-0000000000c0"),
					"Gamma",
					"SAGA-G",
					null);
			entityManager.flush();
		});
	}

	private Task persistTask(UUID id, String title, String key, Task parent) {
		Task task = new Task();
		task.setId(id);
		task.setProject(projects.findById(project.getId()).orElseThrow());
		task.setJiraIntegration(jiraIntegration);
		task.setTitle(title);
		task.setStatus(TaskStatus.TODO);
		task.setExternalKey(key);
		task.setExternalId("ext-" + key);
		if (parent != null) {
			task.setParentTask(tasks.findById(parent.getId()).orElseThrow());
		}
		return tasks.save(task);
	}

	private void seedGraph() {
		student = new UserAccount();
		student.setEmail("student-" + UUID.randomUUID() + "@fe.edu.vn");
		student.setFullName("Student");
		student.setAccountRole(AccountRole.STUDENT);
		student.setAccountStatus(AccountStatus.ACTIVE);
		student = users.save(student);

		StudentProfile author = new StudentProfile();
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

		jiraIntegration = jiraIntegrations.save(jiraFor(project));

		otherProject = new Project();
		otherProject.setName("Other");
		otherProject.setCourse(course);
		otherProject.setCreatedBy(student);
		otherProject = projects.save(otherProject);
		otherJiraIntegration = jiraIntegrations.save(jiraFor(otherProject));

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
	}

	private static JiraIntegration jiraFor(Project project) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		return integration;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private record Measured(TaskParentOptionsResponse page, long queries) {}
}

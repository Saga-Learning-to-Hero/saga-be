package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.project.TaskEvidenceGroupedResponse;
import com.saga.be.dto.project.TaskEvidenceItem;
import com.saga.be.dto.project.TaskEvidencePageResponse;
import com.saga.be.dto.project.TaskEvidenceType;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.EvidenceSource;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.jira.TaskAttachment;
import com.saga.be.entity.jira.TaskFile;
import com.saga.be.entity.jira.TaskWebLink;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.service.projection.ProjectDataAuthorization;
import com.saga.be.service.projection.ProjectTaskEvidenceReadService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
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
class TaskEvidenceReadQueryCountTest {

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
	private LecturerProfileRepository lecturers;
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
	private TaskGitCommitLinkRepository commitLinks;
	@Autowired
	private TaskFileRepository files;
	@Autowired
	private TaskWebLinkRepository webLinks;
	@Autowired
	private TaskAttachmentRepository attachments;
	@Autowired
	private TaskWorkSessionRepository workSessions;
	@Autowired
	private ContributionConfirmationRepository confirmations;

	private TransactionTemplate tx;
	private ProjectTaskEvidenceReadService service;
	private UserAccount student;
	private UserAccount lecturerUser;
	private Project project;
	private Project otherProject;
	private Task task;
	private GitRepo repo;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		service = new ProjectTaskEvidenceReadService(
				new ProjectDataAuthorization(users, members, projects),
				tasks,
				commitLinks,
				commits,
				files,
				webLinks);
		tx.executeWithoutResult(status -> seedGraph());
	}

	@Test
	void groupedPreview_capsPerType_excludesNonEvidenceAndUnlinkedCommit() {
		tx.executeWithoutResult(status -> seedEvidenceSet(3));
		TaskEvidenceGroupedResponse response = tx.execute(
				status -> (TaskEvidenceGroupedResponse)
						service.list(student.getId(), project.getId(), task.getId(), null, 0, 2));
		assertThat(response.groups().COMMIT().total()).isEqualTo(3);
		assertThat(response.groups().COMMIT().items()).hasSize(2);
		assertThat(response.groups().COMMIT().items())
				.extracting(item -> item.commit().message())
				.containsExactly("linked-2", "linked-1");
		assertThat(response.groups().COMMIT().items())
				.extracting(item -> item.commit().message())
				.doesNotContain("unlinked");
		assertThat(response.groups().FILE().total()).isEqualTo(3);
		assertThat(response.groups().FILE().items()).hasSize(2);
		TaskEvidenceGroupedResponse full = tx.execute(
				status -> (TaskEvidenceGroupedResponse)
						service.list(student.getId(), project.getId(), task.getId(), null, 0, 20));
		assertThat(full.groups().FILE().items()).hasSize(3);
		assertThat(full.groups().FILE().total()).isEqualTo(3);
		assertThat(full.groups().FILE().items())
				.extracting(TaskEvidenceItem::source)
				.contains("JIRA", "SAGA");
		assertThat(attachments.findByTask_Id(task.getId())).hasSize(1);
		assertThat(response.groups().FILE().items()).allSatisfy(item -> {
			assertThat(item.file().downloadPath()).startsWith("/api/tasks/" + task.getId() + "/files/");
			assertThat(item.file().downloadPath()).doesNotContain("data");
		});
		assertThat(response.groups().WEB_LINK().total()).isEqualTo(3);
		assertThat(response.groups().WEB_LINK().items()).hasSize(2);
		assertThat(response.groups().WEB_LINK().items())
				.noneMatch(item -> "session".equals(item.title()) || "confirmation".equals(item.title()));
	}

	@Test
	void lecturer_readsJiraAndSagaWebLinks() {
		tx.executeWithoutResult(status -> seedEvidenceSet(1));
		TaskEvidenceGroupedResponse response = tx.execute(
				status -> (TaskEvidenceGroupedResponse)
						service.list(lecturerUser.getId(), project.getId(), task.getId(), null, null, 20));
		assertThat(response.groups().WEB_LINK().total()).isEqualTo(1);
		assertThat(response.groups().WEB_LINK().items()).extracting(item -> item.webLink().url()).isNotEmpty();
	}

	@Test
	void typedPaging_isDeterministicAndCappedAt50() {
		tx.executeWithoutResult(status -> seedEvidenceSet(5));
		TaskEvidencePageResponse page0 = tx.execute(
				status -> (TaskEvidencePageResponse)
						service.list(student.getId(), project.getId(), task.getId(), "FILE", 0, 2));
		TaskEvidencePageResponse page1 = tx.execute(
				status -> (TaskEvidencePageResponse)
						service.list(student.getId(), project.getId(), task.getId(), "FILE", 1, 2));
		assertThat(page0.total()).isEqualTo(5);
		assertThat(page0.items()).hasSize(2);
		assertThat(page1.items()).hasSize(2);
		assertThat(page0.items().getFirst().id()).isNotEqualTo(page1.items().getFirst().id());
		TaskEvidencePageResponse max = tx.execute(
				status -> (TaskEvidencePageResponse)
						service.list(student.getId(), project.getId(), task.getId(), "COMMIT", 0, 50));
		assertThat(max.size()).isEqualTo(50);
		assertThat(max.items()).hasSize(5);
		TaskEvidencePageResponse links = tx.execute(
				status -> (TaskEvidencePageResponse)
						service.list(student.getId(), project.getId(), task.getId(), "WEB_LINK", 0, 2));
		assertThat(links.total()).isEqualTo(5);
		assertThat(links.items()).hasSize(2);
	}

	@Test
	void foreignProjectTaskIsNotFound() {
		tx.executeWithoutResult(status -> seedEvidenceSet(1));
		tx.executeWithoutResult(status -> {
			try {
				service.list(lecturerUser.getId(), otherProject.getId(), task.getId(), null, 0, 20);
				throw new AssertionError("expected not found");
			} catch (com.saga.be.exception.AcademicException ex) {
				assertThat(ex.getCode()).isEqualTo(com.saga.be.exception.AcademicErrorCode.PROJECT_NOT_FOUND);
			}
		});
	}

	@Test
	void groupedAndTypedQueryCountsDoNotGrowWithRowCount() {
		tx.executeWithoutResult(status -> seedEvidenceSet(25));
		long grouped25 = measureGrouped();
		long typedFile25 = measureTyped(TaskEvidenceType.FILE);
		long typedCommit25 = measureTyped(TaskEvidenceType.COMMIT);
		long typedLink25 = measureTyped(TaskEvidenceType.WEB_LINK);
		tx.executeWithoutResult(status -> seedEvidenceSet(20));
		long grouped45 = measureGrouped();
		long typedFile45 = measureTyped(TaskEvidenceType.FILE);
		long typedCommit45 = measureTyped(TaskEvidenceType.COMMIT);
		long typedLink45 = measureTyped(TaskEvidenceType.WEB_LINK);
		assertThat(grouped25).as("grouped query count is stable once each type fills the page").isEqualTo(grouped45);
		assertThat(typedFile25).as("typed FILE query count").isEqualTo(typedFile45);
		assertThat(typedCommit25).as("typed COMMIT query count").isEqualTo(typedCommit45);
		assertThat(typedLink25).as("typed WEB_LINK query count").isEqualTo(typedLink45);
		assertThat(grouped25).isEqualTo(10L);
		assertThat(typedFile25).isEqualTo(5L);
		assertThat(typedCommit25).isEqualTo(6L);
		assertThat(typedLink25).isEqualTo(5L);
	}

	private long measureGrouped() {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		tx.executeWithoutResult(status -> service.list(student.getId(), project.getId(), task.getId(), null, 0, 20));
		return stats.getPrepareStatementCount();
	}

	private long measureTyped(TaskEvidenceType type) {
		tx.executeWithoutResult(status -> entityManager.clear());
		Statistics stats = statistics();
		stats.clear();
		tx.executeWithoutResult(
				status -> service.list(student.getId(), project.getId(), task.getId(), type.name(), 0, 20));
		return stats.getPrepareStatementCount();
	}

	private void seedEvidenceSet(int count) {
		long existingFiles = files.countByTask_Id(task.getId());
		long existingLinks = webLinks.countByTask_Id(task.getId());
		long existingCommits = commitLinks.countByTask_Id(task.getId());
		for (int i = 0; i < count; i++) {
			int n = (int) existingCommits + i;
			GitCommit commit = new GitCommit();
			commit.setRepo(repo);
			commit.setShaHash(String.format("%040d", 1000 + n));
			commit.setMessage("linked-" + n);
			commit.setCommittedAt(LocalDateTime.of(2026, 9, 1, 0, 0).plusMinutes(n));
			commit = commits.save(commit);
			TaskGitCommitLink link = new TaskGitCommitLink();
			link.setTask(task);
			link.setGitCommit(commit);
			link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
			commitLinks.save(link);
		}
		if (existingCommits == 0) {
			GitCommit unlinked = new GitCommit();
			unlinked.setRepo(repo);
			unlinked.setShaHash("ffffffffffffffffffffffffffffffffffffffff");
			unlinked.setMessage("unlinked");
			unlinked.setCommittedAt(LocalDateTime.of(2026, 9, 2, 0, 0));
			commits.save(unlinked);
		}
		for (int i = 0; i < count; i++) {
			int n = (int) existingFiles + i;
			TaskFile file = new TaskFile();
			file.setTask(task);
			file.setOriginalFilename("file-" + n + ".pdf");
			file.setMimeType("application/pdf");
			file.setSizeBytes(10 + n);
			file.setContentHash(String.format("%064d", n + 1));
			file.setSource(n == 0 ? EvidenceSource.JIRA : EvidenceSource.SAGA);
			file.setExternalId(n == 0 ? "jira-att-1" : null);
			files.save(file);
			if (n == 0) {
				TaskAttachment attachment = new TaskAttachment();
				attachment.setTask(task);
				attachment.setExternalId("jira-att-1");
				attachment.setFilename("file-0.pdf");
				attachments.save(attachment);
			}
		}
		for (int i = 0; i < count; i++) {
			int n = (int) existingLinks + i;
			TaskWebLink link = new TaskWebLink();
			link.setTask(task);
			link.setUrl("https://example.com/" + n);
			link.setUrlHash(String.format("%064d", n + 1));
			link.setTitle("link-" + n);
			link.setSource(n == 0 ? EvidenceSource.JIRA : EvidenceSource.SAGA);
			webLinks.save(link);
		}
		if (existingFiles == 0) {
			TaskWorkSession session = new TaskWorkSession();
			session.setTask(task);
			session.setUser(student);
			session.setProject(project);
			session.setStartedAt(LocalDateTime.now());
			session.setStatus(WorkSessionStatus.OPEN);
			workSessions.save(session);
			ContributionConfirmation confirmation = new ContributionConfirmation();
			confirmation.setTask(task);
			confirmation.setUser(student);
			confirmation.setProject(project);
			confirmation.setEventState(ConfirmationEvent.CONFIRMED);
			confirmation.setConfirmationMethod(ConfirmationMethod.PASSWORD_STEP_UP);
			confirmation.setEvidenceHash("abc");
			confirmation.setEvidenceSnapshotJson("{\"commits\":[]}");
			confirmations.save(confirmation);
		}
		entityManager.flush();
	}

	private void seedGraph() {
		student = account(AccountRole.STUDENT, "student");
		lecturerUser = account(AccountRole.LECTURER, "lecturer");
		LecturerProfile lecturer = new LecturerProfile();
		lecturer.setUserAccount(lecturerUser);
		lecturer = lecturers.save(lecturer);

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
		course.setInstructor(lecturer);
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

		otherProject = new Project();
		otherProject.setName("Other");
		otherProject.setCourse(course);
		otherProject.setCreatedBy(student);
		otherProject = projects.save(otherProject);

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

		task = new Task();
		task.setProject(project);
		task.setTitle("Login");
		task.setStatus(TaskStatus.TODO);
		task.setExternalKey("SAGA-1");
		task = tasks.save(task);
	}

	private UserAccount account(AccountRole role, String prefix) {
		UserAccount account = new UserAccount();
		account.setEmail(prefix + "-" + UUID.randomUUID() + "@fe.edu.vn");
		account.setFullName(prefix);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return users.save(account);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

/**
 * Real H2, real JPQL — proves the progress-dashboard aggregate queries added to {@link
 * TaskRepository} and {@link TaskGitCommitLinkRepository} genuinely exclude soft-deleted
 * ({@code deleted_at IS NOT NULL}) Tasks at the database level, not merely in service-layer
 * filtering that a pure-mock unit test could not catch.
 *
 * <p>Deliberately placed in {@code com.saga.be.repository} (not {@code com.saga.be.service.projection}):
 * this class's nested {@code @SpringBootConfiguration TxSlice} would otherwise be auto-detected by
 * any plain {@code @SpringBootTest} in the same package that does not declare {@code classes=} —
 * see {@link PasswordResetConcurrencyTest} for the same precaution.
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
class TaskProgressSoftDeleteQueryTest {

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
	private ProjectRepository projects;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskGitCommitLinkRepository links;

	private Project project;
	private StudentProfile student;
	private Task activeTask;
	private Task deletedTask;

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));
		project = projects.save(project(course));

		UserAccount account = users.save(studentAccount());
		student = students.save(studentProfile(account));

		activeTask = tasks.save(task(project, student, TaskStatus.DONE, null));
		deletedTask = tasks.save(task(project, student, TaskStatus.DONE, LocalDateTime.now()));

		GitRepo repo = repos.save(gitRepo(project));
		GitCommit commitForActiveTask = commits.save(gitCommit(repo, student, "sha-active"));
		GitCommit commitForDeletedTaskOnly = commits.save(gitCommit(repo, student, "sha-deleted-only"));
		links.save(link(activeTask, commitForActiveTask));
		links.save(link(deletedTask, commitForDeletedTaskOnly));
	}

	@Test
	void deletedTaskExcludedFromProjectTaskStatusSummary() {
		List<Object[]> rows = tasks.countGroupedByAssigneeAndStatus(project.getId());
		long total = rows.stream().mapToLong(row -> (Long) row[2]).sum();
		assertThat(total).isEqualTo(1); // only activeTask; deletedTask must not inflate the total
	}

	@Test
	void deletedTaskExcludedFromCourseOverviewStatusAggregate() {
		List<Object[]> rows = tasks.countGroupedByStatusForProjects(List.of(project.getId()));
		long total = rows.stream().mapToLong(row -> (Long) row[2]).sum();
		assertThat(total).isEqualTo(1);
	}

	@Test
	void deletedTaskExcludedFromMemberAssignedTaskList() {
		List<Task> assigned =
				tasks.findActiveFetchedByProject_IdAndAssigneeStudent_Id(project.getId(), student.getId());
		assertThat(assigned).extracting(Task::getId).containsExactly(activeTask.getId());
	}

	@Test
	void deletedTaskDoesNotInflateLinkedCommitOrTasksWithLinkedCommitsCounts() {
		List<Object[]> rows = links.countLinkedCommitsAndTasksGroupedByAuthorStudent(project.getId());
		assertThat(rows).hasSize(1);
		Object[] row = rows.getFirst();
		assertThat(row[0]).isEqualTo(student.getId());
		// Only the commit linked to the ACTIVE task counts — the commit whose sole link points at
		// the soft-deleted task is excluded entirely, not just its task-count contribution.
		assertThat((Long) row[1]).isEqualTo(1L); // distinct linked commit count
		assertThat((Long) row[2]).isEqualTo(1L); // distinct tasksWithLinkedCommits
	}

	@Test
	void deletedTaskDoesNotInflateProjectWideLinkedCommitTotal() {
		long linked = links.countDistinctLinkedCommitsByProject_Id(project.getId());
		assertThat(linked).isEqualTo(1);
	}

	private static Semester semester() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		return semester;
	}

	private static AcademicClass academicClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + (int) (Math.random() * 100000));
		academicClass.setName("Test Class");
		return academicClass;
	}

	private static Subject subject() {
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		return subject;
	}

	private static Course course(AcademicClass academicClass, Subject subject, Semester semester) {
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		return course;
	}

	private static Project project(Course course) {
		Project project = new Project();
		project.setName("Project");
		project.setCourse(course);
		return project;
	}

	private static UserAccount studentAccount() {
		UserAccount account = new UserAccount();
		account.setEmail("student-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName("Student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}

	private static StudentProfile studentProfile(UserAccount account) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		return profile;
	}

	private static Task task(Project project, StudentProfile assignee, TaskStatus status, LocalDateTime deletedAt) {
		Task task = new Task();
		task.setProject(project);
		task.setAssigneeStudent(assignee);
		task.setExternalKey("SAGA-" + UUID.randomUUID().toString().substring(0, 6));
		task.setTitle("Task");
		task.setStatus(status);
		task.setDeletedAt(deletedAt);
		return task;
	}

	private static GitRepo gitRepo(Project project) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setFullName("org/repo");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		return repo;
	}

	private static GitCommit gitCommit(GitRepo repo, StudentProfile author, String sha) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setAuthorStudent(author);
		commit.setShaHash(sha);
		commit.setCommittedAt(LocalDateTime.now());
		return commit;
	}

	private static TaskGitCommitLink link(Task task, GitCommit commit) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		return link;
	}
}

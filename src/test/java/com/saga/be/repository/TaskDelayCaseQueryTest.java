package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.delay.TaskChangeLog;
import com.saga.be.entity.delay.TaskDelayCase;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.DelayCaseEnums.TaskChangeField;
import com.saga.be.entity.enums.DelayCaseStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Real H2, real JPQL for the delay case feature: scan candidates, evidence summaries, case queries. */
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
class TaskDelayCaseQueryTest {

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

	private static final LocalDateTime START_OF_TODAY = LocalDateTime.of(2026, 10, 6, 0, 0);
	private static final LocalDateTime WINDOW_START = START_OF_TODAY.minusDays(7);

	@Autowired private UserAccountRepository users;
	@Autowired private StudentProfileRepository students;
	@Autowired private LecturerProfileRepository lecturers;
	@Autowired private SubjectRepository subjects;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SemesterRepository semesters;
	@Autowired private CourseRepository courses;
	@Autowired private ProjectRepository projects;
	@Autowired private JiraIntegrationRepository jiraIntegrations;
	@Autowired private TaskRepository tasks;
	@Autowired private TaskDelayCaseRepository cases;
	@Autowired private TaskChangeLogRepository changeLogs;
	@Autowired private GitRepoRepository repos;
	@Autowired private GitCommitRepository commits;
	@Autowired private TaskGitCommitLinkRepository links;
	@Autowired private TaskWorkSessionRepository workSessions;

	private Project project;
	private JiraIntegration source;
	private StudentProfile student;
	private UserAccount studentAccount;
	private UserAccount lecturerAccount;
	private Task overdue;

	@BeforeEach
	void setUp() {
		lecturerAccount = users.save(account(AccountRole.LECTURER));
		LecturerProfile lecturer = new LecturerProfile();
		lecturer.setUserAccount(lecturerAccount);
		lecturer = lecturers.save(lecturer);
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Semester");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Class");
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		course.setInstructor(lecturer);
		course = courses.save(course);
		project = new Project();
		project.setName("Project");
		project.setCourse(course);
		project = projects.save(project);
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		integration.setVersion(0L);
		source = jiraIntegrations.save(integration);
		studentAccount = users.save(account(AccountRole.STUDENT));
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(studentAccount);
		profile.setStudentCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		student = students.save(profile);

		overdue = tasks.save(task("1", TaskStatus.IN_PROGRESS, START_OF_TODAY.minusDays(2), null, student));
		tasks.save(task("2", TaskStatus.IN_PROGRESS, START_OF_TODAY, null, student)); // due today: not over yet
		tasks.save(task("3", TaskStatus.IN_PROGRESS, START_OF_TODAY.minusDays(20), null, student)); // outside the window
		tasks.save(task("4", TaskStatus.IN_PROGRESS, START_OF_TODAY.minusDays(2), null, null)); // unassigned
		tasks.save(task("5", TaskStatus.DONE, START_OF_TODAY.minusDays(3), START_OF_TODAY.minusDays(1), student)); // done late
		tasks.save(task("6", TaskStatus.DONE, START_OF_TODAY.minusDays(3), START_OF_TODAY.minusDays(4), student)); // done early
	}

	@Test
	void overdueCandidatesAreAssignedOpenTasksWhoseDueDayIsOverWithinTheWindow() {
		assertThat(tasks.findDelayCaseOverdueCandidates(START_OF_TODAY, WINDOW_START, PageRequest.of(0, 50)).getContent())
				.extracting(Task::getExternalKey)
				.containsExactly("SAGA-1");
	}

	@Test
	void completedLateCandidatesAreDoneAfterTheirDueDate() {
		assertThat(tasks.findDelayCaseCompletedLateCandidates(WINDOW_START, PageRequest.of(0, 50)).getContent())
				.extracting(Task::getExternalKey)
				.containsExactly("SAGA-5");
	}

	@Test
	void openLoadCountsTheSamePersonsOtherUnfinishedTasksNearTheDueDate() {
		long count = tasks.countOpenTasksOfAssigneeDueBetween(
				project.getId(), student.getId(), overdue.getId(), overdue.getDueDate().minusDays(7), overdue.getDueDate().plusDays(7));

		assertThat(count).isEqualTo(1); // SAGA-2; SAGA-3 is too early, SAGA-4 unassigned, 5/6 done
	}

	@Test
	void evidenceSummariesIgnoreMergeCommits() {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setFullName("org/repo");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		repo = repos.save(repo);
		links.save(link(overdue, commits.save(commit(repo, "sha-a", START_OF_TODAY.minusDays(5), 1))));
		links.save(link(overdue, commits.save(commit(repo, "sha-b", START_OF_TODAY.minusDays(3), null))));
		links.save(link(overdue, commits.save(commit(repo, "sha-merge", START_OF_TODAY.minusDays(1), 2))));
		TaskWorkSession session = new TaskWorkSession();
		session.setTask(overdue);
		session.setUser(studentAccount);
		session.setProject(project);
		session.setStartedAt(START_OF_TODAY.minusDays(4));
		session.setEndedAt(START_OF_TODAY.minusDays(4).plusHours(2));
		session.setStatus(WorkSessionStatus.STOPPED);
		workSessions.save(session);

		Object[] commitRow = links.summarizeNonMergeCommitsByTask(overdue.getId()).getFirst();
		Object[] sessionRow = workSessions.summarizeByTask(overdue.getId()).getFirst();

		assertThat(((Number) commitRow[0]).longValue()).isEqualTo(2);
		assertThat(commitRow[1]).isEqualTo(START_OF_TODAY.minusDays(5));
		assertThat(commitRow[2]).isEqualTo(START_OF_TODAY.minusDays(3));
		assertThat(((Number) sessionRow[0]).longValue()).isEqualTo(1);
		assertThat(sessionRow[2]).isEqualTo(START_OF_TODAY.minusDays(4).plusHours(2));
		assertThat(((Number) links.summarizeNonMergeCommitsByTask(UUID.randomUUID()).getFirst()[0]).longValue()).isZero();
	}

	@Test
	void caseQueriesForTheProjectTheLecturerQueueExpiryAndTheOnTimeRate() {
		TaskDelayCase waiting = cases.save(delayCase(overdue, overdue.getDueDate(), DelayCaseStatus.AWAITING_LECTURER, START_OF_TODAY.plusDays(1)));
		TaskDelayCase expiring = cases.save(delayCase(
				tasks.save(task("7", TaskStatus.TODO, START_OF_TODAY.minusDays(1), null, student)),
				START_OF_TODAY.minusDays(1), DelayCaseStatus.OPEN, START_OF_TODAY.minusHours(1)));
		TaskDelayCase excused = cases.save(delayCase(
				tasks.save(task("8", TaskStatus.DONE, START_OF_TODAY.minusDays(4), START_OF_TODAY.minusDays(2), student)),
				START_OF_TODAY.minusDays(4), DelayCaseStatus.CLOSED_OBJECTIVE, START_OF_TODAY.minusDays(1)));

		assertThat(cases.findFetchedByProject(project.getId())).extracting(TaskDelayCase::getId)
				.containsExactlyInAnyOrder(waiting.getId(), expiring.getId(), excused.getId());
		assertThat(cases.findFetchedByIdAndProject(waiting.getId(), project.getId())).isPresent();
		assertThat(cases.findFetchedByIdAndProject(waiting.getId(), UUID.randomUUID())).isEmpty();
		assertThat(cases.findFetchedForLecturer(lecturerAccount.getId(), List.of(DelayCaseStatus.AWAITING_LECTURER)))
				.extracting(TaskDelayCase::getId).containsExactly(waiting.getId());
		assertThat(cases.findFetchedForLecturer(UUID.randomUUID(), List.of(DelayCaseStatus.AWAITING_LECTURER))).isEmpty();
		assertThat(cases.findByStatusAndExplanationDueAtBefore(DelayCaseStatus.OPEN, START_OF_TODAY))
				.extracting(TaskDelayCase::getId).containsExactly(expiring.getId());
		List<Object[]> dues = cases.findObjectiveClosedTaskDues(project.getId());
		assertThat(dues).hasSize(1);
		assertThat(dues.getFirst()[1]).isEqualTo(START_OF_TODAY.minusDays(4));
		assertThat(cases.existsByTask_IdAndDueDate(overdue.getId(), overdue.getDueDate())).isTrue();
		assertThat(cases.existsByProject_Id(project.getId())).isTrue();
	}

	@Test
	void aTaskHasAtMostOneCasePerDueDate() {
		cases.saveAndFlush(delayCase(overdue, overdue.getDueDate(), DelayCaseStatus.OPEN, START_OF_TODAY.plusDays(3)));

		assertThatThrownBy(() -> cases.saveAndFlush(
						delayCase(overdue, overdue.getDueDate(), DelayCaseStatus.OPEN, START_OF_TODAY.plusDays(3))))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void changeLogRoundTripsInOrder() {
		changeLogs.save(change(overdue, TaskChangeField.STORY_POINT, "3", "5", START_OF_TODAY.minusDays(2)));
		changeLogs.save(change(overdue, TaskChangeField.DUE_DATE, "2026-10-01", "2026-10-04", START_OF_TODAY.minusDays(5)));

		assertThat(changeLogs.findByTask_IdOrderByChangedAtAsc(overdue.getId()))
				.extracting(TaskChangeLog::getField)
				.containsExactly(TaskChangeField.DUE_DATE, TaskChangeField.STORY_POINT);
	}

	private TaskDelayCase delayCase(Task task, LocalDateTime due, DelayCaseStatus status, LocalDateTime explanationDueAt) {
		TaskDelayCase delay = new TaskDelayCase();
		delay.setProject(project);
		delay.setTask(task);
		delay.setStudentProfile(student);
		delay.setDueDate(due);
		delay.setOpenedAt(START_OF_TODAY.minusDays(1));
		delay.setExplanationDueAt(explanationDueAt);
		delay.setStatus(status);
		return delay;
	}

	private Task task(String number, TaskStatus status, LocalDateTime due, LocalDateTime completedAt, StudentProfile assignee) {
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(source);
		task.setExternalId("ext-" + number + "-" + UUID.randomUUID());
		task.setExternalKey("SAGA-" + number);
		task.setTitle("Task " + number);
		task.setStatus(status);
		task.setDueDate(due);
		task.setCompletedAt(completedAt);
		task.setAssigneeStudent(assignee);
		return task;
	}

	private static TaskChangeLog change(Task task, TaskChangeField field, String oldValue, String newValue, LocalDateTime at) {
		TaskChangeLog log = new TaskChangeLog();
		log.setTask(task);
		log.setField(field);
		log.setOldValue(oldValue);
		log.setNewValue(newValue);
		log.setChangedAt(at);
		return log;
	}

	private static GitCommit commit(GitRepo repo, String sha, LocalDateTime at, Integer parents) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setCommittedAt(at);
		commit.setParentCount(parents);
		return commit;
	}

	private static TaskGitCommitLink link(Task task, GitCommit commit) {
		TaskGitCommitLink link = new TaskGitCommitLink();
		link.setTask(task);
		link.setGitCommit(commit);
		link.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		return link;
	}

	private static UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName(role.name());
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

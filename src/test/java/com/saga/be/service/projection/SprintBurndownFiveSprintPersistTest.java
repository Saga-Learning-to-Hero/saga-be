package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.saga.be.dto.project.BurndownChartResponse;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.PeerReviewRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskAttachmentRepository;
import com.saga.be.repository.TaskFileRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWebLinkRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Real H2, real JPQL: the sprint burndown selected in the UI is scoped to exactly one canonical
 * local {@code Sprint.id} of the requested team's project. Five sprints across two Jira sources
 * carry deliberately different task data, and one external sprint id is reused by both sources,
 * so any fallback to project-wide totals or to external-id matching would show up as equal or
 * swapped numbers.
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
class SprintBurndownFiveSprintPersistTest {

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

	/** Deliberately unequal per-sprint shapes: {total, done}. */
	private static final Map<String, int[]> SHAPE = Map.of(
			"S1", new int[] {2, 1},
			"S2", new int[] {5, 3},
			"S3", new int[] {1, 0},
			"S4", new int[] {7, 2},
			"S5", new int[] {3, 3});
	private static final int BACKLOG_TASKS = 4;

	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private EntityManager entityManager;
	@Autowired private SemesterRepository semesters;
	@Autowired private AcademicClassRepository classes;
	@Autowired private SubjectRepository subjects;
	@Autowired private CourseRepository courses;
	@Autowired private ProjectRepository projects;
	@Autowired private TeamRepository teams;
	@Autowired private TeamMemberRepository members;
	@Autowired private JiraIntegrationRepository jiraIntegrations;
	@Autowired private SprintRepository sprints;
	@Autowired private TaskRepository tasks;
	@Autowired private GitCommitRepository commits;
	@Autowired private PeerReviewRepository peerReviews;
	@Autowired private TaskFileRepository files;
	@Autowired private TaskWebLinkRepository webLinks;
	@Autowired private TaskAttachmentRepository attachments;

	private TransactionTemplate tx;
	private TeamActivityAnalyticsService service;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		// Reader authorization is covered by TeamActivityAnalyticsServiceTest; here it always passes
		// so the test isolates sprint scoping.
		service = new TeamActivityAnalyticsService(teams, members, sprints, commits, peerReviews, files, webLinks, attachments, tasks,
				mock(ProjectDataAuthorization.class));
		fixture = seed();
	}

	@Test
	void eachOfFiveSprintsReturnsOnlyItsOwnTasks() {
		for (String name : List.of("S1", "S2", "S3", "S4", "S5")) {
			BurndownChartResponse burndown = burndown(fixture.sprintIds.get(name));
			int[] expected = SHAPE.get(name);
			assertThat(burndown.sprintId()).as(name).isEqualTo(fixture.sprintIds.get(name));
			assertThat(burndown.sprintName()).as(name).isEqualTo(name);
			assertThat(burndown.totalScope()).as(name + " total").isEqualTo(expected[0]);
			assertThat(burndown.points().getLast().doneCount()).as(name + " done").isEqualTo(expected[1]);
			assertThat(burndown.points().getLast().actualRemaining()).as(name + " remaining").isEqualTo(expected[0] - expected[1]);
			// The even-pace guideline is scoped to this sprint too: its own scope down to 0.
			assertThat(burndown.points().getFirst().idealRemaining()).as(name + " ideal start").isEqualTo(expected[0]);
			assertThat(burndown.points().getLast().idealRemaining()).as(name + " ideal end").isZero();
		}
	}

	@Test
	void differentSprintsNeverShareOneResult() {
		BurndownChartResponse s1 = burndown(fixture.sprintIds.get("S1"));
		BurndownChartResponse s2 = burndown(fixture.sprintIds.get("S2"));
		BurndownChartResponse s3 = burndown(fixture.sprintIds.get("S3"));
		assertThat(List.of(s1.totalScope(), s2.totalScope(), s3.totalScope())).doesNotHaveDuplicates();
		assertThat(s1.startDate()).isNotEqualTo(s2.startDate());
		assertThat(s1.points()).isNotEqualTo(s2.points());
	}

	@Test
	void sameExternalSprintIdOnTwoJiraSourcesStaysIsolatedByLocalId() {
		Sprint s1 = tx.execute(status -> sprints.findById(fixture.sprintIds.get("S1")).orElseThrow());
		Sprint s4 = tx.execute(status -> sprints.findById(fixture.sprintIds.get("S4")).orElseThrow());
		assertThat(s1.getExternalSprintId()).isEqualTo(s4.getExternalSprintId());

		BurndownChartResponse viaS1 = burndown(s1.getId());
		BurndownChartResponse viaS4 = burndown(s4.getId());
		assertThat(viaS1.totalScope()).isEqualTo(2);
		assertThat(viaS4.totalScope()).isEqualTo(7);
	}

	@Test
	void allSprintAggregateEqualsTheSumOfTheSprintScopedResultsAndExcludesBacklog() {
		int sumOfSprints = 0;
		for (String name : SHAPE.keySet()) sumOfSprints += burndown(fixture.sprintIds.get(name)).totalScope();
		long aggregate = tx.execute(status -> tasks.countGroupedBySprintAndStatus(fixture.projectId).stream().mapToLong(row -> (Long) row[2]).sum());
		long projectWideIncludingBacklog = tx.execute(status -> tasks.findAll().stream()
				.filter(t -> t.getProject().getId().equals(fixture.projectId) && t.getDeletedAt() == null).count());

		assertThat(sumOfSprints).isEqualTo(2 + 5 + 1 + 7 + 3);
		assertThat(aggregate).isEqualTo(sumOfSprints);
		// A sprint never shows the project-wide total: backlog and the other sprints are excluded.
		assertThat(projectWideIncludingBacklog).isEqualTo(sumOfSprints + BACKLOG_TASKS);
		for (String name : SHAPE.keySet())
			assertThat((long) burndown(fixture.sprintIds.get(name)).totalScope()).isLessThan(projectWideIncludingBacklog);
	}

	@Test
	void softDeletedTaskIsNotCountedInItsSprint() {
		UUID s2 = fixture.sprintIds.get("S2");
		tx.executeWithoutResult(status -> {
			Task deleted = task(fixture.projectId, sprints.findById(s2).orElseThrow(), TaskStatus.DONE, null);
			deleted.setDeletedAt(LocalDateTime.of(2026, 9, 10, 0, 0));
			tasks.save(deleted);
		});
		assertThat(burndown(s2).totalScope()).isEqualTo(5);
	}

	@Test
	void sprintOfAnotherProjectOrAnotherTeamIsRejectedWithoutLeakingData() {
		UUID foreignSprint = tx.execute(status -> {
			Course course = courses.findById(fixture.courseId).orElseThrow();
			Project other = new Project();
			other.setCourse(course);
			other.setName("OTHER");
			other = projects.save(other);
			JiraIntegration jira = jiraIntegrations.save(jira(other, "cloud-x", "90000", "OTH"));
			Sprint sprint = sprints.save(sprint(jira, "S-foreign", "101", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7)));
			tasks.save(task(other.getId(), sprint, TaskStatus.TODO, null));
			return sprint.getId();
		});

		assertThatThrownBy(() -> burndown(foreignSprint))
				.isInstanceOfSatisfying(AcademicException.class, ex -> assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND));
		assertThatThrownBy(() -> tx.execute(status -> service.burndown(UUID.randomUUID(), UUID.randomUUID(), fixture.teamId, fixture.sprintIds.get("S1"))))
				.isInstanceOfSatisfying(AcademicException.class, ex -> assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.TEAM_NOT_FOUND));
		assertThatThrownBy(() -> burndown(UUID.randomUUID()))
				.isInstanceOfSatisfying(AcademicException.class, ex -> assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND));
	}

	@Test
	void queryCountIsBoundedAndIndependentOfSprintSize() {
		Statistics stats = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		stats.clear();
		burndown(fixture.sprintIds.get("S3")); // 1 task
		long small = stats.getPrepareStatementCount();
		stats.clear();
		burndown(fixture.sprintIds.get("S4")); // 7 tasks
		long large = stats.getPrepareStatementCount();

		// Constant shape (team+project, sprint, burndown rows) -- never one query per task.
		assertThat(large).isEqualTo(small);
		assertThat(large).isLessThanOrEqualTo(4L);
	}

	private BurndownChartResponse burndown(UUID sprintId) {
		return tx.execute(status -> service.burndown(UUID.randomUUID(), fixture.courseId, fixture.teamId, sprintId));
	}

	private Fixture seed() {
		return tx.execute(status -> {
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
			course.setName("Main");
			course.setCourseCode(subject.getSubjectCode());
			course.setSubject(subject);
			course.setAcademicClass(academicClass);
			course.setSemester(semester);
			course.setSyllabusVersion(syllabus);
			course = courses.save(course);

			Project project = new Project();
			project.setCourse(course);
			project.setName("SAGA");
			project = projects.save(project);
			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(1);
			team.setName("Alpha");
			team = teams.save(team);

			JiraIntegration sourceA = jiraIntegrations.save(jira(project, "cloud-a", "10000", "SAGA"));
			JiraIntegration sourceB = jiraIntegrations.save(jira(project, "cloud-b", "20000", "LMS"));
			Map<String, Sprint> byName = new HashMap<>();
			// S1 and S4 deliberately share external sprint id "101" on different Jira sources.
			byName.put("S1", sprints.save(sprint(sourceA, "S1", "101", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7))));
			byName.put("S2", sprints.save(sprint(sourceA, "S2", "102", LocalDate.of(2026, 9, 8), LocalDate.of(2026, 9, 14))));
			byName.put("S3", sprints.save(sprint(sourceA, "S3", "103", LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 21))));
			byName.put("S4", sprints.save(sprint(sourceB, "S4", "101", LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 14))));
			byName.put("S5", sprints.save(sprint(sourceB, "S5", "105", LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 28))));

			Map<String, UUID> ids = new HashMap<>();
			for (Map.Entry<String, Sprint> entry : byName.entrySet()) {
				Sprint sprint = entry.getValue();
				int[] shape = SHAPE.get(entry.getKey());
				for (int i = 0; i < shape[0]; i++) {
					boolean done = i < shape[1];
					LocalDateTime completedAt = done ? sprint.getStartDate().plusDays(1 + i % 3) : null;
					tasks.save(task(project.getId(), sprint, done ? TaskStatus.DONE : TaskStatus.IN_PROGRESS, completedAt));
				}
				ids.put(entry.getKey(), sprint.getId());
			}
			for (int i = 0; i < BACKLOG_TASKS; i++) {
				Task backlog = task(project.getId(), null, TaskStatus.TODO, null);
				backlog.setJiraIntegration(i % 2 == 0 ? sourceA : sourceB);
				tasks.save(backlog);
			}
			entityManager.flush();
			return new Fixture(course.getId(), team.getId(), project.getId(), Map.copyOf(ids));
		});
	}

	private static JiraIntegration jira(Project project, String cloudId, String jiraProjectId, String projectKey) {
		JiraIntegration jira = new JiraIntegration();
		jira.setProject(project);
		jira.setName("board-" + cloudId);
		jira.setCloudId(cloudId);
		jira.setJiraProjectId(jiraProjectId);
		jira.setProjectKey(projectKey);
		jira.setConnectionStatus(IntegrationStatus.ACTIVE);
		jira.setConsecutiveFailures(0);
		jira.setVersion(0L);
		return jira;
	}

	private static Sprint sprint(JiraIntegration jira, String name, String externalSprintId, LocalDate start, LocalDate end) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(jira);
		sprint.setExternalSprintId(externalSprintId);
		sprint.setName(name);
		sprint.setState("active");
		sprint.setStartDate(start.atStartOfDay());
		sprint.setEndDate(end.atTime(23, 0));
		return sprint;
	}

	private Task task(UUID projectId, Sprint sprint, TaskStatus status, LocalDateTime completedAt) {
		Task task = new Task();
		task.setProject(entityManager.getReference(Project.class, projectId));
		if (sprint != null) task.setJiraIntegration(sprint.getJiraIntegration());
		task.setSprint(sprint);
		task.setStatus(status);
		task.setCompletedAt(completedAt);
		task.setTitle(status.name());
		task.setExternalId(UUID.randomUUID().toString());
		return task;
	}

	private record Fixture(UUID courseId, UUID teamId, UUID projectId, Map<String, UUID> sprintIds) {}
}

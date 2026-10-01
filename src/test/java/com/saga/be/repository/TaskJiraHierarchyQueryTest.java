package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real H2, real JPQL: the Jira-parent queries -- parent candidates by level and source, direct
 * Jira children, and the "still has children" delete guard -- plus the V37 columns round-trip.
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
class TaskJiraHierarchyQueryTest {

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

	private static final UUID NOBODY = UUID.fromString("00000000-0000-0000-0000-000000000000");

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
	private JiraIntegrationRepository jiraIntegrations;

	private Project project;
	private JiraIntegration siteA;
	private JiraIntegration siteB;
	private Task epic;
	private Task story;
	private Task subtask;

	@BeforeEach
	void setUp() {
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
		course = courses.save(course);
		project = new Project();
		project.setName("Project");
		project.setCourse(course);
		project = projects.save(project);
		siteA = jiraIntegrations.save(site("cloud-a"));
		siteB = jiraIntegrations.save(site("cloud-b"));

		epic = tasks.save(task(siteA, "100", null, "Authentication", "EPIC", 1, null));
		story = tasks.save(task(siteA, "101", "100", "Login story", "STANDARD", 0, null));
		subtask = tasks.save(task(siteA, "102", "101", "Login API", "SUBTASK", -1, null));
		tasks.save(task(siteA, "103", "100", "Deleted story", "STANDARD", 0, LocalDateTime.now()));
		tasks.save(task(siteA, "104", null, "Legacy row", null, null, null));
		tasks.save(task(siteB, "100", null, "Epic on another site", "EPIC", 1, null));
	}

	@Test
	void hierarchyColumnsRoundTrip() {
		Task reloaded = tasks.findById(subtask.getId()).orElseThrow();

		assertThat(reloaded.getIssueTypeId()).isEqualTo("type-SUBTASK");
		assertThat(reloaded.getIssueTypeLevel()).isEqualTo("SUBTASK");
		assertThat(reloaded.getJiraHierarchyLevel()).isEqualTo(-1);
	}

	@Test
	void epicCandidatesComeOnlyFromTheSameSource() {
		List<Object[]> rows = options(siteA, "EPIC", NOBODY, null);

		assertThat(rows).extracting(row -> row[0]).containsExactly(epic.getId());
		assertThat(rows.getFirst()[5]).isEqualTo("EPIC");
	}

	@Test
	void standardCandidatesSkipDeletedUnknownAndTheItemItself() {
		assertThat(options(siteA, "STANDARD", NOBODY, null)).extracting(row -> row[0]).containsExactly(story.getId());
		assertThat(options(siteA, "STANDARD", story.getId(), null)).isEmpty();
	}

	@Test
	void candidatesFilterByTitleOrKeyPrefix() {
		assertThat(options(siteA, "STANDARD", NOBODY, "log")).hasSize(1);
		assertThat(options(siteA, "STANDARD", NOBODY, "zzz")).isEmpty();
	}

	@Test
	void directJiraChildrenAreActiveRowsOfTheSameSource() {
		assertThat(tasks.findActiveJiraChildSummaries(siteA.getId(), "100"))
				.extracting(row -> row[0])
				.containsExactly(story.getId());
		assertThat(tasks.findActiveJiraChildSummaries(siteA.getId(), "101"))
				.extracting(row -> row[3])
				.containsExactly("SAGA-102");
		assertThat(tasks.findActiveJiraChildSummaries(siteB.getId(), "100")).isEmpty();
	}

	@Test
	void deleteGuardSeesOnlyActiveChildren() {
		assertThat(tasks.existsByJiraIntegration_IdAndParentExternalIdAndDeletedAtIsNull(siteA.getId(), "101")).isTrue();
		assertThat(tasks.existsByJiraIntegration_IdAndParentExternalIdAndDeletedAtIsNull(siteA.getId(), "102")).isFalse();
		assertThat(tasks.existsByJiraIntegration_IdAndParentExternalIdAndDeletedAtIsNull(siteB.getId(), "101")).isFalse();
	}

	private List<Object[]> options(JiraIntegration source, String level, UUID exclude, String q) {
		boolean blank = q == null || q.isBlank();
		return tasks.findJiraParentOptions(
						project.getId(), source.getId(), level, exclude, blank, blank ? "" : q.toLowerCase(), PageRequest.of(0, 20))
				.getContent();
	}

	private JiraIntegration site(String cloudId) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId(cloudId + "-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		integration.setVersion(0L);
		return integration;
	}

	private Task task(
			JiraIntegration source,
			String externalId,
			String parentExternalId,
			String title,
			String level,
			Integer hierarchyLevel,
			LocalDateTime deletedAt) {
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(source);
		task.setExternalId(externalId);
		task.setExternalKey("SAGA-" + externalId);
		task.setParentExternalId(parentExternalId);
		task.setTitle(title);
		task.setStatus(TaskStatus.TODO);
		task.setIssueTypeId(level == null ? null : "type-" + level);
		task.setIssueTypeLevel(level);
		task.setJiraHierarchyLevel(hierarchyLevel);
		task.setDeletedAt(deletedAt);
		return task;
	}
}

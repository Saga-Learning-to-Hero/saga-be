package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import java.util.Optional;
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
 * Real H2, real JPQL. Before V14, {@code findByCloudIdAndJiraProjectId} was safe to return a
 * singular {@code Optional} only because the DB guaranteed at most one {@code jira_integration}
 * row could ever exist for a given (cloudId, jiraProjectId) pair. V14 intentionally allows
 * multiple REVOKED rows to share a source, so any status-agnostic singular lookup over those
 * columns risks {@code IncorrectResultSizeDataAccessException} the moment two or more rows match.
 * This proves the ACTIVE-scoped replacement ({@code findByConnectionStatusAndCloudIdAndJiraProjectId})
 * stays safely singular regardless of how many REVOKED rows share the source, because
 * {@code uk_jira_active_cloud_project} (V14) still guarantees at most one ACTIVE row.
 *
 * <p>H2's create-drop schema here has no unique index at all on (cloud_id, jira_project_id) --
 * {@link JiraIntegration} intentionally does not map V14's generated columns -- so this test can
 * freely seed multiple REVOKED rows on the same source to exercise the query engine's cardinality
 * handling. The actual DB-level uniqueness enforcement is real-MySQL-only and verified separately.
 *
 * <p>Deliberately placed in {@code com.saga.be.repository}, matching {@code
 * TaskProgressSoftDeleteQueryTest}, to avoid the nested {@code @SpringBootConfiguration TxSlice}
 * being auto-detected by a sibling plain {@code @SpringBootTest}.
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
class JiraIntegrationActiveScopedLookupTest {

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
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository academicClasses;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;

	private static final String CLOUD_ID = "cloud-shared";
	private static final String JIRA_PROJECT_ID = "10099";

	private Project projectA;
	private Project projectB;
	private Project projectC;

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));

		projectA = projects.save(project(course, "Project A"));
		projectB = projects.save(project(course, "Project B"));
		projectC = projects.save(project(course, "Project C"));
	}

	@Test
	void multipleRevokedRowsCoexistOnTheSameSourceWithoutAnyConstraintViolation() {
		assertThatCode(() -> {
					jiraIntegrations.save(revokedIntegration(projectA));
					jiraIntegrations.save(revokedIntegration(projectB));
				})
				.doesNotThrowAnyException();
	}

	@Test
	void activeScopedLookupFindsTheSoleActiveOwnerAmongMultipleRevokedRows() {
		jiraIntegrations.save(revokedIntegration(projectA));
		jiraIntegrations.save(revokedIntegration(projectB));
		JiraIntegration activeC = jiraIntegrations.save(activeIntegration(projectC));

		assertThatCode(() -> jiraIntegrations.findByConnectionStatusAndCloudIdAndJiraProjectId(
						IntegrationStatus.ACTIVE, CLOUD_ID, JIRA_PROJECT_ID))
				.doesNotThrowAnyException();

		Optional<JiraIntegration> found = jiraIntegrations.findByConnectionStatusAndCloudIdAndJiraProjectId(
				IntegrationStatus.ACTIVE, CLOUD_ID, JIRA_PROJECT_ID);
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(activeC.getId());
		assertThat(found.get().getProject().getId()).isEqualTo(projectC.getId());
	}

	@Test
	void sourceBecomesAvailableAfterActiveOwnerDisconnectsAndCanBeClaimedByAnotherRow() {
		JiraIntegration activeC = jiraIntegrations.save(activeIntegration(projectC));
		assertThat(jiraIntegrations.findByConnectionStatusAndCloudIdAndJiraProjectId(
						IntegrationStatus.ACTIVE, CLOUD_ID, JIRA_PROJECT_ID))
				.isPresent();

		activeC.setConnectionStatus(IntegrationStatus.REVOKED);
		jiraIntegrations.save(activeC);

		assertThat(jiraIntegrations.findByConnectionStatusAndCloudIdAndJiraProjectId(
						IntegrationStatus.ACTIVE, CLOUD_ID, JIRA_PROJECT_ID))
				.isEmpty();

		JiraIntegration activeA = jiraIntegrations.save(activeIntegration(projectA));

		Optional<JiraIntegration> found = jiraIntegrations.findByConnectionStatusAndCloudIdAndJiraProjectId(
				IntegrationStatus.ACTIVE, CLOUD_ID, JIRA_PROJECT_ID);
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(activeA.getId());
	}

	private static JiraIntegration revokedIntegration(Project project) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId(CLOUD_ID);
		integration.setJiraProjectId(JIRA_PROJECT_ID);
		integration.setProjectKey("KEY-" + UUID.randomUUID().toString().substring(0, 6));
		integration.setConnectionStatus(IntegrationStatus.REVOKED);
		integration.setConsecutiveFailures(0);
		return integration;
	}

	private static JiraIntegration activeIntegration(Project project) {
		JiraIntegration integration = revokedIntegration(project);
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		return integration;
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

	private static Project project(Course course, String name) {
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		return project;
	}
}

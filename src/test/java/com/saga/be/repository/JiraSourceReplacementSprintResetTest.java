package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.project.Project;
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
 * Real H2, real JPQL. Sprint is reached only via {@code jira_integration_id}, never {@code
 * project_id} (see {@link com.saga.be.entity.jira.Sprint}) -- so when a SAGA project replaces its
 * Jira source and {@code ProjectIntegrationService.persistJiraIntegration} reuses the SAME {@code
 * jira_integration} row, the old source's Sprint rows would otherwise survive attached to that
 * same id, indistinguishable from the new source's future Sprints. This proves {@code
 * SprintRepository.deleteByJiraIntegration_Id}, wired into that source-replacement path via {@code
 * JiraTaskProjectionHardReset#hardDeleteAllSprintsForIntegration}, removes exactly and only the
 * Sprints under the given integration id, leaving an unrelated integration's Sprints untouched.
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
class JiraSourceReplacementSprintResetTest {

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
	@Autowired
	private SprintRepository sprints;

	private JiraIntegration integrationB; // the one being source-replaced
	private JiraIntegration integrationOther; // unrelated project, must stay untouched

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));

		Project projectB = projects.save(project(course, "Project B"));
		Project projectOther = projects.save(project(course, "Project Other"));

		integrationB = jiraIntegrations.save(integration(projectB, "cloud-x", "old-source-x"));
		integrationOther = jiraIntegrations.save(integration(projectOther, "cloud-z", "unrelated-z"));

		sprints.save(sprint(integrationB, "1", "Old Sprint 1"));
		sprints.save(sprint(integrationB, "2", "Old Sprint 2"));
		sprints.save(sprint(integrationOther, "1", "Other Project's Sprint 1"));
	}

	@Test
	void deleteByJiraIntegrationIdRemovesOnlyThatIntegrationsSprints() {
		assertThat(sprints.findByJiraIntegration_IdAndExternalSprintIdIn(
						integrationB.getId(), List.of("1", "2")))
				.hasSize(2);

		sprints.deleteByJiraIntegration_Id(integrationB.getId());

		assertThat(sprints.findByJiraIntegration_IdAndExternalSprintIdIn(
						integrationB.getId(), List.of("1", "2")))
				.isEmpty();
		// Unrelated project's Sprint history is completely unaffected.
		assertThat(sprints.findByJiraIntegration_IdAndExternalSprintId(integrationOther.getId(), "1"))
				.isPresent();
	}

	private static JiraIntegration integration(Project project, String cloudId, String jiraProjectId) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId(cloudId);
		integration.setJiraProjectId(jiraProjectId);
		integration.setProjectKey("KEY-" + UUID.randomUUID().toString().substring(0, 6));
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		return integration;
	}

	private static Sprint sprint(JiraIntegration integration, String externalSprintId, String name) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(integration);
		sprint.setExternalSprintId(externalSprintId);
		sprint.setName(name);
		sprint.setState("closed");
		return sprint;
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

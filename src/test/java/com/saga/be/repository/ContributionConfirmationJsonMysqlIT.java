package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.attribution.ContributionConfirmation;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.ConfirmationEvent;
import com.saga.be.entity.enums.ConfirmationMethod;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.service.confirmation.EvidenceHasher;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real MySQL 8 + Hibernate path: persist {@link ContributionConfirmation} through the repository
 * and {@code @JdbcTypeCode(SqlTypes.JSON)}, then assert {@code JSON_TYPE(...)=OBJECT}. Also proves
 * the pre-fix {@code TreeMap#toString()} snapshot is rejected as MySQL error 3141. Opt-in:
 * {@code saga.verify.mysql=true}. Nested TxSlice stays out of {@code com.saga.be}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.profiles.active=",
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=root",
			"spring.datasource.password=",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=none",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.flyway.enabled=true",
			"spring.flyway.user=root",
			"spring.flyway.password=",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContributionConfirmationJsonMysqlIT {

	private static final String SHA = "d362a3532ceaebe36c78f7e151e881daae8c6277";

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
	private UserAccountRepository users;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private ContributionConfirmationRepository confirmations;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate tx;
	private UserAccount user;
	private Task task;
	private Project project;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		tx.executeWithoutResult(status -> seed());
	}

	@Test
	void repositorySaveAndFlush_storesJsonObject_notDoubleEncodedString() {
		String snapshot = EvidenceHasher.canonical("SAGA-1", List.of(SHA), List.of(), "TODO");
		UUID id = tx.execute(status -> {
			ContributionConfirmation row = confirmation(snapshot);
			return confirmations.saveAndFlush(row).getId();
		});

		Object[] row = tx.execute(status -> (Object[]) entityManager
				.createNativeQuery(
						"""
						SELECT
						  JSON_TYPE(evidence_snapshot_json),
						  JSON_EXTRACT(evidence_snapshot_json, '$.commits'),
						  JSON_EXTRACT(evidence_snapshot_json, '$.taskKey')
						FROM contribution_confirmation
						WHERE id = :id
						""")
				.setParameter("id", id.toString())
				.getSingleResult());

		assertThat(row).isNotNull();
		assertThat(asText(row[0])).isEqualTo("OBJECT");
		assertThat(asText(row[1])).contains(SHA);
		assertThat(asText(row[2])).contains("SAGA-1");
	}

	@Test
	void repositorySaveAndFlush_legacyMapToString_isMysqlError3141() {
		String legacy = legacyMapToString();
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
					ContributionConfirmation row = confirmation(legacy);
					confirmations.saveAndFlush(row);
				}))
				.matches(ContributionConfirmationJsonMysqlIT::isMysqlError3141);
	}

	@Test
	void mysqlCastAsJson_rejectsJavaMapToString_error3141() {
		assertThatThrownBy(() -> tx.execute(status -> entityManager
						.createNativeQuery("select cast(:snapshot as json)")
						.setParameter("snapshot", legacyMapToString())
						.getSingleResult()))
				.matches(ContributionConfirmationJsonMysqlIT::isMysqlError3141);
	}

	private ContributionConfirmation confirmation(String snapshot) {
		ContributionConfirmation row = new ContributionConfirmation();
		row.setTask(tasks.findById(task.getId()).orElseThrow());
		row.setUser(users.findById(user.getId()).orElseThrow());
		row.setProject(projects.findById(project.getId()).orElseThrow());
		row.setEventState(ConfirmationEvent.CONFIRMED);
		row.setConfirmationMethod(ConfirmationMethod.PASSWORD_STEP_UP);
		row.setEvidenceSnapshotJson(snapshot);
		row.setEvidenceHash(EvidenceHasher.sha256(snapshot));
		return row;
	}

	private void seed() {
		Semester semester = new Semester();
		semester.setCode("FA" + UUID.randomUUID().toString().substring(0, 8));
		semester.setName("Fall");
		semester = semesters.save(semester);

		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("SE");
		academicClass = academicClasses.save(academicClass);

		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);

		Course course = new Course();
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course = courses.save(course);

		project = new Project();
		project.setName("P");
		project.setCourse(course);
		project = projects.save(project);

		JiraIntegration jira = jiraIntegrations.save(jiraFor(project));

		user = new UserAccount();
		user.setEmail("confirm-" + UUID.randomUUID() + "@fe.edu.vn");
		user.setFullName("Student");
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(AccountStatus.ACTIVE);
		user = users.save(user);

		task = new Task();
		task.setProject(project);
		task.setJiraIntegration(jira);
		task.setExternalKey("SAGA-1");
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle("Task");
		task.setStatus(TaskStatus.TODO);
		task = tasks.save(task);
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

	private static String legacyMapToString() {
		TreeMap<String, Object> body = new TreeMap<>();
		body.put("commits", List.of(SHA));
		body.put("jiraStatus", "TODO");
		body.put("pullRequests", List.of());
		body.put("taskKey", "SAGA-1");
		return body.toString();
	}

	private static boolean isMysqlError3141(Throwable ex) {
		Throwable cause = ex;
		while (cause != null) {
			if (cause instanceof SQLException sql && sql.getErrorCode() == 3141) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}

	private static String asText(Object value) {
		if (value == null) {
			return "null";
		}
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}
}

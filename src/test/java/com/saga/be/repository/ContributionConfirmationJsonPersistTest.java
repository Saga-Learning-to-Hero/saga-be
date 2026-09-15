package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.service.confirmation.EvidenceHasher;
import jakarta.persistence.EntityManager;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Always-on H2 regression: {@link ContributionConfirmationRepository#saveAndFlush} of
 * {@link EvidenceHasher#canonical} through {@code @JdbcTypeCode(SqlTypes.JSON)} must store a JSON
 * object payload, not a double-encoded JSON string. MySQL {@code JSON_TYPE} is proven separately.
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ContributionConfirmationJsonPersistTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();
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
	private UserAccountRepository users;
	@Autowired
	private TaskRepository tasks;
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
	void repositorySaveAndFlush_storesJsonObjectNotDoubleEncodedString() throws Exception {
		String snapshot = EvidenceHasher.canonical("SAGA-1", List.of(SHA), List.of(), "TODO");
		UUID id = tx.execute(status -> {
			ContributionConfirmation row = confirmation(snapshot);
			return confirmations.saveAndFlush(row).getId();
		});

		String stored = tx.execute(status -> {
			entityManager.clear();
			Object raw = entityManager
					.createNativeQuery("select evidence_snapshot_json from contribution_confirmation where id = :id")
					.setParameter("id", id.toString())
					.getSingleResult();
			return raw == null ? null : raw.toString();
		});

		assertThat(stored).isNotBlank();
		assertThat(stored.trim()).startsWith("{").doesNotStartWith("\"");
		assertThat(stored).doesNotStartWith("{commits=");
		JsonNode node = MAPPER.readTree(stored);
		assertThat(node.isObject()).isTrue();
		assertThat(node.isTextual()).isFalse();
		assertThat(node.get("commits").get(0).asText()).isEqualTo(SHA);
		assertThat(node.get("taskKey").asText()).isEqualTo("SAGA-1");

		ContributionConfirmation reloaded = tx.execute(status -> confirmations.findById(id).orElseThrow());
		assertThat(reloaded.getEvidenceSnapshotJson()).isEqualTo(snapshot);
		JsonNode fromEntity = MAPPER.readTree(reloaded.getEvidenceSnapshotJson());
		assertThat(fromEntity.isObject()).isTrue();
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

		user = new UserAccount();
		user.setEmail("confirm-" + UUID.randomUUID() + "@fe.edu.vn");
		user.setFullName("Student");
		user.setAccountRole(AccountRole.STUDENT);
		user.setAccountStatus(AccountStatus.ACTIVE);
		user = users.save(user);

		task = new Task();
		task.setProject(project);
		task.setExternalKey("SAGA-1");
		task.setExternalId(UUID.randomUUID().toString());
		task.setTitle("Task");
		task.setStatus(TaskStatus.TODO);
		task = tasks.save(task);
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
}

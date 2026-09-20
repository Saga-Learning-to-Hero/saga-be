package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.projection.TaskHierarchyService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
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

/**
 * Disposable MySQL 8: V1→V21 seed a Task, V22 adds nullable parent_task_id, then concurrent
 * hierarchy mutations serialize on Project FOR UPDATE. Opt-in: {@code saga.verify.mysql=true}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.profiles.active=",
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
			"spring.datasource.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=none",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskHierarchyV22MysqlIT {

	private static final String SEMESTER_ID = "aaaaaaaa-0000-4000-8000-000000000022";
	private static final String CLASS_ID = "bbbbbbbb-0000-4000-8000-000000000022";
	private static final String SUBJECT_ID = "cccccccc-0000-4000-8000-000000000022";
	private static final String COURSE_ID = "dddddddd-0000-4000-8000-000000000022";
	private static final String PROJECT_ID = "eeeeeeee-0000-4000-8000-000000000022";
	private static final String LEGACY_JIRA_INTEGRATION_ID = "11111111-0000-4000-8000-000000000022";
	private static final String LEGACY_TASK_ID = "ffffffff-0000-4000-8000-000000000022";

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
	private javax.sql.DataSource dataSource;
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
	private TaskRepository tasks;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private Map<Integer, Integer> checksumsThroughV21;

	@BeforeEach
	void migrateV1ThroughV21SeedTaskThenV22() throws Exception {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("21")
				.load()
				.migrate();
		checksumsThroughV21 = checksumsByVersion();
		seedLegacyTask();
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void v22KeepsLegacyTaskNullParentAndAddsRestrictiveFk() throws Exception {
		Map<Integer, Integer> after = checksumsByVersion();
		for (int version = 1; version <= 21; version++) {
			assertEquals(checksumsThroughV21.get(version), after.get(version), "V" + version + " checksum changed");
		}
		assertTrue(after.containsKey(22));
		assertEquals(22, after.size());

		try (Connection connection = dataSource.getConnection()) {
			assertEquals("YES", nullable(connection, "parent_task_id"));
			assertEquals("char", dataType(connection, "parent_task_id"));
			assertEquals(36, maxLength(connection, "parent_task_id"));
			try (PreparedStatement row = connection.prepareStatement(
					"SELECT parent_task_id, title FROM task WHERE id = ?")) {
				row.setString(1, LEGACY_TASK_ID);
				try (ResultSet rs = row.executeQuery()) {
					assertTrue(rs.next());
					assertEquals("Legacy task", rs.getString("title"));
					assertEquals(null, rs.getString("parent_task_id"));
				}
			}
			String createTable;
			try (PreparedStatement show = connection.prepareStatement("SHOW CREATE TABLE task");
					ResultSet rs = show.executeQuery()) {
				assertTrue(rs.next());
				createTable = rs.getString(2);
			}
			assertThat(createTable).contains("ix_task_parent_task_id");
			assertThat(createTable).contains("fk_task_parent_task");
			assertThat(createTable.toUpperCase()).doesNotContain("ON DELETE CASCADE");
			assertThat(createTable.toUpperCase()).doesNotContain("ON DELETE SET NULL");
		}
	}

	@Test
	void concurrentOppositeParentAssign_onlyOneCommits() throws Exception {
		Project project = persistProject("Cycle");
		Task taskA = persistTask(project, "A");
		Task taskB = persistTask(project, "B");
		TaskHierarchyService replicaA = new TaskHierarchyService(projects, tasks, transactionManager);
		TaskHierarchyService replicaB = new TaskHierarchyService(projects, tasks, transactionManager);
		CyclicBarrier start = new CyclicBarrier(2);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> runAssign(replicaA, start, project.getId(), taskA.getId(), taskB.getId(), successes, rejected));
			Future<?> second = pool.submit(() -> runAssign(replicaB, start, project.getId(), taskB.getId(), taskA.getId(), successes, rejected));
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertThat(successes.get()).isEqualTo(1);
		assertThat(rejected.get()).isEqualTo(1);
		Task reloadedA = tasks.findById(taskA.getId()).orElseThrow();
		Task reloadedB = tasks.findById(taskB.getId()).orElseThrow();
		boolean aToB = reloadedA.getParentTask() != null && taskB.getId().equals(reloadedA.getParentTask().getId());
		boolean bToA = reloadedB.getParentTask() != null && taskA.getId().equals(reloadedB.getParentTask().getId());
		assertThat(aToB ^ bToA).isTrue();
	}

	@Test
	void concurrentDistinctParentChanges_areDeadlockFree() throws Exception {
		Project project = persistProject("Deadlock");
		Task parent = persistTask(project, "P");
		Task childA = persistTask(project, "C1");
		Task childB = persistTask(project, "C2");
		TaskHierarchyService replicaA = new TaskHierarchyService(projects, tasks, transactionManager);
		TaskHierarchyService replicaB = new TaskHierarchyService(projects, tasks, transactionManager);
		CyclicBarrier start = new CyclicBarrier(2);
		AtomicInteger successes = new AtomicInteger();
		AtomicInteger rejected = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<?> first = pool.submit(() -> runAssign(replicaA, start, project.getId(), childA.getId(), parent.getId(), successes, rejected));
			Future<?> second = pool.submit(() -> runAssign(replicaB, start, project.getId(), childB.getId(), parent.getId(), successes, rejected));
			first.get(20, TimeUnit.SECONDS);
			second.get(20, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}
		assertThat(successes.get()).isEqualTo(2);
		assertThat(rejected.get()).isEqualTo(0);
		assertThat(tasks.findById(childA.getId()).orElseThrow().getParentTask().getId()).isEqualTo(parent.getId());
		assertThat(tasks.findById(childB.getId()).orElseThrow().getParentTask().getId()).isEqualTo(parent.getId());
	}

	private static void runAssign(
			TaskHierarchyService service,
			CyclicBarrier start,
			UUID projectId,
			UUID childId,
			UUID parentId,
			AtomicInteger successes,
			AtomicInteger rejected) {
		try {
			start.await(15, TimeUnit.SECONDS);
			service.mutateParent(projectId, childId, parentId);
			successes.incrementAndGet();
		} catch (AcademicException ex) {
			if (ex.getCode() != AcademicErrorCode.TASK_PARENT_INVALID) {
				throw ex;
			}
			rejected.incrementAndGet();
		} catch (RuntimeException ex) {
			throw ex;
		} catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	private void seedLegacyTask() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			try (PreparedStatement semester = connection.prepareStatement(
					"INSERT INTO semester (id, code, name, created_at, updated_at) VALUES (?, 'V22SEM', 'V22 Semester', NOW(6), NOW(6))")) {
				semester.setString(1, SEMESTER_ID);
				semester.executeUpdate();
			}
			try (PreparedStatement academicClass = connection.prepareStatement(
					"""
					INSERT INTO academic_class (id, semester_id, class_code, name, created_at, updated_at)
					VALUES (?, ?, 'SE22', 'V22 Class', NOW(6), NOW(6))
					""")) {
				academicClass.setString(1, CLASS_ID);
				academicClass.setString(2, SEMESTER_ID);
				academicClass.executeUpdate();
			}
			try (PreparedStatement subject = connection.prepareStatement(
					"""
					INSERT INTO subject (id, subject_code, name, status, created_at, updated_at)
					VALUES (?, 'V22SUB', 'V22 Subject', 'ACTIVE', NOW(6), NOW(6))
					""")) {
				subject.setString(1, SUBJECT_ID);
				subject.executeUpdate();
			}
			try (PreparedStatement course = connection.prepareStatement(
					"""
					INSERT INTO course
					  (id, subject_id, academic_class_id, semester_id, name, created_at, updated_at)
					VALUES (?, ?, ?, ?, 'V22 Course', NOW(6), NOW(6))
					""")) {
				course.setString(1, COURSE_ID);
				course.setString(2, SUBJECT_ID);
				course.setString(3, CLASS_ID);
				course.setString(4, SEMESTER_ID);
				course.executeUpdate();
			}
			try (PreparedStatement project = connection.prepareStatement(
					"INSERT INTO project (id, course_id, name, created_at, updated_at) VALUES (?, ?, 'V22 Project', NOW(6), NOW(6))")) {
				project.setString(1, PROJECT_ID);
				project.setString(2, COURSE_ID);
				project.executeUpdate();
			}
			try (PreparedStatement jira = connection.prepareStatement(
					"""
					INSERT INTO jira_integration
					  (id, project_id, cloud_id, jira_project_id, project_key, connection_status, consecutive_failures, version, created_at, updated_at)
					VALUES (?, ?, 'cloud-v22', '10022', 'V22', 'ACTIVE', 0, 0, NOW(6), NOW(6))
					""")) {
				jira.setString(1, LEGACY_JIRA_INTEGRATION_ID);
				jira.setString(2, PROJECT_ID);
				jira.executeUpdate();
			}
			try (PreparedStatement task = connection.prepareStatement(
					"INSERT INTO task (id, project_id, title, created_at, updated_at) VALUES (?, ?, 'Legacy task', NOW(6), NOW(6))")) {
				task.setString(1, LEGACY_TASK_ID);
				task.setString(2, PROJECT_ID);
				task.executeUpdate();
			}
		}
	}

	private Project persistProject(String name) {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + (int) (Math.random() * 100000));
		academicClass.setName("Test Class");
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
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		return projects.save(project);
	}

	private Task persistTask(Project project, String title) {
		JiraIntegration jira = jiraIntegrations
				.findByProject_Id(project.getId())
				.orElseGet(() -> jiraIntegrations.save(jiraFor(project)));
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(jira);
		task.setTitle(title);
		task.setStatus(TaskStatus.TODO);
		task.setExternalId(UUID.randomUUID().toString());
		task.setExternalKey("SAGA-" + title);
		return tasks.saveAndFlush(task);
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

	private Map<Integer, Integer> checksumsByVersion() throws SQLException {
		Map<Integer, Integer> checksums = new LinkedHashMap<>();
		try (Connection connection = dataSource.getConnection();
				PreparedStatement statement = connection.prepareStatement(
						"SELECT version, checksum FROM flyway_schema_history WHERE success = 1 AND version IS NOT NULL");
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				checksums.put(Integer.parseInt(rs.getString("version")), rs.getInt("checksum"));
			}
		}
		return checksums;
	}

	private static String nullable(Connection connection, String column) throws SQLException {
		return columnMeta(connection, column, "IS_NULLABLE");
	}

	private static String dataType(Connection connection, String column) throws SQLException {
		return columnMeta(connection, column, "DATA_TYPE");
	}

	private static Integer maxLength(Connection connection, String column) throws SQLException {
		try (PreparedStatement columns = connection.prepareStatement(
				"""
				SELECT CHARACTER_MAXIMUM_LENGTH
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = 'task'
				  AND COLUMN_NAME = ?
				""")) {
			columns.setString(1, column);
			try (ResultSet rs = columns.executeQuery()) {
				assertTrue(rs.next());
				return rs.getObject(1) == null ? null : rs.getInt(1);
			}
		}
	}

	private static String columnMeta(Connection connection, String column, String field) throws SQLException {
		try (PreparedStatement columns = connection.prepareStatement(
				"""
				SELECT CHARACTER_SET_NAME, COLLATION_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = 'task'
				  AND COLUMN_NAME = ?
				""")) {
			columns.setString(1, column);
			try (ResultSet rs = columns.executeQuery()) {
				assertTrue(rs.next());
				return rs.getString(field);
			}
		}
	}
}

package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.project.Project;
import com.saga.be.integration.crypto.TokenEncryptor;
import com.saga.be.integration.jira.JiraOAuthClient;
import com.saga.be.integration.jira.JiraOAuthClient.TokenResponse;
import com.saga.be.integration.jira.JiraTeamTokenService;
import java.time.LocalDateTime;
import java.util.Base64;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Disposable MySQL 8: two {@link JiraTeamTokenService} instances, one row, overlapping refresh
 * HTTP. Proves InnoDB {@code lockById} / {@code @Version} persist — not Atlassian rotation. Opt-in:
 * {@code saga.verify.mysql=true} and {@code saga.verify.mysql.url}.
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
class JiraTeamTokenRefreshMysqlIT {

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
	private UserAccountRepository users;
	@Autowired
	private JiraIntegrationRepository integrations;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@BeforeEach
	void migrateClean() {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void mysqlTwoReplicas_lockByIdPersistsOneWinner_httpOutsideTx() throws Exception {
		UserAccount connected = users.save(userAccount());
		Project project = persistProject();
		byte[] key = new byte[32];
		key[0] = 23;
		TokenEncryptor encryptor = new TokenEncryptor(Base64.getEncoder().encodeToString(key));
		JiraIntegration row = new JiraIntegration();
		row.setProject(project);
		row.setConnectedBy(connected);
		row.setConnectionStatus(IntegrationStatus.ACTIVE);
		row.setConsecutiveFailures(0);
		row.setVersion(0L);
		row.setCloudId("cloud");
		row.setProjectKey("SAGA");
		row = integrations.saveAndFlush(row);
		String realAad = TokenEncryptor.aad(row.getId().toString(), "JIRA", connected.getId().toString());
		row.setEncryptedAccessToken(encryptor.encrypt("access-stale", realAad));
		row.setEncryptedRefreshToken(encryptor.encrypt("refresh-live", realAad));
		row.setTokenExpiresAt(LocalDateTime.now().minusMinutes(5));
		row = integrations.saveAndFlush(row);
		UUID integrationId = row.getId();
		long versionBefore = row.getVersion();

		JiraOAuthClient oauth = org.mockito.Mockito.mock(JiraOAuthClient.class);
		CyclicBarrier bothRefreshing = new CyclicBarrier(2);
		AtomicInteger httpCalls = new AtomicInteger();
		when(oauth.refresh(any())).thenAnswer(inv -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive())
					.as("oauth.refresh must not run inside a MySQL TX")
					.isFalse();
			httpCalls.incrementAndGet();
			bothRefreshing.await(15, TimeUnit.SECONDS);
			return new TokenResponse("access-" + Thread.currentThread().getName(), "refresh-new", 3600, "offline_access");
		});

		JiraTeamTokenService replicaA = new JiraTeamTokenService(integrations, encryptor, oauth, transactionManager);
		JiraTeamTokenService replicaB = new JiraTeamTokenService(integrations, encryptor, oauth, transactionManager);
		JiraIntegration handleA = integrations.findById(integrationId).orElseThrow();
		JiraIntegration handleB = integrations.findById(integrationId).orElseThrow();

		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<String> first = pool.submit(() -> replicaA.accessToken(handleA));
			Future<String> second = pool.submit(() -> replicaB.accessToken(handleB));
			String tokenA = first.get(30, TimeUnit.SECONDS);
			String tokenB = second.get(30, TimeUnit.SECONDS);
			assertThat(tokenA).isEqualTo(tokenB);
			assertThat(tokenA).startsWith("access-");
			assertThat(httpCalls.get()).isEqualTo(2);
			JiraIntegration stored = integrations.findById(integrationId).orElseThrow();
			assertThat(encryptor.decrypt(stored.getEncryptedAccessToken(), realAad)).isEqualTo(tokenA);
			assertThat(stored.getVersion()).isGreaterThan(versionBefore);
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
		} finally {
			pool.shutdownNow();
		}
	}

	private Project persistProject() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));
		Project project = new Project();
		project.setName("Project");
		project.setCourse(course);
		return projects.save(project);
	}

	private static UserAccount userAccount() {
		UserAccount account = new UserAccount();
		account.setEmail("jira-refresh-mysql-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName("Lecturer");
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
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
}

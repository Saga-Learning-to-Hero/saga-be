package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.service.academic.AcademicCatalogService.AuditRequest;
import com.saga.be.service.audit.AuditRedactor;
import com.saga.be.service.audit.AuditService;
import com.saga.be.realtime.ProjectSseHub;
import com.saga.be.realtime.UserSseHub;
import com.saga.be.security.AccountDisabledAfterCommitListener;
import com.saga.be.security.AccountDisabledEvent;
import com.saga.be.security.IndexedSessionRevocationService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.PayloadApplicationEvent;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class AdminUserCommandPersistTest {

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
	private LecturerProfileRepository lecturers;
	@Autowired
	private AuditLogRepository auditLogs;
	@Autowired
	private EntityManager entityManager;

	@Test
	void persistStudentTransitionWritesExactAuditAndNoOpDoesNot() {
		UserAccount actor = persistAccount("root@saga.local", "Root", AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount student = persistStudent("ada@fpt.edu.vn", "Ada", "SE123456", AccountStatus.ACTIVE);
		List<Object> published = new ArrayList<>();
		AdminUserCommandService service = commandService(published);
		AuditRequest auditRequest = new AuditRequest("req-9", "10.0.0.1", "JUnit");

		AdminUserResponse inactivated =
				service.updateStatus(actor.getId(), student.getId(), "INACTIVE", auditRequest);
		entityManager.flush();
		entityManager.clear();

		assertEquals("INACTIVE", inactivated.accountStatus());
		assertEquals("STUDENT", inactivated.role());
		assertEquals("SE123456", inactivated.studentCode());
		assertEquals(AccountStatus.INACTIVE, users.findById(student.getId()).orElseThrow().getAccountStatus());

		List<AuditLog> rows = auditLogs.findAll();
		assertEquals(1, rows.size());
		AuditLog row = rows.getFirst();
		assertEquals(AdminUserCommandService.USER_STATUS_CHANGED, row.getAction());
		assertEquals("user_account", row.getEntityType());
		assertEquals(student.getId(), row.getEntityId());
		assertEquals(actor.getId(), row.getActorUser().getId());
		assertEquals(AuditSource.API, row.getSource());
		assertEquals("{\"accountStatus\":\"ACTIVE\"}", row.getBeforeData());
		assertEquals("{\"accountStatus\":\"INACTIVE\"}", row.getAfterData());
		assertEquals("{\"role\":\"STUDENT\"}", row.getMetadataJson());
		assertTrue(row.getMetadataJson() == null || !row.getMetadataJson().toLowerCase().contains("token"));
		assertTrue(row.getMetadataJson() == null || !row.getMetadataJson().toLowerCase().contains("session"));
		assertEquals(1, published.size());
		assertEquals(student.getId(), ((AccountDisabledEvent) published.getFirst()).userId());

		AdminUserResponse noop = service.updateStatus(actor.getId(), student.getId(), "INACTIVE", auditRequest);
		entityManager.flush();
		assertEquals("INACTIVE", noop.accountStatus());
		assertEquals(1, auditLogs.findAll().size());
		assertEquals(1, published.size());
	}

	@Test
	void persistLecturerInactiveToActive() {
		UserAccount actor = persistAccount("root@saga.local", "Root", AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount lecturer = persistLecturer("lan@fe.edu.vn", "Lan", AccountStatus.INACTIVE);
		List<Object> published = new ArrayList<>();
		AdminUserResponse activated = commandService(published)
				.updateStatus(actor.getId(), lecturer.getId(), "ACTIVE", new AuditRequest(null, null, null));
		entityManager.flush();
		assertEquals("ACTIVE", activated.accountStatus());
		assertEquals("LECTURER", activated.role());
		assertEquals(AccountStatus.ACTIVE, users.findById(lecturer.getId()).orElseThrow().getAccountStatus());
		AuditLog row = auditLogs.findAll().getFirst();
		assertEquals("{\"accountStatus\":\"INACTIVE\"}", row.getBeforeData());
		assertEquals("{\"accountStatus\":\"ACTIVE\"}", row.getAfterData());
		assertEquals("{\"role\":\"LECTURER\"}", row.getMetadataJson());
		assertTrue(published.isEmpty());
	}

	@Test
	void redisRevocationFailureDoesNotReactivateCommittedInactive() {
		UserAccount actor = persistAccount("root@saga.local", "Root", AccountRole.ADMIN, AccountStatus.ACTIVE);
		UserAccount student = persistStudent("ada@fpt.edu.vn", "Ada", "SE123456", AccountStatus.ACTIVE);
		commandService(new ArrayList<>())
				.updateStatus(actor.getId(), student.getId(), "INACTIVE", new AuditRequest("req-x", null, null));
		entityManager.flush();
		assertEquals(AccountStatus.INACTIVE, users.findById(student.getId()).orElseThrow().getAccountStatus());

		IndexedSessionRevocationService failing = org.mockito.Mockito.mock(IndexedSessionRevocationService.class);
		org.mockito.Mockito.doThrow(new IllegalStateException("redis down")).when(failing).revokeAllForUser(student.getId());
		new AccountDisabledAfterCommitListener(failing, new UserSseHub(), new ProjectSseHub())
				.onAccountDisabled(new AccountDisabledEvent(student.getId(), java.time.Instant.parse("2026-09-15T12:00:00Z")));

		entityManager.flush();
		entityManager.clear();
		assertEquals(AccountStatus.INACTIVE, users.findById(student.getId()).orElseThrow().getAccountStatus());
	}

	private AdminUserCommandService commandService(List<Object> published) {
		ObjectMapper mapper = new ObjectMapper();
		ApplicationEventPublisher events = new ApplicationEventPublisher() {
			@Override
			public void publishEvent(ApplicationEvent event) {
				if (event instanceof PayloadApplicationEvent<?> payload) {
					published.add(payload.getPayload());
				} else {
					published.add(event);
				}
			}

			@Override
			public void publishEvent(Object event) {
				published.add(event);
			}
		};
		return new AdminUserCommandService(
				users,
				new AdminUserQueryService(users),
				new AuditService(auditLogs, students, new AuditRedactor(mapper), mapper),
				events);
	}

	private UserAccount persistStudent(String email, String name, String code, AccountStatus status) {
		UserAccount account = persistAccount(email, name, AccountRole.STUDENT, status);
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		students.save(profile);
		return account;
	}

	private UserAccount persistLecturer(String email, String name, AccountStatus status) {
		UserAccount account = persistAccount(email, name, AccountRole.LECTURER, status);
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		lecturers.save(profile);
		return account;
	}

	private UserAccount persistAccount(String email, String name, AccountRole role, AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(email.substring(0, email.indexOf('@')));
		account.setFullName(name);
		account.setAccountRole(role);
		account.setAccountStatus(status);
		account.setPasswordHash("not-a-secret-in-api");
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
		account.setCreatedAt(now);
		account.setUpdatedAt(now);
		return users.save(account);
	}
}

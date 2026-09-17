package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.saga.be.dto.admin.AdminAuditLogPageResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.audit.AuditLog;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AuditSource;
import com.saga.be.repository.AuditLogRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.transaction.annotation.Transactional;

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
@Transactional
class AdminAuditLogQueryCountTest {

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
	private AuditLogRepository auditLogs;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listQueryCountIsBoundedAndOrderIsDeterministic() {
		AdminAuditLogQueryService service = new AdminAuditLogQueryService(auditLogs, new ObjectMapper());
		UserAccount actor = persistActor("actor@saga.local");
		persistLog(actor, "COURSE_CREATED", "course", LocalDateTime.of(2026, 1, 1, 10, 0), "{\"a\":1}");
		persistLog(actor, "PROJECT_CREATED", "project", LocalDateTime.of(2026, 1, 3, 10, 0), "{\"b\":2}");
		persistLog(null, "COURSE_CREATED", "course", LocalDateTime.of(2026, 1, 2, 10, 0), null);
		entityManager.flush();
		entityManager.clear();

		Statistics stats = statistics();
		stats.clear();
		AdminAuditLogPageResponse page = service.list(null, null, null, null, null, null, 0, 50);
		assertEquals(3, page.total());
		assertEquals("PROJECT_CREATED", page.items().get(0).action());
		assertEquals("COURSE_CREATED", page.items().get(1).action());
		assertEquals("COURSE_CREATED", page.items().get(2).action());
		long queriesForThree = stats.getPrepareStatementCount();
		assertTrue(queriesForThree >= 1 && queriesForThree <= 2);

		persistLog(actor, "TEAM_MEMBER_MOVED", "team", LocalDateTime.of(2026, 1, 4, 10, 0), null);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AdminAuditLogPageResponse second = service.list(null, null, null, null, null, null, 0, 50);
		assertEquals(4, second.total());
		assertEquals("TEAM_MEMBER_MOVED", second.items().getFirst().action());
		assertEquals(queriesForThree, stats.getPrepareStatementCount());
	}

	@Test
	void historicalIdsWithNullSnapshotsAreReturnedWithoutCurrentNameFallback() {
		AdminAuditLogQueryService service = new AdminAuditLogQueryService(auditLogs, new ObjectMapper());
		UserAccount actor = persistActor("legacy@saga.local");
		UUID projectId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa01");
		UUID teamId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaa02");
		AuditLog legacy = persistLog(actor, "GITHUB_REPOSITORY_CONNECTED", "git_repo", LocalDateTime.of(2026, 2, 1, 10, 0), null);
		legacy.setContextProjectId(projectId);
		legacy.setContextTeamId(teamId);
		auditLogs.save(legacy);
		AuditLog current = persistLog(actor, "PROJECT_CREATED", "project", LocalDateTime.of(2026, 2, 2, 10, 0), "{\"ok\":true}");
		current.setContextProjectId(projectId);
		current.setContextProjectNameSnapshot("SAGA V1");
		current.setContextTeamId(teamId);
		current.setContextTeamNoSnapshot(2);
		current.setContextTeamNameSnapshot("Alpha");
		auditLogs.save(current);
		entityManager.flush();
		entityManager.clear();

		Statistics stats = statistics();
		stats.clear();
		AdminAuditLogPageResponse page = service.list(null, null, null, null, null, null, 0, 50);
		assertTrue(stats.getPrepareStatementCount() >= 1 && stats.getPrepareStatementCount() <= 2);
		assertEquals("PROJECT_CREATED", page.items().get(0).action());
		assertEquals("SAGA V1", page.items().get(0).contextProjectNameSnapshot());
		assertEquals(2, page.items().get(0).contextTeamNoSnapshot());
		assertEquals("Alpha", page.items().get(0).contextTeamNameSnapshot());
		assertEquals("GITHUB_REPOSITORY_CONNECTED", page.items().get(1).action());
		assertEquals(projectId, page.items().get(1).contextProjectId());
		assertEquals(teamId, page.items().get(1).contextTeamId());
		assertEquals(null, page.items().get(1).contextProjectNameSnapshot());
		assertEquals(null, page.items().get(1).contextTeamNoSnapshot());
		assertEquals(null, page.items().get(1).contextTeamNameSnapshot());
	}

	@Test
	void filtersByActorActionEntityAndTimeRangeThenPaginates() {
		AdminAuditLogQueryService service = new AdminAuditLogQueryService(auditLogs, new ObjectMapper());
		UserAccount actor = persistActor("filter@saga.local");
		UUID courseId = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
		AuditLog match = persistLog(actor, "COURSE_CREATED", "course", LocalDateTime.of(2026, 5, 2, 12, 0), null);
		match.setEntityId(courseId);
		auditLogs.save(match);
		persistLog(actor, "PROJECT_CREATED", "project", LocalDateTime.of(2026, 5, 3, 12, 0), null);
		persistLog(null, "COURSE_CREATED", "course", LocalDateTime.of(2026, 5, 1, 12, 0), null);
		entityManager.flush();
		entityManager.clear();

		AdminAuditLogPageResponse byActor = service.list(actor.getId(), null, null, null, null, null, 0, 50);
		assertEquals(2, byActor.total());
		AdminAuditLogPageResponse byAction = service.list(null, "COURSE_CREATED", null, null, null, null, 0, 50);
		assertEquals(2, byAction.total());
		AdminAuditLogPageResponse byEntity =
				service.list(null, null, "course", courseId, null, null, 0, 50);
		assertEquals(1, byEntity.total());
		assertEquals(courseId, byEntity.items().getFirst().entityId());
		AdminAuditLogPageResponse byTime = service.list(
				null,
				null,
				null,
				null,
				LocalDateTime.of(2026, 5, 2, 0, 0),
				LocalDateTime.of(2026, 5, 2, 23, 59),
				0,
				50);
		assertEquals(1, byTime.total());
		AdminAuditLogPageResponse page0 = service.list(null, null, null, null, null, null, 0, 1);
		AdminAuditLogPageResponse page1 = service.list(null, null, null, null, null, null, 1, 1);
		assertEquals(3, page0.total());
		assertEquals("PROJECT_CREATED", page0.items().getFirst().action());
		assertEquals("COURSE_CREATED", page1.items().getFirst().action());
	}

	private UserAccount persistActor(String email) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(email.substring(0, email.indexOf('@')));
		account.setFullName("Actor");
		account.setAccountRole(AccountRole.ADMIN);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setCreatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		account.setUpdatedAt(LocalDateTime.of(2026, 1, 1, 0, 0));
		return users.save(account);
	}

	private AuditLog persistLog(UserAccount actor, String action, String entityType, LocalDateTime occurredAt, String metadata) {
		AuditLog log = new AuditLog();
		log.setActorUser(actor);
		if (actor != null) {
			log.setActorEmailSnapshot(actor.getEmail());
			log.setActorRoleSnapshot(actor.getAccountRole().name());
			log.setActorFullNameSnapshot(actor.getFullName());
		}
		log.setAction(action);
		log.setEntityType(entityType);
		log.setEntityId(UUID.randomUUID());
		log.setMetadataJson(metadata);
		log.setSource(AuditSource.API);
		log.setOccurredAt(occurredAt);
		log.setCreatedAt(occurredAt);
		log.setUpdatedAt(occurredAt);
		return auditLogs.save(log);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

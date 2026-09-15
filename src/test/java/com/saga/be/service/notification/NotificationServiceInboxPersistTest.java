package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.notification.NotificationReadAllResponse;
import com.saga.be.dto.notification.UserNotificationPageResponse;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
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
class NotificationServiceInboxPersistTest {

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
	private UserNotificationRepository notifications;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listIsOwnInboxOnlyWithDeterministicOrderAndUnreadFilter() {
		UserAccount owner = persistAccount("ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount other = persistAccount("bob@fpt.edu.vn", AccountRole.LECTURER);
		LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
		LocalDateTime t2 = LocalDateTime.of(2026, 9, 1, 11, 0);
		LocalDateTime t3 = LocalDateTime.of(2026, 9, 1, 12, 0);
		UserNotification older = persistNotification(owner, "Older", t1, null, "k-old");
		UserNotification sameTimeA = persistNotification(owner, "Tie A", t2, null, "k-a");
		UserNotification sameTimeB = persistNotification(owner, "Tie B", t2, t2, "k-b");
		UserNotification newest = persistNotification(owner, "Newest", t3, null, "k-new");
		persistNotification(other, "Other inbox", t3, null, "k-other");
		entityManager.flush();
		entityManager.clear();

		NotificationService service = service();
		UserNotificationPageResponse page = service.listOwn(owner.getId(), 0, 50, false);
		assertEquals(4, page.total());
		assertEquals(0, page.page());
		assertEquals(50, page.size());
		assertEquals("Newest", page.items().getFirst().title());
		assertEquals("Older", page.items().get(3).title());
		UserNotificationResponse firstTie = page.items().get(1);
		UserNotificationResponse secondTie = page.items().get(2);
		assertTrue(firstTie.id().toString().compareTo(secondTie.id().toString()) > 0);
		assertTrue(List.of("Tie A", "Tie B").containsAll(List.of(firstTie.title(), secondTie.title())));
		assertNull(page.items().getFirst().actionUrl());
		assertTrue(page.items().stream().noneMatch(row -> "Other inbox".equals(row.title())));

		UserNotificationPageResponse unread = service.listOwn(owner.getId(), 0, 50, true);
		assertEquals(3, unread.total());
		assertEquals("Newest", unread.items().getFirst().title());
		assertEquals("Older", unread.items().get(2).title());

		UserNotificationPageResponse page0 = service.listOwn(owner.getId(), 0, 1, false);
		UserNotificationPageResponse page1 = service.listOwn(owner.getId(), 1, 1, false);
		assertEquals(4, page0.total());
		assertEquals(1, page0.items().size());
		assertEquals("Newest", page0.items().getFirst().title());
		assertEquals(firstTie.title(), page1.items().getFirst().title());

		assertEquals(3, service.unreadCount(owner.getId()).unreadCount());
		assertEquals(1, service.unreadCount(other.getId()).unreadCount());
		assertEquals(newest.getId(), page.items().getFirst().id());
		assertEquals(older.getId(), page.items().get(3).id());
		assertEquals(sameTimeA.getId().equals(firstTie.id()) ? sameTimeA.getId() : sameTimeB.getId(), firstTie.id());
	}

	@Test
	void pageValidationAndQueryCountStayBounded() {
		UserAccount owner = persistAccount("ada@fpt.edu.vn", AccountRole.STUDENT);
		persistNotification(owner, "One", LocalDateTime.of(2026, 9, 1, 10, 0), null, "k1");
		persistNotification(owner, "Two", LocalDateTime.of(2026, 9, 1, 11, 0), null, "k2");
		entityManager.flush();
		entityManager.clear();
		NotificationService service = service();

		AcademicException pageEx = assertThrows(AcademicException.class, () -> service.listOwn(owner.getId(), -1, 50, false));
		AcademicException sizeEx = assertThrows(AcademicException.class, () -> service.listOwn(owner.getId(), 0, 201, false));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, pageEx.getCode());
		assertEquals(AcademicErrorCode.REQUEST_INVALID, sizeEx.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, pageEx.getStatus());

		Statistics stats = statistics();
		stats.clear();
		UserNotificationPageResponse page = service.listOwn(owner.getId(), 0, 50, false);
		assertEquals(2, page.total());
		long listQueries = stats.getPrepareStatementCount();
		assertTrue(listQueries >= 1 && listQueries <= 2);

		persistNotification(owner, "Three", LocalDateTime.of(2026, 9, 1, 12, 0), null, "k3");
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		assertEquals(3, service.listOwn(owner.getId(), 0, 50, false).total());
		assertEquals(listQueries, stats.getPrepareStatementCount());

		stats.clear();
		assertEquals(3, service.unreadCount(owner.getId()).unreadCount());
		assertEquals(1, stats.getPrepareStatementCount());
	}

	@Test
	void markReadIsIdempotentAndHidesForeignRows() {
		UserAccount owner = persistAccount("ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount other = persistAccount("bob@fe.edu.vn", AccountRole.LECTURER);
		UserNotification unread = persistNotification(owner, "Mine", LocalDateTime.of(2026, 9, 1, 10, 0), null, "k-mine");
		UserNotification foreign =
				persistNotification(other, "Theirs", LocalDateTime.of(2026, 9, 1, 10, 0), null, "k-theirs");
		entityManager.flush();
		entityManager.clear();
		NotificationService service = service();

		Statistics stats = statistics();
		stats.clear();
		UserNotificationResponse first = service.markRead(owner.getId(), unread.getId());
		assertEquals(unread.getId(), first.id());
		assertNotNull(first.readAt());
		long markQueries = stats.getPrepareStatementCount();
		assertTrue(markQueries >= 1 && markQueries <= 2);

		UserNotificationResponse second = service.markRead(owner.getId(), unread.getId());
		assertEquals(first.readAt(), second.readAt());
		assertEquals(0, service.unreadCount(owner.getId()).unreadCount());
		assertEquals(1, service.unreadCount(other.getId()).unreadCount());

		UUID missing = UUID.fromString("99999999-9999-9999-9999-999999999999");
		AcademicException unknown = assertThrows(AcademicException.class, () -> service.markRead(owner.getId(), missing));
		AcademicException hidden = assertThrows(AcademicException.class, () -> service.markRead(owner.getId(), foreign.getId()));
		assertEquals(AcademicErrorCode.NOTIFICATION_NOT_FOUND, unknown.getCode());
		assertEquals(AcademicErrorCode.NOTIFICATION_NOT_FOUND, hidden.getCode());
		assertEquals(HttpStatus.NOT_FOUND, unknown.getStatus());
		assertEquals(unknown.getStatus(), hidden.getStatus());
		assertEquals(unknown.getMessage(), hidden.getMessage());
		assertNull(notifications.findById(foreign.getId()).orElseThrow().getReadAt());
	}

	@Test
	void markAllReadUpdatesOnlyCallerUnreadRowsWithoutLoadingInbox() {
		UserAccount owner = persistAccount("ada@fpt.edu.vn", AccountRole.ADMIN);
		UserAccount other = persistAccount("bob@fpt.edu.vn", AccountRole.STUDENT);
		persistNotification(owner, "A", LocalDateTime.of(2026, 9, 1, 10, 0), null, "a");
		persistNotification(owner, "B", LocalDateTime.of(2026, 9, 1, 11, 0), null, "b");
		UserNotification alreadyRead =
				persistNotification(owner, "C", LocalDateTime.of(2026, 9, 1, 12, 0), LocalDateTime.of(2026, 9, 1, 12, 0), "c");
		persistNotification(other, "D", LocalDateTime.of(2026, 9, 1, 10, 0), null, "d");
		entityManager.flush();
		entityManager.clear();
		NotificationService service = service();

		Statistics stats = statistics();
		stats.clear();
		NotificationReadAllResponse first = service.markAllRead(owner.getId());
		assertEquals(2, first.updatedCount());
		assertNotNull(first.readAt());
		assertEquals(1, stats.getPrepareStatementCount());

		assertEquals(0, service.unreadCount(owner.getId()).unreadCount());
		assertEquals(1, service.unreadCount(other.getId()).unreadCount());
		assertEquals(
				alreadyRead.getReadAt(),
				notifications.findById(alreadyRead.getId()).orElseThrow().getReadAt());

		NotificationReadAllResponse second = service.markAllRead(owner.getId());
		assertEquals(0, second.updatedCount());
	}

	private NotificationService service() {
		return new NotificationService(notifications, users, unusedPublisher());
	}

	private static ApplicationEventPublisher unusedPublisher() {
		return event -> {
			throw new AssertionError("inbox tests must not write notifications");
		};
	}

	private UserNotification persistNotification(
			UserAccount recipient, String title, LocalDateTime createdAt, LocalDateTime readAt, String eventKey) {
		UserNotification row = new UserNotification();
		row.setRecipientUser(recipient);
		row.setNotificationType(NotificationType.SYSTEM);
		row.setTitle(title);
		row.setMessage(title + " body");
		row.setEventKey(eventKey);
		row.setReadAt(readAt);
		row.setCreatedAt(createdAt);
		row.setUpdatedAt(createdAt);
		UserNotification saved = notifications.save(row);
		entityManager.flush();
		entityManager
				.createNativeQuery("update user_notification set created_at = :ts where id = :id")
				.setParameter("ts", createdAt)
				.setParameter("id", saved.getId().toString())
				.executeUpdate();
		saved.setCreatedAt(createdAt);
		return saved;
	}

	private UserAccount persistAccount(String email, AccountRole role) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(email.substring(0, email.indexOf('@')));
		account.setFullName(email);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account.setPasswordHash("not-a-secret-in-api");
		LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
		account.setCreatedAt(now);
		account.setUpdatedAt(now);
		return users.save(account);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

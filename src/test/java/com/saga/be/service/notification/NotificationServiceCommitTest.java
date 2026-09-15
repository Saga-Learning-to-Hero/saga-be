package com.saga.be.service.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.notification.NotificationCreatedAfterCommitListener;
import com.saga.be.realtime.UserSseHub;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
class NotificationServiceCommitTest {

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
	static class TxSlice {
		@Bean
		UserSseHub userSseHub() {
			return Mockito.mock(UserSseHub.class);
		}

		@Bean
		NotificationCreatedAfterCommitListener notificationCreatedAfterCommitListener(UserSseHub userSseHub) {
			return new NotificationCreatedAfterCommitListener(userSseHub);
		}

		@Bean
		NotificationService notificationService(
				UserNotificationRepository notifications, UserAccountRepository users, ApplicationEventPublisher events) {
			return new NotificationService(notifications, users, events);
		}
	}

	@Autowired
	private UserAccountRepository users;
	@Autowired
	private UserNotificationRepository notifications;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private NotificationService notificationsWriter;
	@Autowired
	private UserSseHub userSse;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@Test
	void duplicateEventKeyDoesNotInsertTwiceAndDifferentRecipientsMayShareKey() {
		UserAccount ada = persistAccount("ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("bob@fe.edu.vn", AccountRole.LECTURER);
		NotificationService service = service();
		UserNotificationResponse first = transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.TEAM, "Added", "You joined a team.", null, "team-added:1"));
		UserNotificationResponse duplicate = transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.TEAM, "Added again", "Should not insert.", null, "team-added:1"));
		UserNotificationResponse other = transactions().execute(status -> service.createNotification(
				bob.getId(), NotificationType.TEAM, "Added", "Bob joined.", null, "team-added:1"));
		UserNotificationResponse nullKeyOne = transactions().execute(status ->
				service.createNotification(ada.getId(), NotificationType.SYSTEM, "One", "First", null, null));
		UserNotificationResponse nullKeyTwo = transactions().execute(status ->
				service.createNotification(ada.getId(), NotificationType.SYSTEM, "Two", "Second", null, null));

		assertEquals(first.id(), duplicate.id());
		assertEquals("Added", duplicate.title());
		assertNotEquals(first.id(), other.id());
		assertNotEquals(nullKeyOne.id(), nullKeyTwo.id());
		assertEquals(4, notifications.count());
	}

	@Test
	void afterCommitSendsNotificationCreatedAndRollbackDoesNot() {
		UserAccount ada = persistAccount("commit-ada@fpt.edu.vn", AccountRole.STUDENT);
		NotificationService service = service();
		Mockito.reset(userSse);

		UserNotificationResponse committed = transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.COURSE, "Enrolled", "You were enrolled.", "/courses", "enroll:" + ada.getId()));
		verify(userSse)
				.notifyCreated(eq(ada.getId()), eq(committed.id()), any());
		assertTrue(notifications.findById(committed.id()).isPresent());

		Mockito.reset(userSse);
		UUID[] rolledBackId = new UUID[1];
		transactions().executeWithoutResult(status -> {
			UserNotificationResponse created = service.createNotification(
					ada.getId(),
					NotificationType.COURSE,
					"Rolled back",
					"Should vanish.",
					null,
					"enroll-rollback:" + ada.getId());
			rolledBackId[0] = created.id();
			status.setRollbackOnly();
		});
		verify(userSse, never()).notifyCreated(eq(ada.getId()), eq(rolledBackId[0]), any());
		assertTrue(notifications.findById(rolledBackId[0]).isEmpty());
	}

	@Test
	void sseFailureDoesNotDeleteCommittedNotification() {
		UserAccount ada = persistAccount("sse-ada@fpt.edu.vn", AccountRole.ADMIN);
		NotificationService service = service();
		Mockito.reset(userSse);
		Mockito.doThrow(new IllegalStateException("sse down")).when(userSse).notifyCreated(any(), any(), any());

		UserNotificationResponse created = transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.SYSTEM, "Hello", "Body", null, "sse-fail:" + ada.getId()));

		verify(userSse, times(1)).notifyCreated(eq(ada.getId()), eq(created.id()), any());
		assertTrue(notifications.findById(created.id()).isPresent());
	}

	private NotificationService service() {
		return notificationsWriter;
	}

	private UserAccount persistAccount(String email, AccountRole role) {
		return transactions().execute(status -> {
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
			UserAccount saved = users.save(account);
			entityManager.flush();
			return saved;
		});
	}
}

package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.config.FcmProperties;
import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.notification.NotificationCreatedAfterCommitListener;
import com.saga.be.realtime.UserSseHub;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
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
			"saga.auth.bootstrap-admin.enabled=false",
			"saga.fcm.enabled=true"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationDeliveryPlanningPersistTest {

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
				UserNotificationRepository notifications,
				UserAccountRepository users,
				ApplicationEventPublisher events,
				FirebaseInstallationRepository installations,
				NotificationDeliveryRepository deliveries) {
			FcmProperties properties = new FcmProperties();
			properties.setEnabled(true);
			return new NotificationService(notifications, users, events, installations, deliveries, properties);
		}
	}

	@Autowired
	private UserAccountRepository users;
	@Autowired
	private FirebaseInstallationRepository installations;
	@Autowired
	private UserNotificationRepository notifications;
	@Autowired
	private NotificationDeliveryRepository deliveries;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private NotificationService writer;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@Test
	void plansOnlyActiveOwnInstallationsWithTokens() {
		UserAccount ada = persistAccount("plan-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("plan-bob@fe.edu.vn", AccountRole.LECTURER);
		FirebaseInstallation web = persistInstallation(ada, "fid-web", "token-web", true);
		persistInstallation(ada, "fid-inactive", "token-inactive", false);
		FirebaseInstallation noToken = persistInstallation(ada, "fid-empty", null, true);
		persistInstallation(bob, "fid-bob", "token-bob", true);
		assertThat(noToken.getFcmToken()).isNull();

		UserNotificationResponse created = transactions().execute(status -> writer.createNotification(
				ada.getId(), NotificationType.TEAM, "Added", "You joined.", "/teams", "plan-key"));
		assertEquals(1, deliveries.countByNotification_Id(created.id()));
		NotificationDelivery row = transactions().execute(status ->
				deliveries.findAll().stream()
						.filter(item -> item.getNotification().getId().equals(created.id()))
						.findFirst()
						.orElseThrow());
		assertEquals(DeliveryStatus.PENDING, row.getDeliveryStatus());
		assertEquals(web.getId(), row.getInstallation().getId());
		assertEquals(0, row.getAttemptCount());

		UserNotificationResponse duplicate = transactions().execute(status -> writer.createNotification(
				ada.getId(), NotificationType.TEAM, "Again", "No.", null, "plan-key"));
		assertEquals(created.id(), duplicate.id());
		assertEquals(1, deliveries.countByNotification_Id(created.id()));

		FirebaseInstallation second = persistInstallation(ada, "fid-android", "token-android", true);
		UserNotificationResponse next = transactions().execute(status -> writer.createNotification(
				ada.getId(), NotificationType.SYSTEM, "Hello", "Body", null, "plan-key-2"));
		assertEquals(2, deliveries.countByNotification_Id(next.id()));
		assertTrue(deliveries.existsByNotification_IdAndInstallation_Id(next.id(), web.getId()));
		assertTrue(deliveries.existsByNotification_IdAndInstallation_Id(next.id(), second.getId()));
	}

	@Test
	void rollbackRemovesNotificationAndDeliveries() {
		UserAccount ada = persistAccount("plan-roll@fpt.edu.vn", AccountRole.STUDENT);
		persistInstallation(ada, "fid-roll", "token-roll", true);
		UUID[] rolledBack = new UUID[1];
		transactions().executeWithoutResult(status -> {
			UserNotificationResponse created = writer.createNotification(
					ada.getId(), NotificationType.SYSTEM, "Gone", "Body", null, "roll-key");
			rolledBack[0] = created.id();
			assertEquals(1, deliveries.countByNotification_Id(created.id()));
			status.setRollbackOnly();
		});
		assertTrue(notifications.findById(rolledBack[0]).isEmpty());
		assertEquals(0, deliveries.countByNotification_Id(rolledBack[0]));
	}

	@Test
	void disabledFcmCreatesNoDeliveryRows() {
		UserAccount ada = persistAccount("plan-off@fpt.edu.vn", AccountRole.STUDENT);
		persistInstallation(ada, "fid-off", "token-off", true);
		NotificationService disabled = new NotificationService(
				notifications, users, event -> {}, installations, deliveries, new FcmProperties());
		UserNotificationResponse created = transactions().execute(status -> disabled.createNotification(
				ada.getId(), NotificationType.SYSTEM, "Hello", "Body", null, "off-key"));
		assertEquals(0, deliveries.countByNotification_Id(created.id()));
	}

	private FirebaseInstallation persistInstallation(UserAccount owner, String fid, String token, boolean active) {
		return transactions().execute(status -> {
			FirebaseInstallation row = new FirebaseInstallation();
			row.setOwnerUser(owner);
			row.setFirebaseInstallationId(fid);
			row.setFcmToken(token);
			row.setPlatform(PushPlatform.WEB);
			row.setActive(active);
			row.setLastRegisteredAt(LocalDateTime.of(2026, 1, 1, 0, 0));
			if (!active) {
				row.setRevokedAt(LocalDateTime.of(2026, 1, 2, 0, 0));
			}
			row.setVersion(0L);
			FirebaseInstallation saved = installations.save(row);
			entityManager.flush();
			return saved;
		});
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

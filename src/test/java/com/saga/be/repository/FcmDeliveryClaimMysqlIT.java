package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.config.FcmProperties;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.DeliveryStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.entity.notification.NotificationDelivery;
import com.saga.be.entity.notification.UserNotification;
import com.saga.be.push.PushNotification;
import com.saga.be.push.PushNotificationSender;
import com.saga.be.service.notification.FcmDeliveryService;
import com.saga.be.service.notification.FcmDeliveryWorker;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

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
class FcmDeliveryClaimMysqlIT {

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
		FcmProperties fcmProperties() {
			FcmProperties properties = new FcmProperties();
			properties.setEnabled(true);
			properties.getWorker().setClaimStaleAfter(Duration.ofMinutes(5));
			properties.getWorker().setRetryDelay(Duration.ofSeconds(30));
			properties.getWorker().setMaxAttempts(5);
			properties.getWorker().setBatchSize(10);
			return properties;
		}

		@Bean
		PushNotificationSender pushNotificationSender() {
			return Mockito.mock(PushNotificationSender.class);
		}

		@Bean
		FcmDeliveryService fcmDeliveryService(
				NotificationDeliveryRepository deliveries,
				FirebaseInstallationRepository installations,
				FcmProperties properties) {
			return new FcmDeliveryService(deliveries, installations, properties);
		}

		@Bean
		FcmDeliveryWorker fcmDeliveryWorker(FcmDeliveryService deliveries, PushNotificationSender sender) {
			return new FcmDeliveryWorker(deliveries, sender);
		}
	}

	@Autowired
	private DataSource dataSource;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private UserNotificationRepository notifications;
	@Autowired
	private FirebaseInstallationRepository installations;
	@Autowired
	private NotificationDeliveryRepository deliveries;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PushNotificationSender sender;
	@Autowired
	private FcmDeliveryWorker worker;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

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
	void concurrentClaimAndStaleRecoveryDoNotDoubleSend() throws Exception {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		AtomicInteger inTransaction = new AtomicInteger();
		Mockito.doAnswer(invocation -> {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				inTransaction.incrementAndGet();
			}
			return null;
		})
				.when(sender)
				.send(any(PushNotification.class));

		UUID deliveryId = persistPending("mysql-fcm-ada@fpt.edu.vn", "mysql-fid", "mysql-token");
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger sent = new AtomicInteger();
		Runnable run = () -> {
			try {
				start.await();
				if (worker.processOne(deliveryId)) {
					sent.incrementAndGet();
				}
			} catch (Exception ex) {
				throw new IllegalStateException(ex);
			} finally {
				done.countDown();
			}
		};
		Thread.ofVirtual().start(run);
		Thread.ofVirtual().start(run);
		start.countDown();
		assertTrue(done.await(30, TimeUnit.SECONDS));
		assertEquals(1, sent.get());
		assertEquals(0, inTransaction.get());
		verify(sender, times(1)).send(any(PushNotification.class));
		assertEquals(DeliveryStatus.SENT, deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus());

		UUID staleId = persistPending("mysql-fcm-stale@fpt.edu.vn", "mysql-fid-stale", "mysql-token-stale");
		transactions().executeWithoutResult(status -> {
			NotificationDelivery row = deliveries.findById(staleId).orElseThrow();
			row.setAttemptCount(1);
			row.setProcessingStartedAt(LocalDateTime.now().minusMinutes(10));
			row.setLastAttemptAt(LocalDateTime.now().minusMinutes(10));
			deliveries.save(row);
		});
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		assertEquals(1, worker.processBatch());
		assertEquals(DeliveryStatus.SENT, deliveries.findById(staleId).orElseThrow().getDeliveryStatus());
		verify(sender, times(1)).send(any(PushNotification.class));
		assertThat(deliveries.count()).isEqualTo(2);
	}

	private UUID persistPending(String email, String fid, String token) {
		return transactions().execute(status -> {
			UserAccount account = new UserAccount();
			account.setEmail(email);
			account.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
			account.setFullName(email);
			account.setAccountRole(AccountRole.STUDENT);
			account.setAccountStatus(AccountStatus.ACTIVE);
			account.setPasswordHash("not-a-secret-in-api");
			LocalDateTime now = LocalDateTime.of(2026, 1, 1, 0, 0);
			account.setCreatedAt(now);
			account.setUpdatedAt(now);
			UserAccount savedUser = users.save(account);
			FirebaseInstallation installation = new FirebaseInstallation();
			installation.setOwnerUser(savedUser);
			installation.setFirebaseInstallationId(fid);
			installation.setFcmToken(token);
			installation.setPlatform(PushPlatform.WEB);
			installation.setActive(true);
			installation.setLastRegisteredAt(now);
			installation.setVersion(0L);
			FirebaseInstallation savedInstall = installations.save(installation);
			UserNotification notification = new UserNotification();
			notification.setRecipientUser(savedUser);
			notification.setNotificationType(NotificationType.SYSTEM);
			notification.setTitle("Hello");
			notification.setMessage("Body");
			notification.setEventKey("evt-" + fid);
			UserNotification savedNotification = notifications.save(notification);
			NotificationDelivery delivery = new NotificationDelivery();
			delivery.setNotification(savedNotification);
			delivery.setInstallation(savedInstall);
			delivery.setDeliveryStatus(DeliveryStatus.PENDING);
			delivery.setAttemptCount(0);
			delivery.setVersion(0L);
			NotificationDelivery savedDelivery = deliveries.save(delivery);
			entityManager.flush();
			return savedDelivery.getId();
		});
	}
}

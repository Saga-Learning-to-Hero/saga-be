package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import com.saga.be.push.FcmFailureCodes;
import com.saga.be.push.PushNotification;
import com.saga.be.push.PushNotificationSender;
import com.saga.be.push.PushSendException;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.NotificationDeliveryRepository;
import com.saga.be.repository.UserAccountRepository;
import com.saga.be.repository.UserNotificationRepository;
import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
class FcmDeliveryWorkerPersistTest {

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
			properties.getWorker().setMaxAttempts(5);
			properties.getWorker().setBatchSize(20);
			properties.getWorker().setRetryDelay(Duration.ofSeconds(30));
			properties.getWorker().setClaimStaleAfter(Duration.ofMinutes(5));
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
	private FcmProperties fcmProperties;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@Test
	void validDeliveryIsSentOutsideTransaction() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		Mockito.doAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			return null;
		})
				.when(sender)
				.send(any(PushNotification.class));
		Fixture fixture = persistValid("worker-ada@fpt.edu.vn", "fid-ok", "token-ok");
		assertTrue(worker.processOne(fixture.deliveryId()));
		NotificationDelivery row = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertEquals(DeliveryStatus.SENT, row.getDeliveryStatus());
		assertNotNull(row.getSentAt());
		assertNull(row.getProcessingStartedAt());
		verify(sender, times(1)).send(any(PushNotification.class));
	}

	@Test
	void ownershipMismatchIsSkippedWithoutSend() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		UserAccount ada = persistAccount("skip-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("skip-bob@fe.edu.vn", AccountRole.LECTURER);
		UserNotification notification = persistNotification(ada, "skip-own");
		FirebaseInstallation bobs = persistInstallation(bob, "fid-bob-skip", "token-bob-skip", true);
		UUID deliveryId = persistDelivery(notification, bobs);
		assertFalse(worker.processOne(deliveryId));
		assertEquals(DeliveryStatus.SKIPPED, deliveries.findById(deliveryId).orElseThrow().getDeliveryStatus());
		assertEquals(
				FcmFailureCodes.OWNERSHIP_MISMATCH,
				deliveries.findById(deliveryId).orElseThrow().getFailureCode());
		verify(sender, never()).send(any());
	}

	@Test
	void inactiveAndMissingTokenAreSkipped() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		UserAccount ada = persistAccount("skip-dev@fpt.edu.vn", AccountRole.STUDENT);
		UserNotification notification = persistNotification(ada, "skip-dev");
		FirebaseInstallation inactive = persistInstallation(ada, "fid-off", "token-off", false);
		FirebaseInstallation empty = persistInstallation(ada, "fid-empty", null, true);
		UUID inactiveDelivery = persistDelivery(notification, inactive);
		UUID emptyDelivery = persistDelivery(notification, empty);
		assertFalse(worker.processOne(inactiveDelivery));
		assertFalse(worker.processOne(emptyDelivery));
		assertEquals(DeliveryStatus.SKIPPED, deliveries.findById(inactiveDelivery).orElseThrow().getDeliveryStatus());
		assertEquals(DeliveryStatus.SKIPPED, deliveries.findById(emptyDelivery).orElseThrow().getDeliveryStatus());
		verify(sender, never()).send(any());
	}

	@Test
	void unregisteredTokenFailsAndRevokesInstallation() {
		Logger logger = (Logger) LoggerFactory.getLogger(FcmDeliveryWorker.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		try {
			Mockito.reset(sender);
			Mockito.when(sender.isEnabled()).thenReturn(true);
			Mockito.doThrow(new PushSendException(FcmFailureCodes.UNREGISTERED, false, true, "FCM token is unregistered."))
					.when(sender)
					.send(any());
			Fixture fixture = persistValid("fail-ada@fpt.edu.vn", "fid-dead", "token-dead-secret");
			worker.processOne(fixture.deliveryId());
			NotificationDelivery row = deliveries.findById(fixture.deliveryId()).orElseThrow();
			assertEquals(DeliveryStatus.FAILED, row.getDeliveryStatus());
			assertEquals(FcmFailureCodes.UNREGISTERED, row.getFailureCode());
			FirebaseInstallation installation = installations.findById(fixture.installationId()).orElseThrow();
			assertFalse(installation.getActive());
			assertNull(installation.getFcmToken());
			assertNotNull(installation.getRevokedAt());
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", (a, b) -> a + "\n" + b);
			assertThat(logs).doesNotContain("token-dead-secret");
		} finally {
			logger.detachAppender(appender);
		}
	}

	@Test
	void transientFailureStaysPendingAndMaxAttemptsFails() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		Mockito.doThrow(new PushSendException(FcmFailureCodes.UNAVAILABLE, true, false, "FCM unavailable."))
				.when(sender)
				.send(any());
		Fixture retryable = persistValid("retry-ada@fpt.edu.vn", "fid-retry", "token-retry");
		worker.processOne(retryable.deliveryId());
		NotificationDelivery afterFirst = deliveries.findById(retryable.deliveryId()).orElseThrow();
		assertEquals(DeliveryStatus.PENDING, afterFirst.getDeliveryStatus());
		assertEquals(1, afterFirst.getAttemptCount());
		assertNull(afterFirst.getProcessingStartedAt());

		int previous = fcmProperties.getWorker().getMaxAttempts();
		fcmProperties.getWorker().setMaxAttempts(1);
		try {
			Fixture terminal = persistValid("max-ada@fpt.edu.vn", "fid-max", "token-max");
			worker.processOne(terminal.deliveryId());
			assertEquals(DeliveryStatus.FAILED, deliveries.findById(terminal.deliveryId()).orElseThrow().getDeliveryStatus());
		} finally {
			fcmProperties.getWorker().setMaxAttempts(previous);
		}
	}

	@Test
	void concurrentClaimSendsOnce() throws Exception {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		AtomicBoolean inTransaction = new AtomicBoolean(false);
		Mockito.doAnswer(invocation -> {
			if (TransactionSynchronizationManager.isActualTransactionActive()) {
				inTransaction.set(true);
			}
			return null;
		})
				.when(sender)
				.send(any());
		Fixture fixture = persistValid("conc-ada@fpt.edu.vn", "fid-conc", "token-conc");
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger sent = new AtomicInteger();
		Runnable run = () -> {
			try {
				start.await();
				if (worker.processOne(fixture.deliveryId())) {
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
		assertTrue(done.await(20, TimeUnit.SECONDS));
		assertEquals(1, sent.get());
		assertFalse(inTransaction.get());
		verify(sender, times(1)).send(any());
		assertEquals(DeliveryStatus.SENT, deliveries.findById(fixture.deliveryId()).orElseThrow().getDeliveryStatus());
	}

	@Test
	void staleClaimCanBeRecovered() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		Fixture fixture = persistValid("stale-ada@fpt.edu.vn", "fid-stale", "token-stale");
		transactions().executeWithoutResult(status -> {
			NotificationDelivery row = deliveries.findById(fixture.deliveryId()).orElseThrow();
			row.setAttemptCount(1);
			row.setProcessingStartedAt(LocalDateTime.now().minusMinutes(10));
			row.setLastAttemptAt(LocalDateTime.now().minusMinutes(10));
			deliveries.save(row);
		});
		assertTrue(worker.processOne(fixture.deliveryId()));
		assertEquals(DeliveryStatus.SENT, deliveries.findById(fixture.deliveryId()).orElseThrow().getDeliveryStatus());
	}

	@Test
	void disabledSenderDoesNotClaim() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(false);
		persistValid("off-ada@fpt.edu.vn", "fid-disabled", "token-disabled");
		assertEquals(0, worker.processBatch());
		verify(sender, never()).send(any());
	}

	@Test
	void payloadInvalidArgumentFailsWithoutRevokingInstallation() {
		Mockito.reset(sender);
		Mockito.when(sender.isEnabled()).thenReturn(true);
		Mockito.doThrow(new PushSendException(
						FcmFailureCodes.INVALID_ARGUMENT, false, false, "Invalid data payload key"))
				.when(sender)
				.send(any());
		Fixture fixture = persistValid("payload-ada@fpt.edu.vn", "fid-payload", "keep-this-token");
		worker.processOne(fixture.deliveryId());
		NotificationDelivery row = deliveries.findById(fixture.deliveryId()).orElseThrow();
		assertEquals(DeliveryStatus.FAILED, row.getDeliveryStatus());
		assertEquals(FcmFailureCodes.INVALID_ARGUMENT, row.getFailureCode());
		FirebaseInstallation installation = installations.findById(fixture.installationId()).orElseThrow();
		assertTrue(installation.getActive());
		assertEquals("keep-this-token", installation.getFcmToken());
		assertNull(installation.getRevokedAt());
	}

	private Fixture persistValid(String email, String fid, String token) {
		UserAccount ada = persistAccount(email, AccountRole.STUDENT);
		UserNotification notification = persistNotification(ada, "evt-" + fid);
		FirebaseInstallation installation = persistInstallation(ada, fid, token, true);
		UUID deliveryId = persistDelivery(notification, installation);
		return new Fixture(deliveryId, installation.getId());
	}

	private UUID persistDelivery(UserNotification notification, FirebaseInstallation installation) {
		return transactions().execute(status -> {
			NotificationDelivery row = new NotificationDelivery();
			row.setNotification(notification);
			row.setInstallation(installation);
			row.setDeliveryStatus(DeliveryStatus.PENDING);
			row.setAttemptCount(0);
			row.setVersion(0L);
			NotificationDelivery saved = deliveries.save(row);
			entityManager.flush();
			return saved.getId();
		});
	}

	private UserNotification persistNotification(UserAccount recipient, String eventKey) {
		return transactions().execute(status -> {
			UserNotification row = new UserNotification();
			row.setRecipientUser(recipient);
			row.setNotificationType(NotificationType.SYSTEM);
			row.setTitle("Hello");
			row.setMessage("Body");
			row.setActionUrl("/inbox");
			row.setEventKey(eventKey);
			UserNotification saved = notifications.save(row);
			entityManager.flush();
			return saved;
		});
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
			account.setUsername("u" + UUID.randomUUID().toString().replace("-", "").substring(0, 16));
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

	private record Fixture(UUID deliveryId, UUID installationId) {}
}

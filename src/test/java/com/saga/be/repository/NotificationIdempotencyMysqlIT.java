package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.saga.be.dto.notification.UserNotificationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.NotificationType;
import com.saga.be.notification.NotificationCreatedAfterCommitListener;
import com.saga.be.realtime.UserSseHub;
import com.saga.be.service.notification.NotificationService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mockito.ArgumentCaptor;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disposable MySQL 8 + InnoDB row locks on {@code user_account}. Opt-in:
 * {@code saga.verify.mysql=true} and {@code saga.verify.mysql.url}. Nested TxSlice stays out of
 * {@code com.saga.be} component scan of sibling {@code @SpringBootTest}s.
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
			"spring.flyway.enabled=true",
			"spring.flyway.user=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
			"spring.flyway.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class NotificationIdempotencyMysqlIT {

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
	void mysqlConcurrentSameEventKeyIsExactlyOneRowAndOneAfterCommitSse() throws Exception {
		UserAccount ada = persistAccount("mysql-ada-" + UUID.randomUUID() + "@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("mysql-bob-" + UUID.randomUUID() + "@fe.edu.vn", AccountRole.LECTURER);
		NotificationService service = notificationsWriter;
		Mockito.reset(userSse);
		String eventKey = "team-added:" + ada.getId();

		int threads = 2;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		List<UUID> ids = java.util.Collections.synchronizedList(new ArrayList<>());
		AtomicInteger unexpectedRollback = new AtomicInteger();

		for (int i = 0; i < threads; i++) {
			final int n = i;
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					UserNotificationResponse created = transactions().execute(status -> {
						UserNotificationResponse row = service.createNotification(
								ada.getId(),
								NotificationType.TEAM,
								"Added",
								"You joined a team.",
								null,
								eventKey);
						UserAccount locked = users.findById(ada.getId()).orElseThrow();
						locked.setFullName("mysql-touched-" + n);
						users.save(locked);
						return row;
					});
					ids.add(created.id());
				} catch (UnexpectedRollbackException ex) {
					unexpectedRollback.incrementAndGet();
					failure.compareAndSet(null, ex);
				} catch (Throwable ex) {
					failure.compareAndSet(null, ex);
				} finally {
					done.countDown();
				}
			});
		}

		start.countDown();
		assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).as("MySQL concurrent create failed").isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertThat(ids).hasSize(2);
		assertThat(ids.stream().distinct().count()).isEqualTo(1);

		long adaRows = notifications.countByRecipientUser_Id(ada.getId());
		assertThat(adaRows).isEqualTo(1);
		assertThat(users.findById(ada.getId()).orElseThrow().getFullName()).startsWith("mysql-touched-");

		ArgumentCaptor<UUID> notificationId = ArgumentCaptor.forClass(UUID.class);
		verify(userSse, times(1)).notifyCreated(eq(ada.getId()), notificationId.capture(), any());
		assertThat(notificationId.getValue()).isEqualTo(ids.getFirst());

		transactions().execute(status -> service.createNotification(
				bob.getId(), NotificationType.TEAM, "Added", "Bob", null, eventKey));
		assertThat(notifications.countByRecipientUser_Id(bob.getId())).isEqualTo(1);
		assertThat(notifications.countByRecipientUser_Id(ada.getId())).isEqualTo(1);
		assertThat(notifications.countByRecipientUser_Id(bob.getId())).isEqualTo(1);
		assertThat(notifications.countByRecipientUser_Id(ada.getId())
						+ notifications.countByRecipientUser_Id(bob.getId()))
				.isEqualTo(2);

		transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.SYSTEM, "N1", "first", null, null));
		transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.SYSTEM, "N2", "second", null, null));
		assertThat(notifications.countByRecipientUser_Id(ada.getId())).isEqualTo(3);

		cleanup(ada.getId(), bob.getId());
	}

	private void cleanup(UUID adaId, UUID bobId) {
		transactions().executeWithoutResult(status -> {
			notifications.deleteByRecipientUser_Id(adaId);
			notifications.deleteByRecipientUser_Id(bobId);
			users.deleteById(adaId);
			users.deleteById(bobId);
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
}

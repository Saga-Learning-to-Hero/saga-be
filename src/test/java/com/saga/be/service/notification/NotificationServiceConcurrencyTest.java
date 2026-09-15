package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real H2 + {@code SELECT ... FOR UPDATE} on {@code user_account}. Concurrent creates run inside
 * {@link TransactionTemplate} so lock, insert, and sibling business work share one transaction.
 */
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
class NotificationServiceConcurrencyTest {

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
	void concurrentSameRecipientAndEventKeyInsertsOneRowAndOneSse() throws Exception {
		UserAccount ada = persistAccount("conc-ada@fpt.edu.vn", AccountRole.STUDENT);
		NotificationService service = service();
		Mockito.reset(userSse);
		String eventKey = "team-added:" + ada.getId();
		int threads = 8;
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
						locked.setFullName("touched-" + n);
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
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).as("concurrent create failed").isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertThat(ids.stream().distinct().count()).isEqualTo(1);
		assertThat(notifications.countByRecipientUser_Id(ada.getId())).isEqualTo(1);
		assertThat(users.findById(ada.getId()).orElseThrow().getFullName()).startsWith("touched-");

		ArgumentCaptor<UUID> notificationId = ArgumentCaptor.forClass(UUID.class);
		verify(userSse, times(1)).notifyCreated(eq(ada.getId()), notificationId.capture(), any());
		assertThat(notificationId.getValue()).isEqualTo(ids.getFirst());
		verify(userSse, never()).notifyDisabled(any(), any());
	}

	@Test
	void nullEventKeyAllowsMultipleRowsAndDifferentRecipientsMayShareKey() throws Exception {
		UserAccount ada = persistAccount("null-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("null-bob@fe.edu.vn", AccountRole.LECTURER);
		NotificationService service = service();
		Mockito.reset(userSse);

		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		Thread.ofVirtual().start(() -> runCreate(start, done, failure, () -> service.createNotification(
				ada.getId(), NotificationType.SYSTEM, "One", "First", null, null)));
		Thread.ofVirtual().start(() -> runCreate(start, done, failure, () -> service.createNotification(
				ada.getId(), NotificationType.SYSTEM, "Two", "Second", null, null)));
		start.countDown();
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).isNull();
		assertThat(notifications.countByRecipientUser_Id(ada.getId())).isEqualTo(2);

		UserNotificationResponse adaTeam = transactions().execute(status -> service.createNotification(
				ada.getId(), NotificationType.TEAM, "Added", "Ada", null, "shared-key"));
		UserNotificationResponse bobTeam = transactions().execute(status -> service.createNotification(
				bob.getId(), NotificationType.TEAM, "Added", "Bob", null, "shared-key"));
		assertThat(adaTeam.id()).isNotEqualTo(bobTeam.id());
		assertThat(notifications.countByRecipientUser_Id(ada.getId())).isEqualTo(3);
		assertThat(notifications.countByRecipientUser_Id(bob.getId())).isEqualTo(1);
	}

	private void runCreate(
			CountDownLatch start,
			CountDownLatch done,
			AtomicReference<Throwable> failure,
			java.util.function.Supplier<UserNotificationResponse> action) {
		try {
			start.await();
			transactions().executeWithoutResult(status -> action.get());
		} catch (Throwable ex) {
			failure.compareAndSet(null, ex);
		} finally {
			done.countDown();
		}
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

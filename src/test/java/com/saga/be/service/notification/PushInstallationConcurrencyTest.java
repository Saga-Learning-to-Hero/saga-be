package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.UserAccountRepository;
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
import org.springframework.transaction.UnexpectedRollbackException;
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
class PushInstallationConcurrencyTest {

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
		PushInstallationService pushInstallationService(
				FirebaseInstallationRepository installations, UserAccountRepository users) {
			return new PushInstallationService(installations, users);
		}
	}

	@Autowired
	private UserAccountRepository users;
	@Autowired
	private FirebaseInstallationRepository installations;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private PushInstallationService writer;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@Test
	void concurrentSameFidRotatesInsteadOfDuplicating() throws Exception {
		UserAccount ada = persistAccount("push-conc@fpt.edu.vn", AccountRole.STUDENT);
		String fid = "fid-concurrent";
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
					PushInstallationResponse created = transactions().execute(status -> writer.register(
							ada.getId(), fid, "token-" + n, PushPlatform.WEB));
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
		assertThat(failure.get()).as("concurrent register failed").isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertThat(ids.stream().distinct().count()).isEqualTo(1);
		assertThat(installations.countByOwnerUser_Id(ada.getId())).isEqualTo(1);
		assertThat(installations.findById(ids.getFirst()).orElseThrow().getFirebaseInstallationId()).isEqualTo(fid);
	}

	@Test
	void tokenSwapDoesNotDeadlock() throws Exception {
		UserAccount ada = persistAccount("push-swap@fpt.edu.vn", AccountRole.STUDENT);
		PushInstallationResponse a = transactions().execute(status -> writer.register(
				ada.getId(), "fid-swap-a", "token-swap-1", PushPlatform.WEB));
		PushInstallationResponse b = transactions().execute(status -> writer.register(
				ada.getId(), "fid-swap-b", "token-swap-2", PushPlatform.WEB));
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicReference<Throwable> failure = new AtomicReference<>();
		AtomicInteger unexpectedRollback = new AtomicInteger();

		Thread.ofVirtual().start(() -> {
			try {
				start.await();
				transactions().execute(status -> writer.register(
						ada.getId(), "fid-swap-a", "token-swap-2", PushPlatform.WEB));
			} catch (UnexpectedRollbackException ex) {
				unexpectedRollback.incrementAndGet();
				failure.compareAndSet(null, ex);
			} catch (Throwable ex) {
				failure.compareAndSet(null, ex);
			} finally {
				done.countDown();
			}
		});
		Thread.ofVirtual().start(() -> {
			try {
				start.await();
				transactions().execute(status -> writer.register(
						ada.getId(), "fid-swap-b", "token-swap-1", PushPlatform.WEB));
			} catch (UnexpectedRollbackException ex) {
				unexpectedRollback.incrementAndGet();
				failure.compareAndSet(null, ex);
			} catch (Throwable ex) {
				failure.compareAndSet(null, ex);
			} finally {
				done.countDown();
			}
		});

		start.countDown();
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).as("token swap failed").isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertEquals(2, installations.countByOwnerUser_Id(ada.getId()));
		FirebaseInstallation rowA = installations.findById(a.id()).orElseThrow();
		FirebaseInstallation rowB = installations.findById(b.id()).orElseThrow();
		assertThat(rowA.getFcmToken()).isIn("token-swap-1", "token-swap-2");
		assertThat(rowB.getFcmToken()).isIn("token-swap-1", "token-swap-2");
		assertThat(rowA.getFcmToken()).isNotEqualTo(rowB.getFcmToken());
	}

	@Test
	void twoUsersRacingSameActiveFidLeaveExactlyOneOwner() throws Exception {
		UserAccount ada = persistAccount("push-race-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("push-race-bob@fe.edu.vn", AccountRole.LECTURER);
		transactions().execute(status -> writer.register(
				ada.getId(), "fid-race", "token-race-ada", PushPlatform.WEB));
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(2);
		AtomicInteger success = new AtomicInteger();
		AtomicInteger conflict = new AtomicInteger();
		AtomicInteger unexpectedRollback = new AtomicInteger();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Runnable bobAttempt = () -> {
			try {
				start.await();
				transactions().execute(status -> writer.register(
						bob.getId(), "fid-race", "token-race-bob", PushPlatform.WEB));
				success.incrementAndGet();
			} catch (AcademicException ex) {
				if (ex.getCode() == AcademicErrorCode.PUSH_INSTALLATION_CONFLICT) {
					conflict.incrementAndGet();
					assertThat(ex.getMessage()).doesNotContain(ada.getId().toString());
				} else {
					failure.compareAndSet(null, ex);
				}
			} catch (UnexpectedRollbackException ex) {
				unexpectedRollback.incrementAndGet();
				failure.compareAndSet(null, ex);
			} catch (Throwable ex) {
				failure.compareAndSet(null, ex);
			} finally {
				done.countDown();
			}
		};
		Thread.ofVirtual().start(bobAttempt);
		Thread.ofVirtual().start(bobAttempt);

		start.countDown();
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		assertThat(failure.get()).isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertThat(success.get()).isZero();
		assertThat(conflict.get()).isEqualTo(2);
		assertEquals(1, installations.countByOwnerUser_Id(ada.getId()));
		assertEquals(0, installations.countByOwnerUser_Id(bob.getId()));
		FirebaseInstallation row = installations.findByFirebaseInstallationId("fid-race").orElseThrow();
		assertEquals(ada.getId(), row.getOwnerUser().getId());
		assertTrue(row.getActive());
		assertEquals("token-race-ada", row.getFcmToken());
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

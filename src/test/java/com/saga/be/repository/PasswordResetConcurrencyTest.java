package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.saga.be.auth.PasswordPolicy;
import com.saga.be.auth.PasswordResetService;
import com.saga.be.config.AuthProperties;
import com.saga.be.dto.mail.EmailOutboxRecord;
import com.saga.be.entity.account.PasswordResetToken;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EmailDeliveryStatus;
import com.saga.be.service.mail.EmailOutboxService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Real H2 + real {@link PlatformTransactionManager} + real {@link UserAccountRepository#findByIdForUpdate}
 * row lock. Proves concurrent forgot-password requests for the same user cannot both leave a valid
 * unused token behind.
 *
 * <p>Deliberately placed in {@code com.saga.be.repository} (not {@code com.saga.be.auth}): this
 * class's nested {@code @SpringBootConfiguration TxSlice} would otherwise be auto-detected by any
 * plain {@code @SpringBootTest} in the same package that does not declare {@code classes=} (e.g.
 * {@code AuthLoginCsrfTest}), hijacking their root application context.
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
class PasswordResetConcurrencyTest {

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
	private PasswordResetTokenRepository tokens;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private PasswordResetService service;
	private UserAccount account;

	@BeforeEach
	void setUp() {
		account = new UserAccount();
		account.setEmail("racer@fpt.edu.vn");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account = users.save(account);

		AuthProperties properties = new AuthProperties();
		EmailOutboxService emails = mock(EmailOutboxService.class);
		when(emails.enqueue(any())).thenReturn(sentRecord());
		service = new PasswordResetService(
				users,
				tokens,
				new Argon2PasswordEncoder(16, 32, 1, 19_456, 2),
				new PasswordPolicy(properties),
				emails,
				properties,
				transactionManager);
	}

	@Test
	void concurrentForgotPasswordLeavesExactlyOneValidUnusedToken() throws Exception {
		int threads = 12;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		for (int i = 0; i < threads; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					service.forgotPassword("racer@fpt.edu.vn");
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(15, TimeUnit.SECONDS)).isTrue();

		List<PasswordResetToken> all = tokens.findAll();
		assertThat(all).hasSize(threads);
		long stillValid = all.stream().filter(t -> t.getUsedAt() == null).count();
		assertThat(stillValid).isEqualTo(1);
		long invalidated = all.stream().filter(t -> t.getUsedAt() != null).count();
		assertThat(invalidated).isEqualTo(threads - 1);
	}

	private static EmailOutboxRecord sentRecord() {
		return new EmailOutboxRecord(
				UUID.randomUUID(),
				"racer@fpt.edu.vn",
				"PASSWORD_RESET",
				"password-reset",
				EmailDeliveryStatus.PENDING,
				0,
				LocalDateTime.now(),
				null,
				null,
				null,
				LocalDateTime.now(),
				LocalDateTime.now());
	}
}

package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.notification.PushInstallationResponse;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.PushPlatform;
import com.saga.be.entity.notification.FirebaseInstallation;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.notification.NotificationDeliveryRules;
import com.saga.be.service.notification.PushInstallationService;
import jakarta.persistence.EntityManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Disposable MySQL 8.0.40: V1→V19 seed, V20 utf8mb4_bin identity columns, then registration.
 * Opt-in: {@code saga.verify.mysql=true} and {@code saga.verify.mysql.url}.
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
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class FirebaseInstallationV20MysqlIT {

	private static final UUID LEGACY_USER = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");
	private static final UUID LEGACY_INSTALLATION = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000001");
	private static final UUID CASE_FID_ROW = UUID.fromString("cccccccc-0000-4000-8000-000000000001");

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
	private DataSource dataSource;
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

	private String fidCharsetBeforeV20;
	private String fidCollationBeforeV20;
	private boolean v19RejectedCaseVariantFid;

	private TransactionTemplate transactions() {
		return new TransactionTemplate(transactionManager);
	}

	@BeforeEach
	void migrateV1ThroughV20WithLegacyRow() throws Exception {
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.cleanDisabled(false)
				.load()
				.clean();
		Flyway.configure()
				.dataSource(dataSource)
				.locations("classpath:db/migration")
				.target("19")
				.load()
				.migrate();
		try (Connection connection = dataSource.getConnection()) {
			ColumnMeta fidBefore = column(connection, "firebase_installation_id");
			fidCharsetBeforeV20 = fidBefore.charset();
			fidCollationBeforeV20 = fidBefore.collation();
			try (PreparedStatement user = connection.prepareStatement(
					"""
					INSERT INTO user_account
					  (id, email, username, full_name, account_role, account_status, password_hash, created_at, updated_at)
					VALUES (?, ?, ?, ?, 'STUDENT', 'ACTIVE', 'not-a-secret-in-api', NOW(6), NOW(6))
					""")) {
				user.setString(1, LEGACY_USER.toString());
				user.setString(2, "legacy-fcm@fpt.edu.vn");
				user.setString(3, "legacyfcm");
				user.setString(4, "Legacy FCM");
				user.executeUpdate();
			}
			try (PreparedStatement install = connection.prepareStatement(
					"""
					INSERT INTO firebase_installation
					  (id, owner_user_id, firebase_installation_id, active, last_registered_at, revoked_at, version, created_at, updated_at)
					VALUES (?, ?, 'legacy-fid', 1, NOW(6), NULL, 0, NOW(6), NOW(6))
					""")) {
				install.setString(1, LEGACY_INSTALLATION.toString());
				install.setString(2, LEGACY_USER.toString());
				install.executeUpdate();
			}
			try (PreparedStatement caseVariant = connection.prepareStatement(
					"""
					INSERT INTO firebase_installation
					  (id, owner_user_id, firebase_installation_id, active, last_registered_at, revoked_at, version, created_at, updated_at)
					VALUES (?, ?, 'legacy-FID', 1, NOW(6), NULL, 0, NOW(6), NOW(6))
					""")) {
				caseVariant.setString(1, CASE_FID_ROW.toString());
				caseVariant.setString(2, LEGACY_USER.toString());
				try {
					caseVariant.executeUpdate();
					v19RejectedCaseVariantFid = false;
				} catch (SQLException ex) {
					v19RejectedCaseVariantFid = true;
				}
			}
		}
		Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
	}

	@Test
	void v20KeepsLegacyRowAndEnforcesCaseSensitiveIdentity() throws Exception {
		assertEquals("utf8mb4", fidCharsetBeforeV20);
		assertEquals("utf8mb4_0900_ai_ci", fidCollationBeforeV20);
		assertTrue(v19RejectedCaseVariantFid, "V1 FID unique should be case-insensitive on MySQL 8");

		try (Connection connection = dataSource.getConnection()) {
			ColumnMeta fid = column(connection, "firebase_installation_id");
			ColumnMeta token = column(connection, "fcm_token");
			assertEquals("utf8mb4", fid.charset());
			assertEquals("utf8mb4_bin", fid.collation());
			assertEquals("utf8mb4", token.charset());
			assertEquals("utf8mb4_bin", token.collation());
			assertEquals("varchar", token.dataType());
			assertEquals(512, token.maxLength());
			assertEquals("YES", token.nullable());
		}

		FirebaseInstallation legacy = installations.findById(LEGACY_INSTALLATION).orElseThrow();
		assertEquals("legacy-fid", legacy.getFirebaseInstallationId());
		assertThat(legacy.getFcmToken()).isNull();
		assertThat(legacy.getPlatform()).isNull();
		assertTrue(legacy.getActive());

		try (Connection connection = dataSource.getConnection()) {
			assertTrue(insertInstallation(connection, CASE_FID_ROW, LEGACY_USER, "legacy-FID", null));
			assertTrue(insertInstallation(
					connection,
					UUID.fromString("dddddddd-0000-4000-8000-000000000001"),
					LEGACY_USER,
					"legacy-Fid",
					"TokenCase"));
			assertTrue(insertInstallation(
					connection,
					UUID.fromString("eeeeeeee-0000-4000-8000-000000000001"),
					LEGACY_USER,
					"legacy-fId",
					"tokencase"));
			assertFalse(insertInstallation(
					connection,
					UUID.fromString("ffffffff-0000-4000-8000-000000000001"),
					LEGACY_USER,
					"legacy-other",
					"TokenCase"));
		}
		assertTrue(installations.findByFirebaseInstallationId("legacy-fid").isPresent());
		assertTrue(installations.findByFirebaseInstallationId("legacy-FID").isPresent());
		assertTrue(installations.findByFcmToken("TokenCase").isPresent());
		assertTrue(installations.findByFcmToken("tokencase").isPresent());
		assertThat(installations.findByFirebaseInstallationId("legacy-fid").orElseThrow().getId())
				.isNotEqualTo(installations.findByFirebaseInstallationId("legacy-FID").orElseThrow().getId());

		UserAccount ada = persistAccount("mysql-push-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("mysql-push-bob@fe.edu.vn", AccountRole.LECTURER);
		PushInstallationResponse created = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-fid-web", "mysql-token-1", PushPlatform.WEB));
		assertEquals("WEB", created.platform());
		assertTrue(created.active());
		FirebaseInstallation stored = installations.findById(created.id()).orElseThrow();
		assertEquals("mysql-token-1", stored.getFcmToken());

		PushInstallationResponse rotated = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-fid-web", "mysql-token-2", PushPlatform.WEB));
		assertEquals(created.id(), rotated.id());
		assertEquals(1, installations.countByOwnerUser_Id(ada.getId()));
		assertEquals("mysql-token-2", installations.findById(created.id()).orElseThrow().getFcmToken());

		AcademicException takeover = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.register(
						bob.getId(), "mysql-fid-web", "mysql-token-bob", PushPlatform.WEB)));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, takeover.getCode());
		assertEquals(HttpStatus.CONFLICT, takeover.getStatus());
		assertThat(takeover.getMessage()).doesNotContain(ada.getId().toString());
		assertEquals(ada.getId(), installations.findByFirebaseInstallationId("mysql-fid-web").orElseThrow().getOwnerUser().getId());

		AcademicException tokenConflict = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.register(
						bob.getId(), "mysql-fid-bob", "mysql-token-2", PushPlatform.WEB)));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, tokenConflict.getCode());
		assertThat(tokenConflict.getMessage()).doesNotContain("mysql-token-2");

		PushInstallationResponse caseA = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-Fid-Case", "mysql-Token-Case", PushPlatform.WEB));
		PushInstallationResponse caseB = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-fid-case", "mysql-token-case", PushPlatform.WEB));
		assertThat(caseA.id()).isNotEqualTo(caseB.id());

		AcademicException foreignRevoke = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.revoke(bob.getId(), created.id())));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_NOT_FOUND, foreignRevoke.getCode());

		transactions().execute(status -> writer.revoke(ada.getId(), created.id()));
		PushInstallationResponse reclaimed = transactions().execute(status -> writer.register(
				bob.getId(), "mysql-fid-web", "mysql-token-reclaim", PushPlatform.WEB));
		assertEquals(created.id(), reclaimed.id());
		FirebaseInstallation bobRow = installations.findByFirebaseInstallationId("mysql-fid-web").orElseThrow();
		assertEquals(bob.getId(), bobRow.getOwnerUser().getId());
		assertFalse(NotificationDeliveryRules.maySend(ada.getId(), bobRow));
		assertTrue(NotificationDeliveryRules.maySend(bob.getId(), bobRow));

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
					PushInstallationResponse row = transactions().execute(status -> writer.register(
							ada.getId(), "mysql-fid-conc", "mysql-conc-" + n, PushPlatform.WEB));
					ids.add(row.id());
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
		assertThat(failure.get()).isNull();
		assertThat(unexpectedRollback.get()).isZero();
		assertThat(ids.stream().distinct().count()).isEqualTo(1);

		PushInstallationResponse swapA = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-fid-swap-a", "mysql-swap-1", PushPlatform.WEB));
		PushInstallationResponse swapB = transactions().execute(status -> writer.register(
				ada.getId(), "mysql-fid-swap-b", "mysql-swap-2", PushPlatform.WEB));
		CountDownLatch swapStart = new CountDownLatch(1);
		CountDownLatch swapDone = new CountDownLatch(2);
		AtomicReference<Throwable> swapFailure = new AtomicReference<>();
		AtomicInteger swapRollback = new AtomicInteger();
		Thread.ofVirtual().start(() -> {
			try {
				swapStart.await();
				transactions().execute(status -> writer.register(
						ada.getId(), "mysql-fid-swap-a", "mysql-swap-2", PushPlatform.WEB));
			} catch (UnexpectedRollbackException ex) {
				swapRollback.incrementAndGet();
				swapFailure.compareAndSet(null, ex);
			} catch (Throwable ex) {
				swapFailure.compareAndSet(null, ex);
			} finally {
				swapDone.countDown();
			}
		});
		Thread.ofVirtual().start(() -> {
			try {
				swapStart.await();
				transactions().execute(status -> writer.register(
						ada.getId(), "mysql-fid-swap-b", "mysql-swap-1", PushPlatform.WEB));
			} catch (UnexpectedRollbackException ex) {
				swapRollback.incrementAndGet();
				swapFailure.compareAndSet(null, ex);
			} catch (Throwable ex) {
				swapFailure.compareAndSet(null, ex);
			} finally {
				swapDone.countDown();
			}
		});
		swapStart.countDown();
		assertThat(swapDone.await(30, TimeUnit.SECONDS)).isTrue();
		assertThat(swapFailure.get()).isNull();
		assertThat(swapRollback.get()).isZero();
		assertThat(installations.findById(swapA.id()).orElseThrow().getFcmToken())
				.isNotEqualTo(installations.findById(swapB.id()).orElseThrow().getFcmToken());

		assertNotNull(fidCollationBeforeV20);
		transactions().executeWithoutResult(status -> {
			installations.deleteByOwnerUser_Id(ada.getId());
			installations.deleteByOwnerUser_Id(bob.getId());
			installations.deleteByOwnerUser_Id(LEGACY_USER);
			users.deleteById(ada.getId());
			users.deleteById(bob.getId());
			users.deleteById(LEGACY_USER);
		});
	}

	private boolean insertInstallation(
			Connection connection, UUID id, UUID ownerId, String fid, String token) throws SQLException {
		try (PreparedStatement install = connection.prepareStatement(
				"""
				INSERT INTO firebase_installation
				  (id, owner_user_id, firebase_installation_id, fcm_token, active, last_registered_at, revoked_at, version, created_at, updated_at)
				VALUES (?, ?, ?, ?, 1, NOW(6), NULL, 0, NOW(6), NOW(6))
				""")) {
			install.setString(1, id.toString());
			install.setString(2, ownerId.toString());
			install.setString(3, fid);
			install.setString(4, token);
			try {
				install.executeUpdate();
				return true;
			} catch (SQLException ex) {
				return false;
			}
		}
	}

	private static ColumnMeta column(Connection connection, String columnName) throws SQLException {
		try (PreparedStatement columns = connection.prepareStatement(
				"""
				SELECT CHARACTER_SET_NAME, COLLATION_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, IS_NULLABLE
				FROM information_schema.COLUMNS
				WHERE TABLE_SCHEMA = DATABASE()
				  AND TABLE_NAME = 'firebase_installation'
				  AND COLUMN_NAME = ?
				""")) {
			columns.setString(1, columnName);
			try (ResultSet rs = columns.executeQuery()) {
				assertTrue(rs.next());
				return new ColumnMeta(
						rs.getString("CHARACTER_SET_NAME"),
						rs.getString("COLLATION_NAME"),
						rs.getString("DATA_TYPE"),
						rs.getObject("CHARACTER_MAXIMUM_LENGTH") == null
								? null
								: rs.getInt("CHARACTER_MAXIMUM_LENGTH"),
						rs.getString("IS_NULLABLE"));
			}
		}
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

	private record ColumnMeta(
			String charset, String collation, String dataType, Integer maxLength, String nullable) {}
}

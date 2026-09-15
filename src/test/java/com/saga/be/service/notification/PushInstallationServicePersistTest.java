package com.saga.be.service.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import com.saga.be.repository.FirebaseInstallationRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
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
class PushInstallationServicePersistTest {

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
	void sameOwnerRotatesActiveForeignFidConflictsAndRevokedFidReclaims() {
		UserAccount ada = persistAccount("push-ada@fpt.edu.vn", AccountRole.STUDENT);
		UserAccount bob = persistAccount("push-bob@fe.edu.vn", AccountRole.LECTURER);

		PushInstallationResponse created = transactions().execute(status -> writer.register(
				ada.getId(), "fid-browser-1", "token-aaa", PushPlatform.WEB));
		assertEquals("WEB", created.platform());
		assertTrue(created.active());
		FirebaseInstallation stored = installations.findById(created.id()).orElseThrow();
		assertEquals("token-aaa", stored.getFcmToken());
		assertEquals("fid-browser-1", stored.getFirebaseInstallationId());

		PushInstallationResponse rotated = transactions().execute(status -> writer.register(
				ada.getId(), "fid-browser-1", "token-bbb", PushPlatform.WEB));
		assertEquals(created.id(), rotated.id());
		assertEquals(1, installations.countByOwnerUser_Id(ada.getId()));
		assertEquals("token-bbb", installations.findById(created.id()).orElseThrow().getFcmToken());
		assertTrue(installations.findById(created.id()).orElseThrow().getActive());

		AcademicException takeover = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.register(
						bob.getId(), "fid-browser-1", "token-ccc", PushPlatform.WEB)));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, takeover.getCode());
		assertEquals(HttpStatus.CONFLICT, takeover.getStatus());
		assertThat(takeover.getMessage()).doesNotContain(ada.getId().toString());
		assertThat(takeover.getMessage()).doesNotContain(ada.getEmail());
		assertThat(takeover.getMessage()).doesNotContain("fid-browser-1");
		assertThat(takeover.getMessage()).doesNotContain("token-bbb");
		FirebaseInstallation stillAda = installations.findByFirebaseInstallationId("fid-browser-1").orElseThrow();
		assertEquals(ada.getId(), stillAda.getOwnerUser().getId());
		assertEquals("token-bbb", stillAda.getFcmToken());
		assertTrue(stillAda.getActive());
		assertEquals(0, installations.countByOwnerUser_Id(bob.getId()));

		AcademicException tokenTakeover = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.register(
						bob.getId(), "fid-bob-other", "token-bbb", PushPlatform.WEB)));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_CONFLICT, tokenTakeover.getCode());
		assertThat(tokenTakeover.getMessage()).doesNotContain(ada.getId().toString());
		assertEquals("token-bbb", installations.findById(created.id()).orElseThrow().getFcmToken());

		PushInstallationResponse otherFid = transactions().execute(status -> writer.register(
				ada.getId(), "fid-browser-2", "token-bbb", PushPlatform.WEB));
		assertThat(otherFid.id()).isNotEqualTo(created.id());
		FirebaseInstallation previous = installations.findByFirebaseInstallationId("fid-browser-1").orElseThrow();
		assertTrue(previous.getActive());
		assertNull(previous.getFcmToken());
		assertEquals(ada.getId(), previous.getOwnerUser().getId());
		assertEquals("token-bbb", installations.findById(otherFid.id()).orElseThrow().getFcmToken());

		AcademicException foreign = assertThrows(
				AcademicException.class,
				() -> transactions().execute(status -> writer.revoke(bob.getId(), created.id())));
		assertEquals(AcademicErrorCode.PUSH_INSTALLATION_NOT_FOUND, foreign.getCode());
		assertEquals(HttpStatus.NOT_FOUND, foreign.getStatus());
		assertThat(foreign.getMessage()).doesNotContain(ada.getId().toString());

		PushInstallationResponse revoked = transactions().execute(status -> writer.revoke(ada.getId(), otherFid.id()));
		assertFalse(revoked.active());
		PushInstallationResponse again = transactions().execute(status -> writer.revoke(ada.getId(), otherFid.id()));
		assertFalse(again.active());
		assertEquals(revoked.id(), again.id());

		PushInstallationResponse reclaimed = transactions().execute(status -> writer.register(
				bob.getId(), "fid-browser-2", "token-ddd", PushPlatform.WEB));
		assertEquals(otherFid.id(), reclaimed.id());
		assertTrue(reclaimed.active());
		FirebaseInstallation bobRow = installations.findByFirebaseInstallationId("fid-browser-2").orElseThrow();
		assertEquals(bob.getId(), bobRow.getOwnerUser().getId());
		assertEquals("token-ddd", bobRow.getFcmToken());
		assertNull(bobRow.getRevokedAt());
		assertFalse(NotificationDeliveryRules.maySend(ada.getId(), bobRow));
		assertTrue(NotificationDeliveryRules.maySend(bob.getId(), bobRow));

		PushInstallationResponse revivedOwn = transactions().execute(status -> writer.register(
				ada.getId(), "fid-browser-1", "token-eee", PushPlatform.WEB));
		assertEquals(created.id(), revivedOwn.id());
		assertTrue(revivedOwn.active());
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

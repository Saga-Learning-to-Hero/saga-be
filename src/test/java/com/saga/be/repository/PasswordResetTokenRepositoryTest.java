package com.saga.be.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.entity.account.PasswordResetToken;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/** Real H2 database: entity mapping, unique constraint, and the bulk-invalidate update. */
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
@Transactional
class PasswordResetTokenRepositoryTest {

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
	private EntityManager entityManager;

	private UserAccount account;

	@BeforeEach
	void setUp() {
		account = new UserAccount();
		account.setEmail("reset-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		account = users.save(account);
	}

	@Test
	void savedTokenIsFoundByHashWithUserAssociationIntact() {
		PasswordResetToken token = newToken("hash-1");
		tokens.save(token);
		entityManager.flush();
		entityManager.clear();

		PasswordResetToken found = tokens.findByTokenHash("hash-1").orElseThrow();
		assertEquals(account.getId(), found.getUser().getId());
		assertNull(found.getUsedAt());
		assertNotNull(found.getExpiresAt());
	}

	@Test
	void tokenHashMustBeUniqueAcrossTheTable() {
		tokens.saveAndFlush(newToken("dup-hash"));
		entityManager.clear();
		PasswordResetToken duplicate = newToken("dup-hash");
		// saveAndFlush (a Spring Data repository method) gets Spring's persistence exception
		// translation; a direct EntityManager.flush() would surface the raw Hibernate exception.
		assertThrows(DataIntegrityViolationException.class, () -> tokens.saveAndFlush(duplicate));
	}

	@Test
	void invalidateUnusedForUserMarksOnlyThatUsersUnusedTokensAndIsIdempotent() {
		UserAccount other = new UserAccount();
		other.setEmail("other-" + UUID.randomUUID() + "@fpt.edu.vn");
		other.setAccountRole(AccountRole.STUDENT);
		other.setAccountStatus(AccountStatus.ACTIVE);
		other = users.save(other);

		PasswordResetToken mine1 = tokens.save(newToken("mine-1"));
		PasswordResetToken mine2 = tokens.save(newToken("mine-2"));
		PasswordResetToken theirs = new PasswordResetToken();
		theirs.setUser(other);
		theirs.setTokenHash("theirs-1");
		theirs.setExpiresAt(LocalDateTime.now().plusMinutes(30));
		tokens.save(theirs);
		entityManager.flush();
		entityManager.clear();

		int invalidated = tokens.invalidateUnusedForUser(account.getId(), LocalDateTime.now());
		assertEquals(2, invalidated);

		assertNotNull(tokens.findByTokenHash("mine-1").orElseThrow().getUsedAt());
		assertNotNull(tokens.findByTokenHash("mine-2").orElseThrow().getUsedAt());
		assertNull(tokens.findByTokenHash("theirs-1").orElseThrow().getUsedAt());

		// Re-running against already-used tokens must not error and must affect nothing further.
		int secondPass = tokens.invalidateUnusedForUser(account.getId(), LocalDateTime.now());
		assertEquals(0, secondPass);
	}

	@Test
	void lockingLookupReturnsSameRowAsPlainLookup() {
		PasswordResetToken token = tokens.save(newToken("lock-hash"));
		entityManager.flush();
		entityManager.clear();

		PasswordResetToken locked = tokens.findByTokenHashForUpdate("lock-hash").orElseThrow();
		assertEquals(token.getId(), locked.getId());
		assertFalse(locked.getTokenHash().isBlank());
	}

	private PasswordResetToken newToken(String hash) {
		PasswordResetToken token = new PasswordResetToken();
		token.setUser(account);
		token.setTokenHash(hash);
		token.setExpiresAt(LocalDateTime.now().plusMinutes(30));
		return token;
	}
}

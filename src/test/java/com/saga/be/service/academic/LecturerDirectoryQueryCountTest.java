package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@ActiveProfiles("tx-it")
@TestPropertySource(
		properties = {
			"spring.flyway.enabled=false",
			"spring.jpa.hibernate.ddl-auto=create-drop",
			"spring.jpa.open-in-view=false",
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"saga.auth.bootstrap-admin.enabled=false"
		})
@Transactional
class LecturerDirectoryQueryCountTest {

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
	private LecturerProfileRepository lecturers;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private EntityManager entityManager;

	@Test
	void directoryQueryCountDoesNotGrowWithLecturerCount() {
		AdminLecturerService service = new AdminLecturerService(lecturers);
		persistLecturers(3);
		entityManager.flush();
		entityManager.clear();
		Statistics stats = statistics();
		stats.clear();
		List<LecturerDirectoryResponse> first = service.list(null, null);
		assertEquals(3, first.size());
		first.forEach(row -> assertTrue(row.email() != null && !row.email().isBlank()));
		long queriesForThree = stats.getPrepareStatementCount();
		assertEquals(1, queriesForThree);

		persistLecturers(5);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		List<LecturerDirectoryResponse> second = service.list(null, null);
		assertEquals(8, second.size());
		second.forEach(row -> assertTrue(row.lecturerProfileId() != null && !row.lecturerProfileId().equals(row.userId())));
		assertEquals(queriesForThree, stats.getPrepareStatementCount());
	}

	private void persistLecturers(int count) {
		for (int i = 0; i < count; i++) {
			UserAccount account = new UserAccount();
			account.setEmail("lecturer-" + System.nanoTime() + "-" + i + "@fe.edu.vn");
			account.setFullName("Lecturer " + i);
			account.setAccountRole(AccountRole.LECTURER);
			account.setAccountStatus(AccountStatus.ACTIVE);
			users.save(account);
			LecturerProfile profile = new LecturerProfile();
			profile.setUserAccount(account);
			lecturers.save(profile);
		}
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

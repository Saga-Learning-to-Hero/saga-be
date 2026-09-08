package com.saga.be.service.sync;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.enums.SyncJobStatus;
import com.saga.be.entity.enums.SyncJobType;
import com.saga.be.repository.SyncJobLogRepository;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
class SyncJobClaimServiceConcurrencyTest {

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
	private SyncJobLogRepository syncJobs;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private SyncJobClaimService claims;
	private UUID projectId;

	@BeforeEach
	void setUp() {
		claims = new SyncJobClaimService(syncJobs, new com.saga.be.config.IntegrationProperties(), transactionManager);
		projectId = UUID.randomUUID();
	}

	@Test
	void concurrentTryClaim_onlyOneRunningJob() throws Exception {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger winners = new AtomicInteger();
		for (int i = 0; i < threads; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					if (claims.tryClaim("JIRA", projectId, SyncJobType.INITIAL).isPresent()) {
						winners.incrementAndGet();
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(winners.get()).isEqualTo(1);
		assertThat(syncJobs.existsByTargetSystemAndTargetIdAndStatus("JIRA", projectId, SyncJobStatus.RUNNING))
				.isTrue();
	}

	@Test
	void concurrentTryReserveEnqueue_onlyOneSucceeds() throws Exception {
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		AtomicInteger winners = new AtomicInteger();
		for (int i = 0; i < threads; i++) {
			Thread.ofVirtual().start(() -> {
				try {
					start.await();
					if (claims.tryReserveEnqueue("GITHUB", projectId)) {
						winners.incrementAndGet();
					}
				} catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			});
		}
		start.countDown();
		assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
		assertThat(winners.get()).isEqualTo(1);
	}
}

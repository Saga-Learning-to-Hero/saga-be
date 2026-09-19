package com.saga.be.service.admin.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.config.AdminDashboardProperties;
import com.saga.be.dto.admin.dashboard.AdminDashboardAvailableSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardCachedPayload;
import com.saga.be.dto.admin.dashboard.AdminDashboardIntegrationPulseResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardKpisResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSelectedSemesterResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardSummaryResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardUnconnectedTeamResponse;
import com.saga.be.dto.admin.dashboard.AdminDashboardMissingService;
import com.saga.be.dto.admin.dashboard.SemesterPeriodStatus;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.enums.IntegrationProvider;
import com.saga.be.entity.enums.WebhookReceiptStatus;
import com.saga.be.entity.integration.WebhookReceipt;
import com.saga.be.integration.webhook.WebhookReceiptService;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.WebhookReceiptRepository;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
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
import org.springframework.transaction.support.TransactionTemplate;

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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminDashboardPulseTest {

	private static final Instant NOW = Instant.parse("2026-09-19T04:00:00Z");
	/** Persistence wall-clock fixture — not {@code saga.dashboard.zone}. */
	private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
	private static final LocalDateTime NOW_LDT = LocalDateTime.of(2026, 9, 19, 4, 0);
	private static final LocalDateTime CUTOFF_24H = NOW_LDT.minusHours(24);
	private static final LocalDateTime CUTOFF_7D = NOW_LDT.minusDays(7);

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
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private WebhookReceiptRepository receipts;
	@Autowired
	private SemesterRepository semesters;

	private TransactionTemplate tx;
	private AdminDashboardPulseQueryService queries;
	private WebhookReceiptService ingest;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		queries = new AdminDashboardPulseQueryService(receipts, CLOCK);
		ingest = new WebhookReceiptService(new WebhookReceiptService.Store() {
			@Override
			public java.util.Optional<WebhookReceipt> find(IntegrationProvider provider, String deliveryId) {
				return receipts.findByProviderAndDeliveryId(provider, deliveryId);
			}

			@Override
			public WebhookReceipt save(WebhookReceipt receipt) {
				return receipts.save(receipt);
			}
		});
		tx.executeWithoutResult(status -> {
			receipts.deleteAll();
			entityManager.flush();
		});
	}

	@Test
	void githubWindowsCountUniqueRowsAndKeepGlobalLastEvent() {
		LocalDateTime latest = LocalDateTime.of(2026, 9, 19, 3, 0);
		persist(IntegrationProvider.GITHUB, "g-1", latest, WebhookReceiptStatus.PROCESSED);
		persist(IntegrationProvider.GITHUB, "g-2", LocalDateTime.of(2026, 9, 18, 10, 0), WebhookReceiptStatus.RECEIVED);
		persist(IntegrationProvider.GITHUB, "g-3", LocalDateTime.of(2026, 9, 15, 0, 0), WebhookReceiptStatus.FAILED);
		persist(IntegrationProvider.GITHUB, "g-old", LocalDateTime.of(2026, 9, 10, 0, 0), WebhookReceiptStatus.PROCESSED);

		List<AdminDashboardIntegrationPulseResponse> pulse = queries.compute();
		assertThat(pulse).hasSize(2);
		assertThat(pulse.get(0).service()).isEqualTo(IntegrationProvider.GITHUB);
		assertThat(pulse.get(0).uniqueEventsReceived24h()).isEqualTo(2L);
		assertThat(pulse.get(0).uniqueEventsReceived7d()).isEqualTo(3L);
		assertThat(pulse.get(0).lastUniqueEventAt()).isEqualTo(latest);
		assertThat(pulse.get(1).service()).isEqualTo(IntegrationProvider.JIRA);
		assertThat(pulse.get(1).uniqueEventsReceived24h()).isZero();
		assertThat(pulse.get(1).uniqueEventsReceived7d()).isZero();
		assertThat(pulse.get(1).lastUniqueEventAt()).isNull();
	}

	@Test
	void jiraWithZeroRowsReturnsZerosAndNullLastEvent() {
		List<AdminDashboardIntegrationPulseResponse> pulse = queries.compute();
		assertThat(pulse.get(1).service()).isEqualTo(IntegrationProvider.JIRA);
		assertThat(pulse.get(1).uniqueEventsReceived24h()).isZero();
		assertThat(pulse.get(1).uniqueEventsReceived7d()).isZero();
		assertThat(pulse.get(1).lastUniqueEventAt()).isNull();
	}

	@Test
	void sameProviderDeliveryDuplicateDoesNotIncreaseCounts() {
		tx.executeWithoutResult(status -> {
			ingest.ingest(
					IntegrationProvider.GITHUB,
					"dup-1",
					"push",
					null,
					"{}",
					null,
					NOW_LDT);
			ingest.ingest(
					IntegrationProvider.GITHUB,
					"dup-1",
					"push",
					null,
					"{}",
					null,
					NOW_LDT);
			WebhookReceipt row = receipts.findByProviderAndDeliveryId(IntegrationProvider.GITHUB, "dup-1").orElseThrow();
			stampCreatedAt(row.getId(), NOW_LDT.minusHours(1));
			entityManager.flush();
		});
		assertThat(receipts.count()).isEqualTo(1L);
		AdminDashboardIntegrationPulseResponse github = queries.compute().getFirst();
		assertThat(github.uniqueEventsReceived24h()).isEqualTo(1L);
		assertThat(github.uniqueEventsReceived7d()).isEqualTo(1L);
	}

	@Test
	void processedThenDuplicateKeepsReceivedCounts() {
		assertStatusFlipDoesNotChangeCounts(WebhookReceiptStatus.PROCESSED);
	}

	@Test
	void failedThenDuplicateKeepsReceivedCounts() {
		assertStatusFlipDoesNotChangeCounts(WebhookReceiptStatus.FAILED);
	}

	@Test
	void pulseIsIdenticalAcrossSemestersBecauseItIsNotSemesterScoped() {
		persist(IntegrationProvider.GITHUB, "shared", NOW_LDT.minusHours(2), WebhookReceiptStatus.RECEIVED);
		Semester a = persistSemester("FA26A");
		Semester b = persistSemester("FA26B");
		List<AdminDashboardIntegrationPulseResponse> first = queries.compute();
		List<AdminDashboardIntegrationPulseResponse> second = queries.compute();
		assertThat(first).isEqualTo(second);

		AdminDashboardServiceCacheTest.MemoryCache summaryCache = new AdminDashboardServiceCacheTest.MemoryCache();
		summaryCache.values.put(a.getId(), cached(a, 1));
		summaryCache.values.put(b.getId(), cached(b, 9));
		AdminDashboardPulseCacheTest.MemoryPulseCache pulseCache = new AdminDashboardPulseCacheTest.MemoryPulseCache();
		AdminDashboardPulseService pulseService = new AdminDashboardPulseService(
				queries,
				pulseCache,
				transactionManager,
				CLOCK,
				duration -> {},
				Duration.ZERO,
				Duration.ZERO,
				() -> "p");
		AdminDashboardService service = new AdminDashboardService(
				semesters,
				null,
				null,
				summaryCache,
				pulseService,
				transactionManager,
				CLOCK,
				duration -> {},
				Duration.ZERO,
				Duration.ZERO,
				() -> "s");
		AdminDashboardSummaryResponse forA = service.summary(a.getId(), false);
		AdminDashboardSummaryResponse forB = service.summary(b.getId(), false);
		assertThat(forA.kpis().totalStudents()).isEqualTo(1L);
		assertThat(forB.kpis().totalStudents()).isEqualTo(9L);
		assertThat(forA.integrationPulse()).isEqualTo(forB.integrationPulse());
		assertThat(forA.integrationPulse().getFirst().uniqueEventsReceived24h()).isEqualTo(1L);
	}

	@Test
	void twentyFourHourBoundaryIsInclusiveOnCutoff() {
		persist(IntegrationProvider.GITHUB, "eq-24", CUTOFF_24H, WebhookReceiptStatus.RECEIVED);
		persist(
				IntegrationProvider.GITHUB,
				"before-24",
				CUTOFF_24H.minusSeconds(1),
				WebhookReceiptStatus.RECEIVED);
		AdminDashboardIntegrationPulseResponse github = queries.compute().getFirst();
		assertThat(github.uniqueEventsReceived24h()).isEqualTo(1L);
		assertThat(github.uniqueEventsReceived7d()).isEqualTo(2L);
	}

	@Test
	void sevenDayBoundaryIsInclusiveOnCutoff() {
		persist(IntegrationProvider.JIRA, "eq-7d", CUTOFF_7D, WebhookReceiptStatus.RECEIVED);
		persist(
				IntegrationProvider.JIRA,
				"before-7d",
				CUTOFF_7D.minusSeconds(1),
				WebhookReceiptStatus.RECEIVED);
		AdminDashboardIntegrationPulseResponse jira = queries.compute().get(1);
		assertThat(jira.uniqueEventsReceived24h()).isZero();
		assertThat(jira.uniqueEventsReceived7d()).isEqualTo(1L);
		assertThat(jira.lastUniqueEventAt()).isEqualTo(CUTOFF_7D);
	}

	@Test
	void academicDashboardZoneDoesNotShiftPulseMembership() {
		Instant instant = Instant.parse("2026-09-19T04:00:00Z");
		Clock persistence = Clock.fixed(instant, ZoneId.of("America/New_York"));
		Clock academicIct = Clock.fixed(instant, ZoneId.of("Asia/Ho_Chi_Minh"));
		LocalDateTime persistenceNow = LocalDateTime.now(persistence);
		persist(
				IntegrationProvider.GITHUB,
				"p-20h",
				persistenceNow.minusHours(20),
				WebhookReceiptStatus.RECEIVED);
		persist(
				IntegrationProvider.GITHUB,
				"p-26h",
				persistenceNow.minusHours(26),
				WebhookReceiptStatus.RECEIVED);

		AdminDashboardProperties utcProps = new AdminDashboardProperties();
		utcProps.setZone(ZoneOffset.UTC);
		AdminDashboardProperties ictProps = new AdminDashboardProperties();
		ictProps.setZone(ZoneId.of("Asia/Ho_Chi_Minh"));
		assertThat(LocalDateTime.now(utcProps.clock())).isNotEqualTo(LocalDateTime.now(ictProps.clock()));
		assertThat(LocalDateTime.now(persistence)).isNotEqualTo(LocalDateTime.now(academicIct));

		AdminDashboardPulseQueryService pulseQueries = new AdminDashboardPulseQueryService(receipts, persistence);
		List<AdminDashboardIntegrationPulseResponse> first = pulseQueries.compute();
		List<AdminDashboardIntegrationPulseResponse> second = pulseQueries.compute();
		assertThat(first).isEqualTo(second);
		assertThat(first.getFirst().uniqueEventsReceived24h()).isEqualTo(1L);
		assertThat(first.getFirst().uniqueEventsReceived7d()).isEqualTo(2L);
		assertThat(first.getFirst().lastUniqueEventAt()).isEqualTo(persistenceNow.minusHours(20));

		List<AdminDashboardIntegrationPulseResponse> academicMisuse =
				new AdminDashboardPulseQueryService(receipts, academicIct).compute();
		assertThat(academicMisuse.getFirst().uniqueEventsReceived24h())
				.as("academic ICT cutoffs would drop the 20h-old JVM-local row")
				.isZero();

		Instant nearIctMidnight = Instant.parse("2026-09-18T20:00:00Z");
		Clock academicUtcNearMidnight = Clock.fixed(nearIctMidnight, ZoneOffset.UTC);
		Clock academicIctNearMidnight = Clock.fixed(nearIctMidnight, ZoneId.of("Asia/Ho_Chi_Minh"));
		UUID teamId = UUID.fromString("55555555-5555-4555-8555-555555555555");
		AdminDashboardCachedPayload cached = new AdminDashboardCachedPayload(
				"g1",
				nearIctMidnight,
				new AdminDashboardSelectedSemesterResponse(
						UUID.fromString("11111111-1111-4111-8111-111111111111"),
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						3,
						true),
				List.of(new AdminDashboardAvailableSemesterResponse(
						UUID.fromString("11111111-1111-4111-8111-111111111111"),
						"FA26",
						"Fall",
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						true,
						SemesterPeriodStatus.IN_PROGRESS)),
				new AdminDashboardKpisResponse(0, null, null, 0, 0, 0, null, 0, 0, null),
				List.of(),
				List.of(new AdminDashboardUnconnectedTeamResponse(
						teamId,
						1,
						"T1",
						"SWP",
						null,
						null,
						AdminDashboardMissingService.PROJECT,
						LocalDateTime.of(2026, 9, 1, 0, 0),
						0)));
		assertThat(AdminDashboardTemporalView.decorate(cached, academicUtcNearMidnight)
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(17);
		assertThat(AdminDashboardTemporalView.decorate(cached, academicIctNearMidnight)
						.unconnectedTeamsAlert()
						.getFirst()
						.daysSinceCreated())
				.isEqualTo(18);
	}

	@Test
	void hibernateCreatedAtUsesJvmLocalDateTimeNow() {
		LocalDateTime before = LocalDateTime.now();
		WebhookReceipt row = persistUnstamped(IntegrationProvider.JIRA, "jvm-now");
		LocalDateTime after = LocalDateTime.now();
		assertThat(row.getCreatedAt()).isBetween(before.minusSeconds(1), after.plusSeconds(1));
	}

	@Test
	void pulseComputeUsesOneGroupedStatementRegardlessOfRowCount() {
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		queries.compute();
		long empty = stats.getPrepareStatementCount();
		assertThat(empty).isEqualTo(1L);

		for (int i = 0; i < 20; i++) {
			persist(
					IntegrationProvider.GITHUB,
					"bulk-" + i,
					NOW_LDT.minusHours(i % 10),
					WebhookReceiptStatus.RECEIVED);
		}
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		List<AdminDashboardIntegrationPulseResponse> pulse = queries.compute();
		assertThat(pulse.getFirst().uniqueEventsReceived7d()).isEqualTo(20L);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(empty);
	}

	private void assertStatusFlipDoesNotChangeCounts(WebhookReceiptStatus initial) {
		WebhookReceipt row = persist(IntegrationProvider.GITHUB, "flip-" + initial, NOW_LDT.minusHours(3), initial);
		tx.executeWithoutResult(status -> {
			WebhookReceipt loaded = receipts.findById(row.getId()).orElseThrow();
			ingest.ingest(
					loaded.getProvider(),
					loaded.getDeliveryId(),
					loaded.getEventType(),
					null,
					"{}",
					null,
					NOW_LDT);
			entityManager.flush();
		});
		WebhookReceipt after = receipts.findById(row.getId()).orElseThrow();
		assertThat(after.getReceiptStatus()).isEqualTo(WebhookReceiptStatus.DUPLICATE);
		assertThat(receipts.count()).isEqualTo(1L);
		AdminDashboardIntegrationPulseResponse github = queries.compute().getFirst();
		assertThat(github.uniqueEventsReceived24h()).isEqualTo(1L);
		assertThat(github.uniqueEventsReceived7d()).isEqualTo(1L);
		assertThat(github.lastUniqueEventAt()).isEqualTo(NOW_LDT.minusHours(3));
	}

	private WebhookReceipt persistUnstamped(IntegrationProvider provider, String deliveryId) {
		return tx.execute(statusTx -> {
			WebhookReceipt receipt = new WebhookReceipt();
			receipt.setProvider(provider);
			receipt.setDeliveryId(deliveryId);
			receipt.setEventType("push");
			receipt.setReceiptStatus(WebhookReceiptStatus.RECEIVED);
			receipt.setAttemptCount(1);
			receipt.setVersion(0L);
			receipt = receipts.save(receipt);
			entityManager.flush();
			entityManager.refresh(receipt);
			return receipt;
		});
	}

	private WebhookReceipt persist(
			IntegrationProvider provider,
			String deliveryId,
			LocalDateTime createdAt,
			WebhookReceiptStatus status) {
		return tx.execute(statusTx -> {
			WebhookReceipt receipt = new WebhookReceipt();
			receipt.setProvider(provider);
			receipt.setDeliveryId(deliveryId);
			receipt.setEventType("push");
			receipt.setReceiptStatus(status);
			receipt.setAttemptCount(1);
			receipt.setVersion(0L);
			receipt.setCreatedAt(createdAt);
			receipt.setUpdatedAt(createdAt);
			receipt = receipts.save(receipt);
			entityManager.flush();
			stampCreatedAt(receipt.getId(), createdAt);
			entityManager.clear();
			return receipts.findById(receipt.getId()).orElseThrow();
		});
	}

	private void stampCreatedAt(UUID id, LocalDateTime createdAt) {
		entityManager
				.createNativeQuery("update webhook_receipt set created_at = :ts where id = :id")
				.setParameter("ts", createdAt)
				.setParameter("id", id.toString())
				.executeUpdate();
	}

	private Semester persistSemester(String code) {
		return tx.execute(status -> {
			Semester semester = new Semester();
			semester.setCode(code);
			semester.setName(code);
			semester.setStartDate(LocalDateTime.of(2026, 9, 1, 0, 0));
			semester.setEndDate(LocalDateTime.of(2026, 12, 15, 0, 0));
			return semesters.save(semester);
		});
	}

	private static AdminDashboardCachedPayload cached(Semester semester, long students) {
		return new AdminDashboardCachedPayload(
				"g-" + semester.getCode(),
				NOW,
				new AdminDashboardSelectedSemesterResponse(
						semester.getId(),
						semester.getCode(),
						semester.getName(),
						LocalDate.of(2026, 9, 1),
						LocalDate.of(2026, 12, 15),
						16,
						3,
						true),
				List.of(),
				new AdminDashboardKpisResponse(students, null, null, 0, 0, 0, null, 0, 0, null),
				List.of(),
				List.of());
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

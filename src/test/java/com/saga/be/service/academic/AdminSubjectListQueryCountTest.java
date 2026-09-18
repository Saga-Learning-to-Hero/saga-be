package com.saga.be.service.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.academic.SubjectPageResponse;
import com.saga.be.dto.academic.SubjectResponse;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
import com.saga.be.repository.SyllabusDeliverableLearningOutcomeRepository;
import com.saga.be.repository.SyllabusExpectedActivityRepository;
import com.saga.be.repository.SyllabusExpectedDeliverableRepository;
import com.saga.be.repository.SyllabusLearningOutcomeRepository;
import com.saga.be.repository.SyllabusLearningUnitOutcomeRepository;
import com.saga.be.repository.SyllabusLearningUnitRepository;
import com.saga.be.repository.SyllabusPhaseLearningOutcomeRepository;
import com.saga.be.repository.SyllabusPhaseRepository;
import com.saga.be.service.audit.AuditService;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
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
@Import(AdminSubjectListQueryCountTest.CatalogBeans.class)
class AdminSubjectListQueryCountTest {

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

	@TestConfiguration
	static class CatalogBeans {
		@Bean
		JpaAcademicCatalogStore jpaAcademicCatalogStore(
				SubjectRepository subjects,
				SubjectSyllabusVersionRepository syllabi,
				SyllabusLearningOutcomeRepository outcomes,
				SyllabusLearningUnitRepository learningUnits,
				SyllabusPhaseRepository phases,
				SyllabusExpectedActivityRepository activities,
				SyllabusExpectedDeliverableRepository deliverables,
				SyllabusPhaseLearningOutcomeRepository phaseOutcomeLinks,
				SyllabusDeliverableLearningOutcomeRepository deliverableOutcomeLinks,
				SyllabusLearningUnitOutcomeRepository unitOutcomeLinks,
				EntityManager entityManager) {
			return new JpaAcademicCatalogStore(
					subjects,
					syllabi,
					outcomes,
					learningUnits,
					phases,
					activities,
					deliverables,
					phaseOutcomeLinks,
					deliverableOutcomeLinks,
					unitOutcomeLinks,
					entityManager);
		}
	}

	@Autowired
	private JpaAcademicCatalogStore store;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listSubjects_queryCountStableAndDoesNotNPlusOne() {
		AcademicCatalogService service = service();
		Statistics stats = statistics();

		entityManager.flush();
		entityManager.clear();
		stats.clear();
		SubjectPageResponse empty = service.listSubjects(null, null, null, 0, 50);
		assertThat(empty.items()).isEmpty();
		assertThat(empty.total()).isZero();
		long emptyQueries = stats.getPrepareStatementCount();
		assertThat(emptyQueries).as("empty page").isEqualTo(1L);

		persist("S001", "One", SubjectStatus.ACTIVE, null);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		SubjectPageResponse one = service.listSubjects(null, null, null, 0, 50);
		assertThat(one.items()).hasSize(1);
		assertThat(one.total()).isEqualTo(1);
		long oneQueries = stats.getPrepareStatementCount();
		assertThat(oneQueries).as("1-row short page").isEqualTo(1L);

		for (int i = 2; i <= 50; i++) {
			persist(String.format("S%03d", i), "Name " + i, SubjectStatus.ACTIVE, null);
		}
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		SubjectPageResponse fifty = service.listSubjects(null, null, null, 0, 50);
		assertThat(fifty.items()).hasSize(50);
		assertThat(fifty.total()).isEqualTo(50);
		assertThat(fifty.items()).extracting(SubjectResponse::code).startsWith("S001").endsWith("S050");
		assertThat(stats.getPrepareStatementCount()).as("full page of 50 includes count").isEqualTo(2L);

		stats.clear();
		SubjectPageResponse shortPage = service.listSubjects(null, null, null, 0, 200);
		assertThat(shortPage.items()).hasSize(50);
		assertThat(shortPage.size()).isEqualTo(200);
		assertThat(stats.getPrepareStatementCount()).as("50-row short page matches 1-row short page").isEqualTo(oneQueries);
	}

	@Test
	void listSubjects_ordersBySubjectCodeThenId_andPages() {
		AcademicCatalogService service = service();
		persist(
				UUID.fromString("00000000-0000-4000-8000-000000000001"),
				"ZZZ",
				"Zed",
				SubjectStatus.ACTIVE,
				null);
		persist(
				UUID.fromString("00000000-0000-4000-8000-000000000002"),
				"AAA",
				"Aye",
				SubjectStatus.ACTIVE,
				null);
		persist(
				UUID.fromString("00000000-0000-4000-8000-000000000003"),
				"MMM",
				"Em",
				SubjectStatus.INACTIVE,
				LocalDateTime.of(2026, 1, 1, 0, 0));
		entityManager.flush();
		entityManager.clear();

		SubjectPageResponse all = service.listSubjects(null, null, null, 0, 50);
		assertThat(all.items()).extracting(SubjectResponse::code).containsExactly("AAA", "MMM", "ZZZ");
		assertThat(all.total()).isEqualTo(3);

		SubjectPageResponse page0 = service.listSubjects(null, null, null, 0, 2);
		SubjectPageResponse page1 = service.listSubjects(null, null, null, 1, 2);
		assertThat(page0.items()).extracting(SubjectResponse::code).containsExactly("AAA", "MMM");
		assertThat(page1.items()).extracting(SubjectResponse::code).containsExactly("ZZZ");
		assertThat(page1.total()).isEqualTo(3);

		SubjectPageResponse beyond = service.listSubjects(null, null, null, 4, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(4);
		assertThat(beyond.size()).isEqualTo(50);
		assertThat(beyond.total()).isEqualTo(3);
	}

	@Test
	void listSubjects_filtersMatchPageAndTotal_andSoftDeletedRowsStayVisible() {
		AcademicCatalogService service = service();
		persist("SWP391", "Software Project", SubjectStatus.ACTIVE, null);
		persist("SWT301", "Software Testing", SubjectStatus.INACTIVE, null);
		persist("SWE201", "Intro", SubjectStatus.ACTIVE, LocalDateTime.of(2026, 2, 1, 0, 0));
		entityManager.flush();
		entityManager.clear();

		assertThat(service.listSubjects(" swp391 ", null, null, 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SWP391");
		assertThat(service.listSubjects(null, SubjectStatus.INACTIVE, null, 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SWT301");
		assertThat(service.listSubjects(null, null, "test", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SWT301");
		assertThat(service.listSubjects("SWP391", SubjectStatus.ACTIVE, null, 0, 50).total()).isEqualTo(1);
		assertThat(service.listSubjects("SWP391", SubjectStatus.INACTIVE, null, 0, 50).total()).isZero();
		assertThat(service.listSubjects(null, SubjectStatus.ACTIVE, "soft", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SWP391");
		assertThat(service.listSubjects(null, null, null, 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SWE201", "SWP391", "SWT301");
	}

	@Test
	void listSubjects_qTreatsPercentUnderscoreAndBackslashAsLiterals() {
		AcademicCatalogService service = service();
		persist("SWP391", "Software", SubjectStatus.ACTIVE, null);
		persist("LAB_1", "Lab one", SubjectStatus.ACTIVE, null);
		persist("PCT", "100% Java", SubjectStatus.ACTIVE, null);
		persist("SLSH", "path\\name", SubjectStatus.ACTIVE, null);
		entityManager.flush();
		entityManager.clear();

		assertThat(service.listSubjects(null, null, "%", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("PCT");
		assertThat(service.listSubjects(null, null, "_", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("LAB_1");
		assertThat(service.listSubjects(null, null, "\\", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("SLSH");
		assertThat(service.listSubjects(null, null, "LAB_1", 0, 50).items())
				.extracting(SubjectResponse::code)
				.containsExactly("LAB_1");
	}

	@Test
	void listSubjects_rejectsInvalidPaging() {
		AcademicCatalogService service = service();
		assertInvalid(() -> service.listSubjects(null, null, null, -1, 50));
		assertInvalid(() -> service.listSubjects(null, null, null, 0, 0));
		assertInvalid(() -> service.listSubjects(null, null, null, 0, 201));
	}

	private static void assertInvalid(Runnable call) {
		try {
			call.run();
			throw new AssertionError("expected REQUEST_INVALID");
		} catch (AcademicException ex) {
			assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
			assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	private AcademicCatalogService service() {
		return new AcademicCatalogService(store, Mockito.mock(AuditService.class));
	}

	private Subject persist(String code, String name, SubjectStatus status, LocalDateTime deletedAt) {
		return persist(UUID.randomUUID(), code, name, status, deletedAt);
	}

	private Subject persist(UUID id, String code, String name, SubjectStatus status, LocalDateTime deletedAt) {
		Subject subject = new Subject();
		subject.setId(id);
		subject.setSubjectCode(code);
		subject.setName(name);
		subject.setStatus(status);
		subject.setDeletedAt(deletedAt);
		entityManager.persist(subject);
		return subject;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

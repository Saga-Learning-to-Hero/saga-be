package com.saga.be.service.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.academic.AcademicClassPageResponse;
import com.saga.be.dto.academic.AcademicClassResponse;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Semester;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.ActiveSemesterSettingRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
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
@Import(AdminClassListQueryCountTest.RuntimeBeans.class)
class AdminClassListQueryCountTest {

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
	static class RuntimeBeans {
		@Bean
		JpaAcademicRuntimeStore jpaAcademicRuntimeStore(
				SemesterRepository semesters,
				ActiveSemesterSettingRepository activeSettings,
				AcademicClassRepository classes,
				CourseRepository courses,
				CourseEnrollmentRepository enrollments,
				ProjectRepository projects,
				SubjectRepository subjects,
				SubjectSyllabusVersionRepository syllabi,
				LecturerProfileRepository lecturers) {
			return new JpaAcademicRuntimeStore(
					semesters,
					activeSettings,
					classes,
					courses,
					enrollments,
					projects,
					subjects,
					syllabi,
					lecturers);
		}
	}

	@Autowired
	private JpaAcademicRuntimeStore store;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listClasses_queryCountStableAndDoesNotNPlusOne() {
		AcademicRuntimeService service = service();
		Statistics stats = statistics();

		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AcademicClassPageResponse empty = service.listClasses(null, 0, 50);
		assertThat(empty.items()).isEmpty();
		assertThat(empty.total()).isZero();
		assertThat(empty.page()).isZero();
		assertThat(empty.size()).isEqualTo(50);
		long emptyQueries = stats.getPrepareStatementCount();
		assertThat(emptyQueries).as("empty page is one select; count skipped").isEqualTo(1L);

		Semester semester = persistSemester("FA26", "Fall 2026");
		persistClass(null, "SE001", semester, null);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AcademicClassPageResponse one = service.listClasses(null, 0, 50);
		assertThat(one.items()).hasSize(1);
		assertThat(one.total()).isEqualTo(1);
		assertThat(one.items().getFirst().classCode()).isEqualTo("SE001");
		assertThat(one.items().getFirst().semesterCode()).isEqualTo("FA26");
		long oneQueries = stats.getPrepareStatementCount();
		assertThat(oneQueries).as("1-row short page is one select; count skipped").isEqualTo(1L);

		for (int i = 2; i <= 50; i++) {
			persistClass(null, String.format("SE%03d", i), semester, null);
		}
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AcademicClassPageResponse fifty = service.listClasses(null, 0, 50);
		assertThat(fifty.items()).hasSize(50);
		assertThat(fifty.total()).isEqualTo(50);
		assertThat(fifty.items()).extracting(AcademicClassResponse::classCode).startsWith("SE001").endsWith("SE050");
		assertThat(fifty.items()).extracting(AcademicClassResponse::semesterCode).containsOnly("FA26");
		long fiftyQueries = stats.getPrepareStatementCount();
		assertThat(fiftyQueries).as("full page of 50 is select + count").isEqualTo(2L);

		stats.clear();
		AcademicClassPageResponse shortPage = service.listClasses(null, 0, 200);
		assertThat(shortPage.items()).hasSize(50);
		assertThat(shortPage.size()).isEqualTo(200);
		assertThat(stats.getPrepareStatementCount())
				.as("50-row short page matches 1-row short page")
				.isEqualTo(oneQueries);

		stats.clear();
		AcademicClassPageResponse sizeOne = service.listClasses(null, 0, 1);
		assertThat(sizeOne.items()).hasSize(1);
		assertThat(stats.getPrepareStatementCount())
				.as("size=1 full page stays bounded like size=50 full page")
				.isEqualTo(fiftyQueries);
	}

	@Test
	void listClasses_ordersByClassCodeThenId_andPages() {
		AcademicRuntimeService service = service();
		Semester fa26 = persistSemester("FA26", "Fall 2026");
		Semester sp27 = persistSemester("SP27", "Spring 2027");
		AcademicClass se1706 = persistClass(
				UUID.fromString("00000000-0000-4000-8000-000000000003"), "SE1706", fa26, null);
		AcademicClass se1705High = persistClass(
				UUID.fromString("00000000-0000-4000-8000-000000000002"), "SE1705", fa26, null);
		AcademicClass se1705Low = persistClass(
				UUID.fromString("00000000-0000-4000-8000-000000000001"), "SE1705", sp27, null);
		entityManager.flush();
		entityManager.clear();

		AcademicClassPageResponse all = service.listClasses(null, null, null);
		assertThat(all.page()).isZero();
		assertThat(all.size()).isEqualTo(50);
		assertThat(all.total()).isEqualTo(3);
		assertThat(all.items())
				.extracting(AcademicClassResponse::id)
				.containsExactly(se1705Low.getId(), se1705High.getId(), se1706.getId());
		assertThat(all.items())
				.extracting(AcademicClassResponse::classCode)
				.containsExactly("SE1705", "SE1705", "SE1706");

		AcademicClassPageResponse page0 = service.listClasses(null, 0, 2);
		AcademicClassPageResponse page1 = service.listClasses(null, 1, 2);
		assertThat(page0.items())
				.extracting(AcademicClassResponse::id)
				.containsExactly(se1705Low.getId(), se1705High.getId());
		assertThat(page1.items()).extracting(AcademicClassResponse::id).containsExactly(se1706.getId());
		assertThat(page1.total()).isEqualTo(3);

		AcademicClassPageResponse beyond = service.listClasses(null, 9, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.size()).isEqualTo(50);
		assertThat(beyond.total()).isEqualTo(3);
	}

	@Test
	void listClasses_filtersBySemester_andOmitsDeleted() {
		AcademicRuntimeService service = service();
		Semester fa26 = persistSemester("FA26", "Fall 2026");
		Semester sp27 = persistSemester("SP27", "Spring 2027");
		Semester empty = persistSemester("SU27", "Summer 2027");
		AcademicClass se1705 = persistClass(null, "SE1705", fa26, null);
		persistClass(null, "SE1706", fa26, null);
		persistClass(null, "SE1801", sp27, null);
		persistClass(null, "SE1799", fa26, LocalDateTime.of(2026, 2, 1, 0, 0));
		entityManager.flush();
		entityManager.clear();

		assertThat(service.listClasses(null, 0, 50).total()).isEqualTo(3);
		AcademicClassPageResponse fa26Page = service.listClasses(fa26.getId(), 0, 50);
		assertThat(fa26Page.total()).isEqualTo(2);
		assertThat(fa26Page.items()).extracting(AcademicClassResponse::classCode).containsExactly("SE1705", "SE1706");
		assertThat(fa26Page.items()).extracting(AcademicClassResponse::semesterCode).containsOnly("FA26");
		assertThat(service.listClasses(sp27.getId(), 0, 50).total()).isEqualTo(1);
		assertThat(service.listClasses(empty.getId(), 0, 50).total()).isZero();
		assertThat(service.listClasses(empty.getId(), 0, 50).items()).isEmpty();
		assertThat(se1705.getClassCode()).isEqualTo("SE1705");
	}

	@Test
	void listClasses_rejectsMissingSemesterAndInvalidPaging() {
		AcademicRuntimeService service = service();
		AcademicException missing = assertThrowsLike(() -> service.listClasses(UUID.randomUUID(), 0, 50));
		assertThat(missing.getCode()).isEqualTo(AcademicErrorCode.SEMESTER_NOT_FOUND);
		assertInvalid(() -> service.listClasses(null, -1, 50));
		assertInvalid(() -> service.listClasses(null, 0, 0));
		assertInvalid(() -> service.listClasses(null, 0, 201));
	}

	private static AcademicException assertThrowsLike(Runnable call) {
		try {
			call.run();
			throw new AssertionError("expected AcademicException");
		} catch (AcademicException ex) {
			return ex;
		}
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

	private AcademicRuntimeService service() {
		return new AcademicRuntimeService(store, Mockito.mock(AuditService.class));
	}

	private Semester persistSemester(String code, String name) {
		Semester semester = new Semester();
		semester.setCode(code);
		semester.setName(name);
		entityManager.persist(semester);
		return semester;
	}

	private AcademicClass persistClass(UUID id, String classCode, Semester semester, LocalDateTime deletedAt) {
		AcademicClass academicClass = new AcademicClass();
		if (id != null) {
			academicClass.setId(id);
		}
		academicClass.setClassCode(classCode);
		academicClass.setName(classCode);
		academicClass.setSemester(semester);
		academicClass.setDeletedAt(deletedAt);
		entityManager.persist(academicClass);
		return academicClass;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

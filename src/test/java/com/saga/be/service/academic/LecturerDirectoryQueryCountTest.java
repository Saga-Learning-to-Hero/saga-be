package com.saga.be.service.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.academic.LecturerDirectoryPageResponse;
import com.saga.be.dto.academic.LecturerDirectoryResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
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
		persistLecturer("Lecturer 0", "lecturer-0@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 1", "lecturer-1@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 2", "lecturer-2@fe.edu.vn", AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();
		Statistics stats = statistics();
		stats.clear();
		List<LecturerDirectoryResponse> first = service.list(null, null);
		assertEquals(3, first.size());
		first.forEach(row -> assertTrue(row.email() != null && !row.email().isBlank()));
		long queriesForThree = stats.getPrepareStatementCount();
		assertEquals(1, queriesForThree);

		persistLecturer("Lecturer 3", "lecturer-3@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 4", "lecturer-4@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 5", "lecturer-5@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 6", "lecturer-6@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Lecturer 7", "lecturer-7@fe.edu.vn", AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		List<LecturerDirectoryResponse> second = service.list(null, null);
		assertEquals(8, second.size());
		second.forEach(row -> assertTrue(row.lecturerProfileId() != null && !row.lecturerProfileId().equals(row.userId())));
		assertEquals(queriesForThree, stats.getPrepareStatementCount());
	}

	@Test
	void pagedDirectoryQueryCountIsBounded() {
		AdminLecturerService service = new AdminLecturerService(lecturers);
		Statistics stats = statistics();

		entityManager.flush();
		entityManager.clear();
		stats.clear();
		LecturerDirectoryPageResponse empty = service.listPaged(null, null, 0, 50);
		assertThat(empty.items()).isEmpty();
		assertThat(empty.total()).isZero();
		assertThat(empty.page()).isZero();
		assertThat(empty.size()).isEqualTo(50);
		long emptyQueries = stats.getPrepareStatementCount();
		assertThat(emptyQueries).as("empty paged directory").isEqualTo(1L);

		persistLecturer("Ann", "ann@fe.edu.vn", AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		LecturerDirectoryPageResponse one = service.listPaged(null, null, 0, 50);
		assertThat(one.items()).hasSize(1);
		assertThat(one.items().getFirst().email()).isEqualTo("ann@fe.edu.vn");
		assertThat(one.items().getFirst().fullName()).isEqualTo("Ann");
		long oneQueries = stats.getPrepareStatementCount();
		assertThat(oneQueries).as("1-row short page").isEqualTo(1L);

		for (int i = 2; i <= 50; i++) {
			persistLecturer(String.format("Name %02d", i), String.format("lecturer-%02d@fe.edu.vn", i), AccountStatus.ACTIVE);
		}
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		LecturerDirectoryPageResponse fifty = service.listPaged(null, null, 0, 50);
		assertThat(fifty.items()).hasSize(50);
		assertThat(fifty.total()).isEqualTo(50);
		assertThat(fifty.items()).extracting(LecturerDirectoryResponse::email).doesNotContainNull();
		long fiftyQueries = stats.getPrepareStatementCount();
		assertThat(fiftyQueries).as("full page of 50 is select + count").isEqualTo(2L);

		stats.clear();
		LecturerDirectoryPageResponse shortPage = service.listPaged(null, null, 0, 200);
		assertThat(shortPage.items()).hasSize(50);
		assertThat(shortPage.size()).isEqualTo(200);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(oneQueries);

		stats.clear();
		LecturerDirectoryPageResponse sizeOne = service.listPaged(null, null, 0, 1);
		assertThat(sizeOne.items()).hasSize(1);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(fiftyQueries);
	}

	@Test
	void pagedDirectoryOrdersByNameThenEmailThenId_andPages() {
		AdminLecturerService service = new AdminLecturerService(lecturers);
		persistLecturer(
				UUID.fromString("00000000-0000-4000-8000-000000000003"),
				"Zed",
				"zed@fe.edu.vn",
				AccountStatus.ACTIVE);
		persistLecturer(
				UUID.fromString("00000000-0000-4000-8000-000000000002"),
				"Ann",
				"ann-b@fe.edu.vn",
				AccountStatus.ACTIVE);
		persistLecturer(
				UUID.fromString("00000000-0000-4000-8000-000000000001"),
				"Ann",
				"ann-a@fe.edu.vn",
				AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();

		LecturerDirectoryPageResponse all = service.listPaged(null, null, null, null);
		assertThat(all.page()).isZero();
		assertThat(all.size()).isEqualTo(50);
		assertThat(all.total()).isEqualTo(3);
		assertThat(all.items())
				.extracting(LecturerDirectoryResponse::email)
				.containsExactly("ann-a@fe.edu.vn", "ann-b@fe.edu.vn", "zed@fe.edu.vn");

		LecturerDirectoryPageResponse page0 = service.listPaged(null, null, 0, 2);
		LecturerDirectoryPageResponse page1 = service.listPaged(null, null, 1, 2);
		assertThat(page0.items()).extracting(LecturerDirectoryResponse::email).containsExactly("ann-a@fe.edu.vn", "ann-b@fe.edu.vn");
		assertThat(page1.items()).extracting(LecturerDirectoryResponse::email).containsExactly("zed@fe.edu.vn");
		assertThat(page1.total()).isEqualTo(3);

		LecturerDirectoryPageResponse beyond = service.listPaged(null, null, 9, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.total()).isEqualTo(3);
	}

	@Test
	void filtersAndLikeEscapingMatchDropdownAndPaged() {
		AdminLecturerService service = new AdminLecturerService(lecturers);
		persistLecturer("Lan Nguyen", "lan@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Off", "off@fe.edu.vn", AccountStatus.INACTIVE);
		persistLecturer("Pct", "100%java@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Und", "lab_1@fe.edu.vn", AccountStatus.ACTIVE);
		persistLecturer("Slash", "path\\name@fe.edu.vn", AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();

		assertThat(service.list(null, null)).extracting(LecturerDirectoryResponse::email)
				.containsExactly("lan@fe.edu.vn", "100%java@fe.edu.vn", "path\\name@fe.edu.vn", "lab_1@fe.edu.vn");
		assertThat(service.list(true, null)).extracting(LecturerDirectoryResponse::email)
				.containsExactly("lan@fe.edu.vn", "100%java@fe.edu.vn", "path\\name@fe.edu.vn", "lab_1@fe.edu.vn");
		assertThat(service.list(false, null)).extracting(LecturerDirectoryResponse::email).containsExactly("off@fe.edu.vn");
		assertThat(service.list(true, "LAN")).extracting(LecturerDirectoryResponse::email).containsExactly("lan@fe.edu.vn");
		assertThat(service.list(false, "off")).extracting(LecturerDirectoryResponse::email).containsExactly("off@fe.edu.vn");
		assertThat(service.list(true, "%")).extracting(LecturerDirectoryResponse::email).containsExactly("100%java@fe.edu.vn");
		assertThat(service.list(true, "_")).extracting(LecturerDirectoryResponse::email).containsExactly("lab_1@fe.edu.vn");
		assertThat(service.list(true, "\\")).extracting(LecturerDirectoryResponse::email).containsExactly("path\\name@fe.edu.vn");

		assertThat(service.listPaged(true, "lan", 0, 50).items())
				.extracting(LecturerDirectoryResponse::email)
				.containsExactly("lan@fe.edu.vn");
		assertThat(service.listPaged(false, null, 0, 50).total()).isEqualTo(1);
		assertThat(service.listPaged(true, "%", 0, 50).items())
				.extracting(LecturerDirectoryResponse::email)
				.containsExactly("100%java@fe.edu.vn");
	}

	@Test
	void pagedRejectsInvalidPaging() {
		AdminLecturerService service = new AdminLecturerService(lecturers);
		assertInvalid(() -> service.listPaged(null, null, -1, 50));
		assertInvalid(() -> service.listPaged(null, null, 0, 0));
		assertInvalid(() -> service.listPaged(null, null, 0, 201));
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

	private LecturerProfile persistLecturer(String name, String email, AccountStatus status) {
		return persistLecturer(UUID.randomUUID(), name, email, status);
	}

	private LecturerProfile persistLecturer(UUID profileId, String name, String email, AccountStatus status) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(name);
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(status);
		users.save(account);
		LecturerProfile profile = new LecturerProfile();
		profile.setId(profileId);
		profile.setUserAccount(account);
		return lecturers.save(profile);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

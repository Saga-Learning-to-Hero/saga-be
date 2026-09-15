package com.saga.be.service.admin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.saga.be.dto.admin.AdminUserPageResponse;
import com.saga.be.dto.admin.AdminUserResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
class AdminUserQueryCountTest {

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
	private StudentProfileRepository students;
	@Autowired
	private LecturerProfileRepository lecturers;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listQueryCountDoesNotGrowWithUserCountAndOrderIsDeterministic() {
		AdminUserQueryService service = new AdminUserQueryService(users);
		persistStudent("zeta@fpt.edu.vn", "Zeta", "SE000001", LocalDateTime.of(2026, 1, 1, 10, 0), AccountStatus.ACTIVE);
		persistStudent("alpha@fpt.edu.vn", "Alpha", "SE000002", LocalDateTime.of(2026, 1, 3, 10, 0), AccountStatus.ACTIVE);
		persistLecturer("lan@fe.edu.vn", "Lan", LocalDateTime.of(2026, 1, 2, 10, 0), AccountStatus.INACTIVE);
		persistAdmin("root-admin@saga.local", "Root Admin", LocalDateTime.of(2026, 1, 5, 10, 0));
		entityManager.flush();
		entityManager.clear();

		Statistics stats = statistics();
		stats.clear();
		AdminUserPageResponse first = service.list(null, null, null, 0, 50);
		assertEquals(3, first.total());
		assertEquals(List.of("alpha@fpt.edu.vn", "lan@fe.edu.vn", "zeta@fpt.edu.vn"), emails(first));
		assertTrue(first.items().stream().noneMatch(row -> "ADMIN".equals(row.role())));
		assertEquals("SE000002", first.items().getFirst().studentCode());
		assertTrue(first.items().get(1).lecturerProfileId() != null);
		long queriesForThree = stats.getPrepareStatementCount();
		assertTrue(queriesForThree >= 1 && queriesForThree <= 2);

		persistStudent("more@fpt.edu.vn", "More", "SE000003", LocalDateTime.of(2026, 1, 4, 10, 0), AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		AdminUserPageResponse second = service.list(null, null, null, 0, 50);
		assertEquals(4, second.total());
		assertEquals("more@fpt.edu.vn", second.items().getFirst().email());
		long queriesForFour = stats.getPrepareStatementCount();
		assertTrue(queriesForFour >= 1 && queriesForFour <= 2);
		assertEquals(queriesForThree, queriesForFour);
	}

	@Test
	void filtersSearchRoleStatusAndPaginate() {
		AdminUserQueryService service = new AdminUserQueryService(users);
		persistStudent("ada@fpt.edu.vn", "Ada Lovelace", "SE111111", LocalDateTime.of(2026, 2, 1, 0, 0), AccountStatus.ACTIVE);
		persistStudent("bob@gmail.com", "Bob", "SE222222", LocalDateTime.of(2026, 2, 2, 0, 0), AccountStatus.INACTIVE);
		persistLecturer("lan@fe.edu.vn", "Lan", LocalDateTime.of(2026, 2, 3, 0, 0), AccountStatus.ACTIVE);
		entityManager.flush();
		entityManager.clear();

		AdminUserPageResponse byQ = service.list("ada", null, null, 0, 50);
		assertEquals(1, byQ.total());
		assertEquals("ada@fpt.edu.vn", byQ.items().getFirst().email());

		AdminUserPageResponse byRole = service.list(null, "LECTURER", null, 0, 50);
		assertEquals(1, byRole.total());
		assertEquals("lan@fe.edu.vn", byRole.items().getFirst().email());

		AdminUserPageResponse byStatus = service.list(null, null, "INACTIVE", 0, 50);
		assertEquals(1, byStatus.total());
		assertEquals("bob@gmail.com", byStatus.items().getFirst().email());

		AdminUserPageResponse page0 = service.list(null, null, null, 0, 1);
		AdminUserPageResponse page1 = service.list(null, null, null, 1, 1);
		assertEquals(3, page0.total());
		assertEquals(1, page0.items().size());
		assertEquals("lan@fe.edu.vn", page0.items().getFirst().email());
		assertEquals("bob@gmail.com", page1.items().getFirst().email());
		assertEquals("Lan", service.get(page0.items().getFirst().id()).fullName());
		assertEquals(page0.items().getFirst().lecturerProfileId(), service.get(page0.items().getFirst().id()).lecturerProfileId());
	}

	@Test
	void wildcardInSearchDoesNotMatchEverything() {
		AdminUserQueryService service = new AdminUserQueryService(users);
		persistStudent("one@fpt.edu.vn", "One", "SE333333", LocalDateTime.of(2026, 3, 1, 0, 0), AccountStatus.ACTIVE);
		persistStudent("two@fpt.edu.vn", "Two", "SE444444", LocalDateTime.of(2026, 3, 2, 0, 0), AccountStatus.ACTIVE);
		entityManager.flush();
		AdminUserPageResponse page = service.list("%", null, null, 0, 50);
		assertEquals(0, page.total());
	}

	@Test
	void adminAccountsAreHiddenFromListSearchAndDetail() {
		AdminUserQueryService service = new AdminUserQueryService(users);
		UserAccount student = persistStudent(
				"ada@fpt.edu.vn", "Ada Lovelace", "SE111111", LocalDateTime.of(2026, 4, 1, 0, 0), AccountStatus.ACTIVE);
		UserAccount lecturer = persistLecturerReturning(
				"lan@fe.edu.vn", "Lan", LocalDateTime.of(2026, 4, 2, 0, 0), AccountStatus.ACTIVE);
		UserAccount admin = persistAdmin("only-admin@saga.local", "Only Admin", LocalDateTime.of(2026, 4, 3, 0, 0));
		entityManager.flush();
		entityManager.clear();

		AdminUserPageResponse list = service.list(null, null, null, 0, 50);
		assertEquals(2, list.total());
		assertEquals(List.of("lan@fe.edu.vn", "ada@fpt.edu.vn"), emails(list));

		AdminUserPageResponse byAdminName = service.list("only-admin", null, null, 0, 50);
		assertEquals(0, byAdminName.total());
		assertTrue(byAdminName.items().isEmpty());

		AcademicException roleEx = assertThrows(AcademicException.class, () -> service.list(null, "ADMIN", null, 0, 50));
		assertEquals(AcademicErrorCode.REQUEST_INVALID, roleEx.getCode());
		assertEquals(HttpStatus.BAD_REQUEST, roleEx.getStatus());

		assertEquals("Ada Lovelace", service.get(student.getId()).fullName());
		assertEquals("STUDENT", service.get(student.getId()).role());
		assertEquals("Lan", service.get(lecturer.getId()).fullName());
		assertEquals("LECTURER", service.get(lecturer.getId()).role());

		UUID unknown = UUID.fromString("99999999-9999-9999-9999-999999999999");
		AcademicException missing = assertThrows(AcademicException.class, () -> service.get(unknown));
		AcademicException hiddenAdmin = assertThrows(AcademicException.class, () -> service.get(admin.getId()));
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, missing.getCode());
		assertEquals(AcademicErrorCode.USER_ACCOUNT_NOT_FOUND, hiddenAdmin.getCode());
		assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
		assertEquals(missing.getStatus(), hiddenAdmin.getStatus());
		assertEquals(missing.getMessage(), hiddenAdmin.getMessage());
	}

	private static List<String> emails(AdminUserPageResponse page) {
		List<String> emails = new ArrayList<>();
		for (AdminUserResponse row : page.items()) {
			emails.add(row.email());
		}
		return emails;
	}

	private UserAccount persistStudent(
			String email, String name, String code, LocalDateTime createdAt, AccountStatus status) {
		UserAccount account = persistAccount(email, name, AccountRole.STUDENT, status, createdAt);
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		students.save(profile);
		return account;
	}

	private void persistLecturer(String email, String name, LocalDateTime createdAt, AccountStatus status) {
		persistLecturerReturning(email, name, createdAt, status);
	}

	private UserAccount persistLecturerReturning(String email, String name, LocalDateTime createdAt, AccountStatus status) {
		UserAccount account = persistAccount(email, name, AccountRole.LECTURER, status, createdAt);
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		lecturers.save(profile);
		return account;
	}

	private UserAccount persistAdmin(String email, String name, LocalDateTime createdAt) {
		return persistAccount(email, name, AccountRole.ADMIN, AccountStatus.ACTIVE, createdAt);
	}

	private UserAccount persistAccount(
			String email, String name, AccountRole role, AccountStatus status, LocalDateTime createdAt) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setUsername(email.substring(0, email.indexOf('@')));
		account.setFullName(name);
		account.setAccountRole(role);
		account.setAccountStatus(status);
		account.setPasswordHash("not-a-secret-in-api");
		account.setGoogleSubject(UUID.randomUUID().toString());
		account.setCreatedAt(createdAt);
		account.setUpdatedAt(createdAt);
		UserAccount saved = users.save(account);
		entityManager.flush();
		entityManager
				.createNativeQuery("update user_account set created_at = :ts where id = :id")
				.setParameter("ts", createdAt)
				.setParameter("id", saved.getId().toString())
				.executeUpdate();
		saved.setCreatedAt(createdAt);
		return saved;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

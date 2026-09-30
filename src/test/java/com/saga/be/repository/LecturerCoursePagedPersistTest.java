package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.academic.CoursePageResponse;
import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import com.saga.be.service.lecturer.LecturerCourseService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
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

/**
 * Real H2 + real JPQL for {@code GET /api/lecturer/courses/paged}: lecturer scope vs ADMIN, the
 * unpaged list's order, semester filter, case-insensitive search across course/class/subject, LIKE
 * wildcards taken literally, soft-deleted courses hidden, and page slicing/total.
 */
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
class LecturerCoursePagedPersistTest {

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

	@Autowired private UserAccountRepository users;
	@Autowired private LecturerProfileRepository lecturerProfiles;
	@Autowired private SubjectRepository subjects;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SemesterRepository semesters;
	@Autowired private CourseRepository courses;
	@Autowired private CourseEnrollmentRepository enrollments;
	@Autowired private PlatformTransactionManager transactionManager;

	private LecturerCourseService service;
	private TransactionTemplate tx;
	private String tag;
	private UserAccount lecturer;
	private UserAccount otherLecturer;
	private UserAccount admin;
	private Semester fall;
	private Semester spring;

	@BeforeEach
	void setUp() {
		service = new LecturerCourseService(
				new LecturerCourseAuthorization(courses, lecturerProfiles), courses, lecturerProfiles, enrollments);
		tx = new TransactionTemplate(transactionManager);
		tag = UUID.randomUUID().toString().substring(0, 6);
		lecturer = users.save(account(AccountRole.LECTURER, "lecturer"));
		otherLecturer = users.save(account(AccountRole.LECTURER, "other"));
		admin = users.save(account(AccountRole.ADMIN, "admin"));
		LecturerProfile mine = lecturerProfiles.save(profile(lecturer));
		LecturerProfile theirs = lecturerProfiles.save(profile(otherLecturer));
		fall = semesters.save(semester("FA" + tag));
		spring = semesters.save(semester("SP" + tag));
		course("C Software Project", "SWP391", "SE18B01", fall, mine, false);
		course("A Web Programming", "PRN231", "SE18A01", fall, mine, false);
		course("B Requirement 100% done", "SWR302", "SE1802", spring, mine, false);
		course("D Deleted", "SWP391", "SE18Z99", fall, mine, true);
		course("E Someone else", "SWP391", "SE18C01", fall, theirs, false);
	}

	@Test
	void lecturerSeesOnlyOwnLiveCoursesInTheUnpagedOrderAndPagesSliceIt() {
		CoursePageResponse first = paged(lecturer, null, null, 0, 2);
		CoursePageResponse second = paged(lecturer, null, null, 1, 2);

		assertThat(first.total()).isEqualTo(3);
		assertThat(names(first)).containsExactly("A Web Programming", "B Requirement 100% done");
		assertThat(names(second)).containsExactly("C Software Project");
		List<String> unpaged = tx.execute(status -> service.listCourses(lecturer)).stream()
				.map(CourseResponse::name)
				.toList();
		assertThat(unpaged).containsExactly("A Web Programming", "B Requirement 100% done", "C Software Project");
	}

	@Test
	void semesterAndCaseInsensitiveSearchAcrossCourseClassAndSubjectNarrowTheList() {
		assertThat(names(paged(lecturer, spring.getId(), null, null, null))).containsExactly("B Requirement 100% done");
		assertThat(names(paged(lecturer, null, "se18b", null, null))).containsExactly("C Software Project");
		assertThat(names(paged(lecturer, null, "swr302", null, null))).containsExactly("B Requirement 100% done");
		assertThat(names(paged(lecturer, null, "subject prn231", null, null))).containsExactly("A Web Programming");
		assertThat(names(paged(lecturer, fall.getId(), "web", null, null))).containsExactly("A Web Programming");
		assertThat(paged(lecturer, null, "no-such-course", null, null).total()).isZero();
	}

	@Test
	void likeWildcardsInSearchAreTakenLiterally() {
		assertThat(names(paged(lecturer, null, "100%", null, null))).containsExactly("B Requirement 100% done");
		assertThat(paged(lecturer, null, "%", null, null).total()).isEqualTo(1);
		assertThat(paged(lecturer, null, "_", null, null).total()).isZero();
	}

	@Test
	void adminSeesEveryLiveCourseAndAnAccountWithoutLecturerProfileSeesNothing() {
		CoursePageResponse all = paged(admin, fall.getId(), null, null, null);
		assertThat(names(all)).contains("A Web Programming", "C Software Project", "E Someone else")
				.doesNotContain("D Deleted");

		UserAccount stranger = users.save(account(AccountRole.LECTURER, "stranger"));
		assertThat(paged(stranger, null, null, null, null).total()).isZero();
	}

	@Test
	void invalidPagingIsRejected() {
		assertThatThrownBy(() -> paged(lecturer, null, null, -1, 10))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThatThrownBy(() -> paged(lecturer, null, null, 0, 0)).isInstanceOf(AcademicException.class);
	}

	private CoursePageResponse paged(UserAccount actor, UUID semesterId, String search, Integer page, Integer size) {
		return tx.execute(status -> service.listCoursesPaged(actor, semesterId, search, page, size));
	}

	private static List<String> names(CoursePageResponse page) {
		return page.items().stream().map(CourseResponse::name).toList();
	}

	private void course(
			String name, String subjectCode, String classCode, Semester semester, LecturerProfile instructor, boolean deleted) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode(classCode + "-" + tag);
		academicClass.setName(classCode);
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode(subjectCode + "-" + UUID.randomUUID().toString().substring(0, 6));
		subject.setName("Subject " + subjectCode);
		subject = subjects.save(subject);
		Course course = new Course();
		course.setName(name);
		course.setCourseCode(subjectCode);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSubject(subject);
		course.setInstructor(instructor);
		if (deleted) {
			course.setDeletedAt(LocalDateTime.now());
		}
		courses.save(course);
	}

	private static Semester semester(String code) {
		Semester semester = new Semester();
		semester.setCode(code);
		semester.setName("Semester " + code);
		return semester;
	}

	private static LecturerProfile profile(UserAccount account) {
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		return profile;
	}

	private static UserAccount account(AccountRole role, String label) {
		UserAccount account = new UserAccount();
		account.setEmail(label + "-" + UUID.randomUUID() + "@fe.edu.vn");
		account.setFullName(label);
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

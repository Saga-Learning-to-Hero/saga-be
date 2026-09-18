package com.saga.be.service.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.academic.CoursePageResponse;
import com.saga.be.dto.academic.CourseResponse;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
@Import(AdminCourseListQueryCountTest.RuntimeBeans.class)
class AdminCourseListQueryCountTest {

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
	private CourseRepository courses;
	@Autowired
	private EntityManager entityManager;

	@Test
	void listCourses_queryCountStableAndDoesNotNPlusOne() {
		AcademicRuntimeService service = service();
		Statistics stats = statistics();

		entityManager.flush();
		entityManager.clear();
		stats.clear();
		CoursePageResponse empty = service.listCourses(null, null, null, null, 0, 50);
		assertThat(empty.items()).isEmpty();
		assertThat(empty.total()).isZero();
		assertThat(empty.page()).isZero();
		assertThat(empty.size()).isEqualTo(50);
		long emptyQueries = stats.getPrepareStatementCount();
		assertThat(emptyQueries).as("empty page is ID select only; count skipped; IN skipped").isEqualTo(1L);

		Graph graph = persistGraph();
		persistCourse(
				null,
				"Course 001",
				"C001",
				graph.semester1,
				persistClass(graph.semester1, "SE001"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		CoursePageResponse one = service.listCourses(null, null, null, null, 0, 50);
		assertThat(one.items()).hasSize(1);
		assertThat(one.total()).isEqualTo(1);
		CourseResponse row = one.items().getFirst();
		assertThat(row.classCode()).isEqualTo("SE001");
		assertThat(row.semesterCode()).isEqualTo("FA26");
		assertThat(row.subjectCode()).isEqualTo("SWP391");
		assertThat(row.syllabusVersionLabel()).isEqualTo("2026-v1");
		assertThat(row.lecturerEmail()).isEqualTo("lecturer1@saga.local");
		long oneQueries = stats.getPrepareStatementCount();
		assertThat(oneQueries).as("1-row short page is ID select + fetch; count skipped").isEqualTo(2L);

		for (int i = 2; i <= 50; i++) {
			persistCourse(
					null,
					String.format("Course %03d", i),
					"C" + i,
					graph.semester1,
					persistClass(graph.semester1, String.format("SE%03d", i)),
					graph.subject1,
					graph.syllabus1,
					graph.lecturer1,
					null);
		}
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		CoursePageResponse fifty = service.listCourses(null, null, null, null, 0, 50);
		assertThat(fifty.items()).hasSize(50);
		assertThat(fifty.total()).isEqualTo(50);
		assertThat(fifty.items()).extracting(CourseResponse::name).startsWith("Course 001").endsWith("Course 050");
		assertThat(fifty.items())
				.extracting(CourseResponse::lecturerEmail)
				.containsOnly("lecturer1@saga.local");
		long fiftyQueries = stats.getPrepareStatementCount();
		assertThat(fiftyQueries).as("full page of 50 is ID select + count + fetch").isEqualTo(3L);

		stats.clear();
		CoursePageResponse shortPage = service.listCourses(null, null, null, null, 0, 200);
		assertThat(shortPage.items()).hasSize(50);
		assertThat(shortPage.size()).isEqualTo(200);
		assertThat(stats.getPrepareStatementCount())
				.as("50-row short page matches 1-row short page")
				.isEqualTo(oneQueries);

		stats.clear();
		CoursePageResponse sizeOne = service.listCourses(null, null, null, null, 0, 1);
		assertThat(sizeOne.items()).hasSize(1);
		assertThat(stats.getPrepareStatementCount())
				.as("size=1 full page stays bounded like size=50 full page")
				.isEqualTo(fiftyQueries);
	}

	@Test
	void listCourses_ordersByNameThenId_andPages() {
		AcademicRuntimeService service = service();
		Graph graph = persistGraph();
		Course zebra = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000003"),
				"Zebra",
				"Z",
				graph.semester1,
				persistClass(graph.semester1, "SEZ"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		Course alphaHigh = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000002"),
				"Alpha",
				"A2",
				graph.semester1,
				persistClass(graph.semester1, "SEA2"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		Course alphaLow = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000001"),
				"Alpha",
				"A1",
				graph.semester1,
				persistClass(graph.semester1, "SEA1"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		entityManager.flush();
		entityManager.clear();

		CoursePageResponse all = service.listCourses(null, null, null, null, null, null);
		assertThat(all.page()).isZero();
		assertThat(all.size()).isEqualTo(50);
		assertThat(all.total()).isEqualTo(3);
		assertThat(all.items())
				.extracting(CourseResponse::id)
				.containsExactly(alphaLow.getId(), alphaHigh.getId(), zebra.getId());
		assertThat(all.items()).extracting(CourseResponse::name).containsExactly("Alpha", "Alpha", "Zebra");

		CoursePageResponse page0 = service.listCourses(null, null, null, null, 0, 2);
		CoursePageResponse page1 = service.listCourses(null, null, null, null, 1, 2);
		assertThat(page0.items())
				.extracting(CourseResponse::id)
				.containsExactly(alphaLow.getId(), alphaHigh.getId());
		assertThat(page1.items()).extracting(CourseResponse::id).containsExactly(zebra.getId());
		assertThat(page1.page()).isEqualTo(1);
		assertThat(page1.size()).isEqualTo(2);
		assertThat(page1.total()).isEqualTo(3);

		CoursePageResponse beyond = service.listCourses(null, null, null, null, 9, 50);
		assertThat(beyond.items()).isEmpty();
		assertThat(beyond.page()).isEqualTo(9);
		assertThat(beyond.size()).isEqualTo(50);
		assertThat(beyond.total()).isEqualTo(3);
	}

	@Test
	void listCourses_filtersMatchPageAndTotal_andOmitsDeleted() {
		AcademicRuntimeService service = service();
		Graph graph = persistGraph();
		AcademicClass class1 = persistClass(graph.semester1, "SE1705");
		AcademicClass class2 = persistClass(graph.semester1, "SE1706");
		AcademicClass class3 = persistClass(graph.semester1, "SE1707");
		AcademicClass otherClass = persistClass(graph.semester2, "SE1801");
		Course zebra = persistCourse(null, "Zebra", "C1", graph.semester1, class1, graph.subject1, graph.syllabus1, graph.lecturer1, null);
		Course alphaHigh =
				persistCourse(null, "Alpha", "C2", graph.semester1, class2, graph.subject1, graph.syllabus1, graph.lecturer1, null);
		persistCourse(null, "Alpha", "C3", graph.semester1, class3, graph.subject2, graph.syllabus2, graph.lecturer2, null);
		persistCourse(
				null,
				"Beta",
				"C4",
				graph.semester2,
				otherClass,
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				LocalDateTime.of(2026, 2, 1, 0, 0));
		entityManager.flush();
		entityManager.clear();

		assertThat(service.listCourses(null, null, null, null, 0, 50).total()).isEqualTo(3);
		assertThat(service.listCourses(graph.semester1.getId(), null, null, null, 0, 50).total()).isEqualTo(3);
		assertThat(service.listCourses(null, class1.getId(), null, null, 0, 50).items())
				.extracting(CourseResponse::id)
				.containsExactly(zebra.getId());
		assertThat(service.listCourses(null, null, graph.subject1.getId(), null, 0, 50).total()).isEqualTo(2);
		assertThat(service.listCourses(null, null, null, graph.lecturer1.getId(), 0, 50).total()).isEqualTo(2);
		assertThat(service.listCourses(graph.semester1.getId(), null, graph.subject1.getId(), null, 0, 50).total())
				.isEqualTo(2);
		assertThat(service.listCourses(graph.semester1.getId(), class1.getId(), null, null, 0, 50).total()).isEqualTo(1);
		assertThat(service.listCourses(null, null, graph.subject1.getId(), graph.lecturer1.getId(), 0, 50).total())
				.isEqualTo(2);
		assertThat(service.listCourses(null, null, UUID.randomUUID(), null, 0, 50).total()).isZero();
		assertThat(service.listCourses(null, null, null, UUID.randomUUID(), 0, 50).total()).isZero();
		assertThat(service.listCourses(null, class1.getId(), null, null, 0, 50).items())
				.extracting(CourseResponse::name)
				.containsExactly("Zebra");
		assertThat(service.listCourses(null, null, graph.subject1.getId(), null, 0, 50).items())
				.extracting(CourseResponse::id)
				.containsExactly(alphaHigh.getId(), zebra.getId());
	}

	@Test
	void listCourses_reconstructsStep1OrderWhenSecondStageReturnsShuffledEntities() {
		AcademicRuntimeService service = shufflingService();
		Graph graph = persistGraph();
		Course zebra = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000003"),
				"Zebra",
				"Z",
				graph.semester1,
				persistClass(graph.semester1, "SEZ"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		Course alphaHigh = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000002"),
				"Alpha",
				"A2",
				graph.semester1,
				persistClass(graph.semester1, "SEA2"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		Course alphaLow = persistCourse(
				UUID.fromString("00000000-0000-4000-8000-000000000001"),
				"Alpha",
				"A1",
				graph.semester1,
				persistClass(graph.semester1, "SEA1"),
				graph.subject1,
				graph.syllabus1,
				graph.lecturer1,
				null);
		entityManager.flush();
		entityManager.clear();

		Page<UUID> idPage = courses.findPageIds(null, null, null, null, PageRequest.of(0, 50));
		List<UUID> step1 = idPage.getContent();
		assertThat(step1).containsExactly(alphaLow.getId(), alphaHigh.getId(), zebra.getId());
		List<UUID> reversed = new ArrayList<>(step1);
		Collections.reverse(reversed);
		List<UUID> fetchedIds = courses.findFetchedByIdIn(reversed).stream().map(Course::getId).toList();
		assertThat(fetchedIds).containsExactlyInAnyOrderElementsOf(step1);

		CoursePageResponse page = service.listCourses(null, null, null, null, 0, 50);
		assertThat(page.items()).extracting(CourseResponse::id).containsExactlyElementsOf(step1);
	}

	@Test
	void listCourses_rejectsInvalidPaging() {
		AcademicRuntimeService service = service();
		assertInvalid(() -> service.listCourses(null, null, null, null, -1, 50));
		assertInvalid(() -> service.listCourses(null, null, null, null, 0, 0));
		assertInvalid(() -> service.listCourses(null, null, null, null, 0, 201));
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

	private AcademicRuntimeService shufflingService() {
		AcademicRuntimeStore shuffling =
				Mockito.mock(AcademicRuntimeStore.class, AdditionalAnswers.delegatesTo(store));
		Mockito.doAnswer(invocation -> {
					List<Course> rows =
							new ArrayList<>(store.findCoursesFetchedByIdIn(invocation.getArgument(0)));
					Collections.reverse(rows);
					return rows;
				})
				.when(shuffling)
				.findCoursesFetchedByIdIn(Mockito.any());
		return new AcademicRuntimeService(shuffling, Mockito.mock(AuditService.class));
	}

	private Graph persistGraph() {
		Semester semester1 = persistSemester("FA26", "Fall 2026");
		Semester semester2 = persistSemester("SP27", "Spring 2027");
		Subject subject1 = persistSubject("SWP391", "Software Project");
		Subject subject2 = persistSubject("SWT301", "Software Testing");
		SubjectSyllabusVersion syllabus1 = persistSyllabus(subject1, "2026-v1");
		SubjectSyllabusVersion syllabus2 = persistSyllabus(subject2, "2026-v1");
		LecturerProfile lecturer1 = persistLecturer("lecturer1@saga.local", "Lecturer One");
		LecturerProfile lecturer2 = persistLecturer("lecturer2@saga.local", "Lecturer Two");
		return new Graph(semester1, semester2, subject1, subject2, syllabus1, syllabus2, lecturer1, lecturer2);
	}

	private Semester persistSemester(String code, String name) {
		Semester semester = new Semester();
		semester.setCode(code);
		semester.setName(name);
		entityManager.persist(semester);
		return semester;
	}

	private AcademicClass persistClass(Semester semester, String classCode) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode(classCode);
		academicClass.setName(classCode);
		entityManager.persist(academicClass);
		return academicClass;
	}

	private Subject persistSubject(String code, String name) {
		Subject subject = new Subject();
		subject.setSubjectCode(code);
		subject.setName(name);
		subject.setStatus(SubjectStatus.ACTIVE);
		entityManager.persist(subject);
		return subject;
	}

	private SubjectSyllabusVersion persistSyllabus(Subject subject, String label) {
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel(label);
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(syllabus);
		return syllabus;
	}

	private LecturerProfile persistLecturer(String email, String fullName) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(fullName);
		account.setAccountRole(AccountRole.LECTURER);
		account.setAccountStatus(AccountStatus.ACTIVE);
		entityManager.persist(account);
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		entityManager.persist(profile);
		return profile;
	}

	private Course persistCourse(
			UUID id,
			String name,
			String courseCode,
			Semester semester,
			AcademicClass academicClass,
			Subject subject,
			SubjectSyllabusVersion syllabus,
			LecturerProfile lecturer,
			LocalDateTime deletedAt) {
		Course course = new Course();
		if (id != null) {
			course.setId(id);
		}
		course.setName(name);
		course.setCourseCode(courseCode);
		course.setSemester(semester);
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSyllabusVersion(syllabus);
		course.setInstructor(lecturer);
		course.setDeletedAt(deletedAt);
		entityManager.persist(course);
		return course;
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private record Graph(
			Semester semester1,
			Semester semester2,
			Subject subject1,
			Subject subject2,
			SubjectSyllabusVersion syllabus1,
			SubjectSyllabusVersion syllabus2,
			LecturerProfile lecturer1,
			LecturerProfile lecturer2) {}
}

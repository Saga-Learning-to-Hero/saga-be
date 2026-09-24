package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.ai.LecturerCourseAcademicClassificationPageResponse;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.repository.AiAcademicClassificationRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.persistence.EntityManager;
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

/**
 * N+1 guard for the course-level Academic Classification read: the statement count for a full
 * page must not grow with the number of rows on the page (projection query + count query +
 * constant authorization lookups).
 */
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
class LecturerCourseAcademicClassificationQueryCountTest {

	private static final int ROWS = 30;

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
				"org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration",
				"org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
				"org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration"
			})
	@EntityScan(basePackages = "com.saga.be.entity")
	@EnableJpaRepositories(basePackages = "com.saga.be.repository")
	static class TxSlice {}

	@Autowired private EntityManager em;
	@Autowired private AiAcademicClassificationRepository classifications;
	@Autowired private CourseRepository courses;
	@Autowired private LecturerProfileRepository lecturers;

	private LecturerCourseAcademicClassificationReadService service;
	private UserAccount lecturer;
	private Course course;

	@BeforeEach
	void seed() {
		CourseAcademicClassificationFixture fx = new CourseAcademicClassificationFixture(em);
		service = new LecturerCourseAcademicClassificationReadService(
				classifications, new LecturerCourseAuthorization(courses, lecturers));
		lecturer = fx.account(AccountRole.LECTURER);
		course = fx.course(fx.lecturer(lecturer));
		SubjectSyllabusVersion syllabus = course.getSyllabusVersion();
		SyllabusPhase phase = fx.phase(syllabus, "P1", "Inception");
		SyllabusExpectedDeliverable deliverable = fx.deliverable(syllabus, phase, "D1", "SRS");
		Project first = fx.project(course, "First");
		Project second = fx.project(course, "Second");
		fx.team(course, first, 1, "One");
		fx.team(course, second, 2, "Two");
		for (int i = 0; i < ROWS; i++) {
			Project project = i % 2 == 0 ? first : second;
			if (i % 3 == 0) {
				Task task = fx.task(project, "K-" + i, "Task " + i);
				fx.classification(project, AiArtifactType.TASK, task.getId(), syllabus,
						AiAcademicTargetType.PHASE, phase, null,
						AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI, null);
			} else {
				GitCommit commit = fx.commit(project, String.format("%040d", i), "commit " + i);
				fx.classification(project, AiArtifactType.COMMIT, commit.getId(), syllabus,
						AiAcademicTargetType.EXPECTED_DELIVERABLE, null, deliverable,
						AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI, null);
			}
		}
		em.flush();
		em.clear();
	}

	@Test
	void statementCountIsIndependentOfRowsPerPage() {
		long small = statementsFor(2);
		long large = statementsFor(25);

		assertThat(large).isEqualTo(small);
		// authorization (course fetch + lecturer profile) + page projection + count
		assertThat(large).isLessThanOrEqualTo(4);
	}

	@Test
	void pageRowsAreProjectedNotLoadedAsEntities() {
		Statistics stats = statistics();
		stats.clear();

		LecturerCourseAcademicClassificationPageResponse page =
				service.list(lecturer, course.getId(), null, null, null, null, 0, 25);

		assertThat(page.items()).hasSize(25);
		assertThat(page.total()).isEqualTo(ROWS);
		assertThat(stats.getCollectionFetchCount()).isZero();
		// Only the authorization lookups materialize entities; classification rows never do.
		assertThat(stats.getEntityStatistics(
						com.saga.be.entity.ai.AiAcademicClassification.class.getName()).getLoadCount())
				.isZero();
		assertThat(stats.getEntityStatistics(Task.class.getName()).getLoadCount()).isZero();
		assertThat(stats.getEntityStatistics(GitCommit.class.getName()).getLoadCount()).isZero();
		assertThat(stats.getEntityStatistics(Project.class.getName()).getLoadCount()).isZero();
	}

	private long statementsFor(int size) {
		em.clear();
		Statistics stats = statistics();
		stats.clear();
		LecturerCourseAcademicClassificationPageResponse page =
				service.list(lecturer, course.getId(), null, null, null, null, 0, size);
		assertThat(page.items()).hasSize(size);
		assertThat(page.total()).isEqualTo(ROWS);
		long statements = stats.getPrepareStatementCount();
		System.out.println("course academic classification page size=" + size + " statements=" + statements);
		return statements;
	}

	private Statistics statistics() {
		Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}
}

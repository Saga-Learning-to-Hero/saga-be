package com.saga.be.service.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.ai.LecturerCourseAcademicClassificationPageResponse;
import com.saga.be.dto.ai.LecturerCourseAcademicClassificationResponse;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAcademicClassification;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AiAcademicClassificationRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.LecturerCourseAcademicClassificationRow;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;

/**
 * Opt-in real-MySQL proof for GET /api/lecturer/courses/{courseId}/ai/academic-classifications:
 * runs the real service + AiAcademicClassificationRepository#findCoursePage (content query and
 * countQuery) against a disposable MySQL 8.4 database migrated by Flyway through V33, where the
 * real NOT NULL / FK / CHECK constraints of V28 apply. Each test rolls back. See
 * {@code scripts/verify_course_academic_classification_mysql.sh}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfSystemProperty(named = "saga.verify.mysql", matches = "true")
@TestPropertySource(
		properties = {
			"spring.datasource.url=${saga.verify.mysql.url}",
			"spring.datasource.username=${SAGA_VERIFY_MYSQL_USERNAME:${saga.verify.mysql.username:root}}",
			"spring.datasource.password=${SAGA_VERIFY_MYSQL_PASSWORD:${saga.verify.mysql.password:}}",
			"spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
			"spring.jpa.hibernate.ddl-auto=validate",
			"spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
			"spring.jpa.properties.hibernate.generate_statistics=true",
			"spring.flyway.enabled=false",
			"saga.auth.bootstrap-admin.enabled=false"
		})
class LecturerCourseAcademicClassificationMysqlIT {

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
	private CourseAcademicClassificationFixture fx;

	private UserAccount lecturerA;
	private UserAccount lecturerB;
	private Course courseA;
	private Project projectA1;
	private Project projectA2;
	private Project projectB1;
	private Team teamA1;
	private Team teamA2;
	private Team teamB1;
	private SyllabusExpectedDeliverable deliverableA;
	private Task taskA1;

	private AiAcademicClassification proposedTaskA1;
	private AiAcademicClassification confirmedCommitA1;
	private AiAcademicClassification rejectedTaskA2;
	private AiAcademicClassification correctedOriginalCommitA2;
	private AiAcademicClassification humanCorrectionCommitA2;
	private AiAcademicClassification courseBTask;
	private AiAcademicClassification courseBCommit;

	@BeforeEach
	void seed() {
		fx = new CourseAcademicClassificationFixture(em);
		service = new LecturerCourseAcademicClassificationReadService(
				classifications, new LecturerCourseAuthorization(courses, lecturers));

		lecturerA = fx.account(AccountRole.LECTURER);
		lecturerB = fx.account(AccountRole.LECTURER);
		courseA = fx.course(fx.lecturer(lecturerA));
		Course courseB = fx.course(fx.lecturer(lecturerB));
		SubjectSyllabusVersion syllabusA = courseA.getSyllabusVersion();
		SubjectSyllabusVersion syllabusB = courseB.getSyllabusVersion();
		SyllabusPhase phaseA = fx.phase(syllabusA, "P1", "Inception");
		deliverableA = fx.deliverable(syllabusA, phaseA, "D1", "SRS document");
		SyllabusPhase phaseB = fx.phase(syllabusB, "PB", "Course B phase");

		projectA1 = fx.project(courseA, "Alpha Project");
		projectA2 = fx.project(courseA, "Beta Project");
		projectB1 = fx.project(courseB, "Other Course Project");
		teamA1 = fx.team(courseA, projectA1, 1, "Alpha");
		teamA2 = fx.team(courseA, projectA2, 2, "Beta");
		teamB1 = fx.team(courseB, projectB1, 1, "Other");

		taskA1 = fx.task(projectA1, "SAGA-1", "Login screen");
		GitCommit commitA1 = fx.commit(projectA1, "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", "feat: login");
		Task taskA2 = fx.task(projectA2, "BETA-7", "Payment flow");
		GitCommit commitA2 = fx.commit(projectA2, "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", "fix: payment");
		Task taskB1 = fx.task(projectB1, "OTH-1", "Other course task");
		GitCommit commitB1 = fx.commit(projectB1, "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", "chore: other");

		proposedTaskA1 = ai(projectA1, AiArtifactType.TASK, taskA1.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null, AiAcademicClassificationStatus.PROPOSED);
		confirmedCommitA1 = ai(projectA1, AiArtifactType.COMMIT, commitA1.getId(), syllabusA,
				AiAcademicTargetType.EXPECTED_DELIVERABLE, null, deliverableA, AiAcademicClassificationStatus.CONFIRMED);
		rejectedTaskA2 = ai(projectA2, AiArtifactType.TASK, taskA2.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null, AiAcademicClassificationStatus.REJECTED);
		correctedOriginalCommitA2 = ai(projectA2, AiArtifactType.COMMIT, commitA2.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null, AiAcademicClassificationStatus.CORRECTED);
		// Exactly what AiAcademicReviewService.CORRECT persists: HUMAN/CONFIRMED, same run, pointing back.
		humanCorrectionCommitA2 = fx.classification(projectA2, AiArtifactType.COMMIT, commitA2.getId(), syllabusA,
				AiAcademicTargetType.EXPECTED_DELIVERABLE, null, deliverableA,
				AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.HUMAN, correctedOriginalCommitA2,
				correctedOriginalCommitA2.getAnalysisRun());
		courseBTask = ai(projectB1, AiArtifactType.TASK, taskB1.getId(), syllabusB,
				AiAcademicTargetType.PHASE, phaseB, null, AiAcademicClassificationStatus.CONFIRMED);
		courseBCommit = ai(projectB1, AiArtifactType.COMMIT, commitB1.getId(), syllabusB,
				AiAcademicTargetType.PHASE, phaseB, null, AiAcademicClassificationStatus.PROPOSED);

		LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
		List<AiAcademicClassification> inCreationOrder = List.of(
				proposedTaskA1, confirmedCommitA1, rejectedTaskA2, correctedOriginalCommitA2,
				humanCorrectionCommitA2, courseBTask, courseBCommit);
		for (int i = 0; i < inCreationOrder.size(); i++) {
			fx.createdAt(inCreationOrder.get(i), base.plusHours(i));
		}
		em.flush();
		em.clear();
	}

	@Test
	void databaseIsMysql84MigratedByFlywayThroughV33() {
		String version = (String) em.createNativeQuery("select version()").getSingleResult();
		System.out.println("MYSQL_VERSION=" + version);
		assertThat(version).startsWith("8.4");
		Number applied = (Number) em.createNativeQuery(
						"select count(*) from flyway_schema_history where success = 1 and version is not null")
				.getSingleResult();
		String latest = (String) em.createNativeQuery(
						"select version from flyway_schema_history where success = 1 and version is not null"
								+ " order by installed_rank desc limit 1")
				.getSingleResult();
		System.out.println("FLYWAY_APPLIED=" + applied + " FLYWAY_LATEST=V" + latest);
		assertThat(applied.intValue()).isEqualTo(33);
		assertThat(latest).isEqualTo("33");
	}

	@Test
	void contentQueryAndCountQueryBothExecuteOnMysql() {
		Statistics stats = statistics();
		stats.clear();

		// size 2 < total 5 forces Spring Data to run the countQuery as well as the content query.
		Page<LecturerCourseAcademicClassificationRow> page = classifications.findCoursePage(
				courseA.getId(), null, null, null, null, PageRequest.of(0, 2));

		assertThat(page.getContent()).hasSize(2);
		assertThat(page.getTotalElements()).isEqualTo(5);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(2);

		stats.clear();
		Page<LecturerCourseAcademicClassificationRow> filtered = classifications.findCoursePage(
				courseA.getId(), AiArtifactType.COMMIT, AiAcademicClassificationStatus.CONFIRMED,
				projectA2.getId(), teamA2.getId(), PageRequest.of(0, 1));
		assertThat(filtered.getContent()).extracting(LecturerCourseAcademicClassificationRow::id)
				.containsExactly(humanCorrectionCommitA2.getId());
		assertThat(filtered.getTotalElements()).isEqualTo(1);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(2);
	}

	@Test
	void unfilteredPageReturnsTaskAndCommitAcrossProjectsAndTeamsWithoutCourseBOrDuplicates() {
		LecturerCourseAcademicClassificationPageResponse page = list(null, null, null, null, null, null);

		assertThat(page.total()).isEqualTo(5);
		assertThat(ids(page)).containsExactlyInAnyOrder(
				proposedTaskA1.getId(), confirmedCommitA1.getId(), rejectedTaskA2.getId(),
				correctedOriginalCommitA2.getId(), humanCorrectionCommitA2.getId());
		assertThat(ids(page)).doesNotContain(courseBTask.getId(), courseBCommit.getId());
		assertThat(page.items()).extracting(row -> row.classification().artifactType()).contains("TASK", "COMMIT");
		assertThat(page.items()).extracting(LecturerCourseAcademicClassificationResponse::projectId)
				.contains(projectA1.getId(), projectA2.getId()).doesNotContain(projectB1.getId());
		assertThat(page.items()).extracting(LecturerCourseAcademicClassificationResponse::teamId)
				.contains(teamA1.getId(), teamA2.getId()).doesNotContain(teamB1.getId());
		assertThat(new HashSet<>(ids(page))).hasSize(page.items().size());
	}

	@Test
	void courseBLecturerCannotReadCourseA() {
		assertThatThrownBy(() -> service.list(lecturerB, courseA.getId(), null, null, null, null, null, null))
				.isInstanceOfSatisfying(AcademicException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				});
	}

	@Test
	void artifactTypeStatusProjectAndTeamFiltersWorkOnMysql() {
		assertThat(ids(list(AiArtifactType.TASK, null, null, null, null, null)))
				.containsExactlyInAnyOrder(proposedTaskA1.getId(), rejectedTaskA2.getId());
		assertThat(ids(list(AiArtifactType.COMMIT, null, null, null, null, null))).containsExactlyInAnyOrder(
				confirmedCommitA1.getId(), correctedOriginalCommitA2.getId(), humanCorrectionCommitA2.getId());

		assertThat(ids(list(null, AiAcademicClassificationStatus.PROPOSED, null, null, null, null)))
				.containsExactly(proposedTaskA1.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.CONFIRMED, null, null, null, null)))
				.containsExactlyInAnyOrder(confirmedCommitA1.getId(), humanCorrectionCommitA2.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.REJECTED, null, null, null, null)))
				.containsExactly(rejectedTaskA2.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.CORRECTED, null, null, null, null)))
				.containsExactly(correctedOriginalCommitA2.getId());

		assertThat(ids(list(null, null, projectA2.getId(), null, null, null))).containsExactlyInAnyOrder(
				rejectedTaskA2.getId(), correctedOriginalCommitA2.getId(), humanCorrectionCommitA2.getId());
		assertThat(ids(list(null, null, null, teamA1.getId(), null, null)))
				.containsExactlyInAnyOrder(proposedTaskA1.getId(), confirmedCommitA1.getId());
	}

	@Test
	void projectOrTeamFromCourseBCannotLeakIntoCourseA() {
		LecturerCourseAcademicClassificationPageResponse byProject = list(null, null, projectB1.getId(), null, null, null);
		LecturerCourseAcademicClassificationPageResponse byTeam = list(null, null, null, teamB1.getId(), null, null);

		assertThat(byProject.items()).isEmpty();
		assertThat(byProject.total()).isZero();
		assertThat(byTeam.items()).isEmpty();
		assertThat(byTeam.total()).isZero();
	}

	@Test
	void paginationAndCreatedAtDescIdDescOrderingAreDeterministicOnMysql() {
		LecturerCourseAcademicClassificationPageResponse all = list(null, null, null, null, 0, 200);
		List<UUID> paged = new ArrayList<>();
		for (int page = 0; page < 3; page++) {
			LecturerCourseAcademicClassificationPageResponse slice = list(null, null, null, null, page, 2);
			assertThat(slice.total()).isEqualTo(5);
			paged.addAll(ids(slice));
		}

		assertThat(paged).containsExactlyElementsOf(ids(all));
		assertThat(ids(all)).containsExactly(
				humanCorrectionCommitA2.getId(), correctedOriginalCommitA2.getId(), rejectedTaskA2.getId(),
				confirmedCommitA1.getId(), proposedTaskA1.getId());
		assertThat(all.items()).extracting(row -> row.classification().createdAt())
				.isSortedAccordingTo(Comparator.reverseOrder());

		LocalDateTime tie = LocalDateTime.of(2026, 9, 5, 12, 0);
		fx.createdAt(proposedTaskA1, tie);
		fx.createdAt(confirmedCommitA1, tie);
		em.flush();
		em.clear();
		List<UUID> expected = new ArrayList<>(List.of(proposedTaskA1.getId(), confirmedCommitA1.getId()));
		expected.sort(Comparator.comparing(UUID::toString).reversed());
		assertThat(ids(list(null, null, null, teamA1.getId(), null, null))).containsExactlyElementsOf(expected);
	}

	@Test
	void projectionMapsTaskCommitProjectTeamAndAcademicTargetFromMysql() {
		List<LecturerCourseAcademicClassificationResponse> rows = list(null, null, null, null, null, null).items();

		LecturerCourseAcademicClassificationResponse task = row(rows, proposedTaskA1);
		assertThat(task.projectName()).isEqualTo("Alpha Project");
		assertThat(task.teamId()).isEqualTo(teamA1.getId());
		assertThat(task.teamName()).isEqualTo("Alpha");
		assertThat(task.taskExternalKey()).isEqualTo("SAGA-1");
		assertThat(task.taskTitle()).isEqualTo("Login screen");
		assertThat(task.commitSha()).isNull();
		assertThat(task.commitMessage()).isNull();
		assertThat(task.classification().artifactId()).isEqualTo(taskA1.getId());
		assertThat(task.classification().targetType()).isEqualTo("PHASE");
		assertThat(task.classification().targetCode()).isEqualTo("P1");
		assertThat(task.classification().targetName()).isEqualTo("Inception");
		assertThat(task.classification().syllabusVersionId()).isEqualTo(courseA.getSyllabusVersion().getId());
		assertThat(task.classification().reviewedAt()).isNull();

		LecturerCourseAcademicClassificationResponse commit = row(rows, confirmedCommitA1);
		assertThat(commit.commitSha()).isEqualTo("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
		assertThat(commit.commitMessage()).isEqualTo("feat: login");
		assertThat(commit.taskExternalKey()).isNull();
		assertThat(commit.taskTitle()).isNull();
		assertThat(commit.projectName()).isEqualTo("Alpha Project");
		assertThat(commit.classification().targetType()).isEqualTo("EXPECTED_DELIVERABLE");
		assertThat(commit.classification().targetName()).isEqualTo("SRS document");
		assertThat(commit.classification().confidence()).isEqualTo(0.8);
		assertThat(commit.classification().reviewedAt()).isNotNull();

		assertThat(row(rows, rejectedTaskA2).teamName()).isEqualTo("Beta");
		assertThat(row(rows, rejectedTaskA2).taskExternalKey()).isEqualTo("BETA-7");
	}

	@Test
	void correctedAiRowAndHumanCorrectionKeepExistingAuthoritativeSemantics() {
		List<LecturerCourseAcademicClassificationResponse> rows = list(null, null, null, null, null, null).items();

		assertThat(row(rows, proposedTaskA1).authoritative()).isFalse();
		assertThat(row(rows, confirmedCommitA1).authoritative()).isTrue();
		assertThat(row(rows, rejectedTaskA2).authoritative()).isFalse();

		LecturerCourseAcademicClassificationResponse original = row(rows, correctedOriginalCommitA2);
		assertThat(original.classification().status()).isEqualTo("CORRECTED");
		assertThat(original.classification().provenance()).isEqualTo("AI");
		assertThat(original.classification().sourceClassificationId()).isNull();
		assertThat(original.authoritative()).isFalse();

		LecturerCourseAcademicClassificationResponse correction = row(rows, humanCorrectionCommitA2);
		assertThat(correction.classification().provenance()).isEqualTo("HUMAN");
		assertThat(correction.classification().status()).isEqualTo("CONFIRMED");
		assertThat(correction.classification().sourceClassificationId()).isEqualTo(correctedOriginalCommitA2.getId());
		assertThat(correction.classification().targetType()).isEqualTo("EXPECTED_DELIVERABLE");
		assertThat(correction.classification().targetId()).isEqualTo(deliverableA.getId());
		assertThat(correction.authoritative()).isTrue();

		// Cross-check against the existing authoritative query on the same MySQL data.
		assertThat(classifications.findAuthoritative(projectA2.getId(), AiArtifactType.COMMIT,
						correctedOriginalCommitA2.getArtifactId(), correctedOriginalCommitA2.getArtifactRevision()))
				.extracting(AiAcademicClassification::getId)
				.doesNotContain(correctedOriginalCommitA2.getId());
	}

	@Test
	void courseWithNoClassificationsReturnsAnEmptyPageOnMysql() {
		UserAccount lecturerC = fx.account(AccountRole.LECTURER);
		LecturerProfile profileC = fx.lecturer(lecturerC);
		Course courseC = fx.course(profileC);
		fx.project(courseC, "Empty Project");
		em.flush();
		em.clear();

		Statistics stats = statistics();
		stats.clear();
		Page<LecturerCourseAcademicClassificationRow> raw = classifications.findCoursePage(
				courseC.getId(), null, null, null, null, PageRequest.of(1, 10));
		assertThat(raw.getContent()).isEmpty();
		assertThat(raw.getTotalElements()).isZero();
		// page 1 of an empty result cannot be short-circuited: the countQuery must run on MySQL.
		assertThat(stats.getPrepareStatementCount()).isEqualTo(2);

		LecturerCourseAcademicClassificationPageResponse page =
				service.list(lecturerC, courseC.getId(), null, null, null, null, null, null);
		assertThat(page.items()).isEmpty();
		assertThat(page.total()).isZero();
	}

	private AiAcademicClassification ai(
			Project project,
			AiArtifactType type,
			UUID artifactId,
			SubjectSyllabusVersion syllabus,
			AiAcademicTargetType targetType,
			SyllabusPhase phase,
			SyllabusExpectedDeliverable deliverable,
			AiAcademicClassificationStatus status) {
		AiAnalysisRun run = fx.analysisRun(project, type, artifactId);
		return fx.classification(project, type, artifactId, syllabus, targetType, phase, deliverable,
				status, AiAcademicProvenance.AI, null, run);
	}

	private LecturerCourseAcademicClassificationPageResponse list(
			AiArtifactType type,
			AiAcademicClassificationStatus status,
			UUID projectId,
			UUID teamId,
			Integer page,
			Integer size) {
		return service.list(lecturerA, courseA.getId(), type, status, projectId, teamId, page, size);
	}

	private Statistics statistics() {
		Statistics stats = em.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private static List<UUID> ids(LecturerCourseAcademicClassificationPageResponse page) {
		return page.items().stream().map(row -> row.classification().id()).toList();
	}

	private static LecturerCourseAcademicClassificationResponse row(
			List<LecturerCourseAcademicClassificationResponse> rows, AiAcademicClassification classification) {
		return rows.stream()
				.filter(row -> row.classification().id().equals(classification.getId()))
				.findFirst()
				.orElseThrow();
	}
}

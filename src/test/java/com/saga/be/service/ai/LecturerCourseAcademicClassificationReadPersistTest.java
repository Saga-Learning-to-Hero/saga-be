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
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.service.lecturer.LecturerCourseAuthorization;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real-persistence proof of GET /api/lecturer/courses/{courseId}/ai/academic-classifications:
 * course-scoped in SQL, TASK + COMMIT, every project/team of the course, history semantics
 * (proposal and lecturer correction are separate rows), filters, deterministic paging.
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
class LecturerCourseAcademicClassificationReadPersistTest {

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
	private Course courseB;
	private Project projectA1;
	private Project projectA2;
	private Project projectA3NoTeam;
	private Project projectB1;
	private Team teamA1;
	private Team teamA2;
	private Team teamB1;
	private SyllabusPhase phaseA;
	private SyllabusExpectedDeliverable deliverableA;
	private Task taskA1;
	private GitCommit commitA1;

	private AiAcademicClassification proposedTaskA1;
	private AiAcademicClassification confirmedCommitA1;
	private AiAcademicClassification rejectedTaskA2;
	private AiAcademicClassification correctedOriginalCommitA2;
	private AiAcademicClassification humanCorrectionCommitA2;
	private AiAcademicClassification foreignTaskInA3;
	private AiAcademicClassification foreignCommitInA3;
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
		courseB = fx.course(fx.lecturer(lecturerB));
		SubjectSyllabusVersion syllabusA = courseA.getSyllabusVersion();
		SubjectSyllabusVersion syllabusB = courseB.getSyllabusVersion();
		phaseA = fx.phase(syllabusA, "P1", "Inception");
		deliverableA = fx.deliverable(syllabusA, phaseA, "D1", "SRS document");
		SyllabusPhase phaseB = fx.phase(syllabusB, "PB", "Course B phase");

		projectA1 = fx.project(courseA, "Alpha Project");
		projectA2 = fx.project(courseA, "Beta Project");
		projectA3NoTeam = fx.project(courseA, "Gamma Project");
		projectB1 = fx.project(courseB, "Other Course Project");
		teamA1 = fx.team(courseA, projectA1, 1, "Alpha");
		teamA2 = fx.team(courseA, projectA2, 2, "Beta");
		teamB1 = fx.team(courseB, projectB1, 1, "Other");

		taskA1 = fx.task(projectA1, "SAGA-1", "Login screen");
		commitA1 = fx.commit(projectA1, "a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1", "feat: login");
		Task taskA2 = fx.task(projectA2, "BETA-7", "Payment flow");
		GitCommit commitA2 = fx.commit(projectA2, "b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2b2", "fix: payment");
		Task taskB1 = fx.task(projectB1, "OTH-1", "Other course task");
		GitCommit commitB1 = fx.commit(projectB1, "c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3c3", "chore: other");

		proposedTaskA1 = fx.classification(projectA1, AiArtifactType.TASK, taskA1.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null,
				AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI, null);
		confirmedCommitA1 = fx.classification(projectA1, AiArtifactType.COMMIT, commitA1.getId(), syllabusA,
				AiAcademicTargetType.EXPECTED_DELIVERABLE, null, deliverableA,
				AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI, null);
		rejectedTaskA2 = fx.classification(projectA2, AiArtifactType.TASK, taskA2.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null,
				AiAcademicClassificationStatus.REJECTED, AiAcademicProvenance.AI, null);
		correctedOriginalCommitA2 = fx.classification(projectA2, AiArtifactType.COMMIT, commitA2.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null,
				AiAcademicClassificationStatus.CORRECTED, AiAcademicProvenance.AI, null);
		// Exactly what AiAcademicReviewService.CORRECT persists: a new HUMAN/CONFIRMED row pointing back.
		humanCorrectionCommitA2 = fx.classification(projectA2, AiArtifactType.COMMIT, commitA2.getId(), syllabusA,
				AiAcademicTargetType.EXPECTED_DELIVERABLE, null, deliverableA,
				AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.HUMAN, correctedOriginalCommitA2);
		// Artifacts that belong to ANOTHER project: display columns must stay null (guard).
		foreignTaskInA3 = fx.classification(projectA3NoTeam, AiArtifactType.TASK, taskA1.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null,
				AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI, null);
		foreignCommitInA3 = fx.classification(projectA3NoTeam, AiArtifactType.COMMIT, commitA1.getId(), syllabusA,
				AiAcademicTargetType.PHASE, phaseA, null,
				AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI, null);

		courseBTask = fx.classification(projectB1, AiArtifactType.TASK, taskB1.getId(), syllabusB,
				AiAcademicTargetType.PHASE, phaseB, null,
				AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI, null);
		courseBCommit = fx.classification(projectB1, AiArtifactType.COMMIT, commitB1.getId(), syllabusB,
				AiAcademicTargetType.PHASE, phaseB, null,
				AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI, null);

		LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
		List<AiAcademicClassification> inCreationOrder = List.of(
				proposedTaskA1, confirmedCommitA1, rejectedTaskA2, correctedOriginalCommitA2,
				humanCorrectionCommitA2, foreignTaskInA3, foreignCommitInA3, courseBTask, courseBCommit);
		for (int i = 0; i < inCreationOrder.size(); i++) {
			fx.createdAt(inCreationOrder.get(i), base.plusHours(i));
		}
		em.flush();
		em.clear();
	}

	@Test
	void assignedLecturerSeesTaskAndCommitAcrossEveryProjectAndTeamOfTheCourseOnly() {
		LecturerCourseAcademicClassificationPageResponse page = list(null, null, null, null, null, null);

		assertThat(page.total()).isEqualTo(7);
		assertThat(ids(page)).containsExactlyInAnyOrder(
				proposedTaskA1.getId(), confirmedCommitA1.getId(), rejectedTaskA2.getId(),
				correctedOriginalCommitA2.getId(), humanCorrectionCommitA2.getId(),
				foreignTaskInA3.getId(), foreignCommitInA3.getId());
		assertThat(ids(page)).doesNotContain(courseBTask.getId(), courseBCommit.getId());
		assertThat(page.items()).extracting(row -> row.classification().artifactType())
				.contains("TASK", "COMMIT");
		assertThat(page.items()).extracting(LecturerCourseAcademicClassificationResponse::projectId)
				.contains(projectA1.getId(), projectA2.getId(), projectA3NoTeam.getId())
				.doesNotContain(projectB1.getId());
		assertThat(page.items()).extracting(LecturerCourseAcademicClassificationResponse::teamId)
				.contains(teamA1.getId(), teamA2.getId())
				.doesNotContain(teamB1.getId());
		assertThat(new HashSet<>(ids(page))).hasSize(page.items().size());
	}

	@Test
	void otherCourseLecturerAdminAndStudentAreForbiddenAndUnknownCourseIsNotFound() {
		UserAccount admin = fx.account(AccountRole.ADMIN);
		UserAccount student = fx.account(AccountRole.STUDENT);

		assertForbidden(lecturerB, courseA.getId());
		assertForbidden(admin, courseA.getId());
		assertForbidden(student, courseA.getId());
		assertThatThrownBy(() -> service.list(lecturerA, UUID.randomUUID(), null, null, null, null, null, null))
				.isInstanceOfSatisfying(AcademicException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.COURSE_NOT_FOUND);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
				});
	}

	@Test
	void artifactTypeFilterUsesTheExistingEnum() {
		assertThat(ids(list(AiArtifactType.TASK, null, null, null, null, null))).containsExactlyInAnyOrder(
				proposedTaskA1.getId(), rejectedTaskA2.getId(), foreignTaskInA3.getId());
		assertThat(ids(list(AiArtifactType.COMMIT, null, null, null, null, null))).containsExactlyInAnyOrder(
				confirmedCommitA1.getId(), correctedOriginalCommitA2.getId(),
				humanCorrectionCommitA2.getId(), foreignCommitInA3.getId());
		assertThat(list(AiArtifactType.COURSE, null, null, null, null, null).total()).isZero();
	}

	@Test
	void statusFilterUsesTheExistingReviewStatusEnum() {
		assertThat(ids(list(null, AiAcademicClassificationStatus.PROPOSED, null, null, null, null)))
				.containsExactlyInAnyOrder(proposedTaskA1.getId(), foreignTaskInA3.getId(), foreignCommitInA3.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.CONFIRMED, null, null, null, null)))
				.containsExactlyInAnyOrder(confirmedCommitA1.getId(), humanCorrectionCommitA2.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.REJECTED, null, null, null, null)))
				.containsExactly(rejectedTaskA2.getId());
		assertThat(ids(list(null, AiAcademicClassificationStatus.CORRECTED, null, null, null, null)))
				.containsExactly(correctedOriginalCommitA2.getId());
	}

	@Test
	void projectAndTeamFiltersScopeWithinTheCourseAndCombineWithOtherFilters() {
		assertThat(ids(list(null, null, projectA2.getId(), null, null, null))).containsExactlyInAnyOrder(
				rejectedTaskA2.getId(), correctedOriginalCommitA2.getId(), humanCorrectionCommitA2.getId());
		assertThat(ids(list(null, null, null, teamA1.getId(), null, null)))
				.containsExactlyInAnyOrder(proposedTaskA1.getId(), confirmedCommitA1.getId());
		assertThat(ids(list(AiArtifactType.COMMIT, AiAcademicClassificationStatus.CONFIRMED, projectA2.getId(),
						teamA2.getId(), null, null)))
				.containsExactly(humanCorrectionCommitA2.getId());
		// team filter of one project combined with another project's id matches nothing
		assertThat(list(null, null, projectA1.getId(), teamA2.getId(), null, null).total()).isZero();
	}

	@Test
	void projectOrTeamIdFromAnotherCourseCannotLeakAndReturnsAnEmptyPage() {
		LecturerCourseAcademicClassificationPageResponse byProject =
				list(null, null, projectB1.getId(), null, null, null);
		LecturerCourseAcademicClassificationPageResponse byTeam =
				list(null, null, null, teamB1.getId(), null, null);
		LecturerCourseAcademicClassificationPageResponse byUnknown =
				list(null, null, UUID.randomUUID(), UUID.randomUUID(), null, null);

		assertThat(byProject.items()).isEmpty();
		assertThat(byProject.total()).isZero();
		assertThat(byTeam.items()).isEmpty();
		assertThat(byTeam.total()).isZero();
		assertThat(byUnknown.total()).isZero();
	}

	@Test
	void reviewSemanticsKeepProposalAndAuthoritativeCorrectionDistinct() {
		List<LecturerCourseAcademicClassificationResponse> rows = list(null, null, null, null, null, null).items();

		LecturerCourseAcademicClassificationResponse proposed = row(rows, proposedTaskA1);
		assertThat(proposed.classification().status()).isEqualTo("PROPOSED");
		assertThat(proposed.classification().provenance()).isEqualTo("AI");
		assertThat(proposed.authoritative()).isFalse();
		assertThat(proposed.classification().reviewedAt()).isNull();

		assertThat(row(rows, confirmedCommitA1).authoritative()).isTrue();
		assertThat(row(rows, rejectedTaskA2).authoritative()).isFalse();

		LecturerCourseAcademicClassificationResponse original = row(rows, correctedOriginalCommitA2);
		assertThat(original.classification().status()).isEqualTo("CORRECTED");
		assertThat(original.classification().provenance()).isEqualTo("AI");
		assertThat(original.authoritative()).isFalse();
		assertThat(original.classification().targetType()).isEqualTo("PHASE");

		LecturerCourseAcademicClassificationResponse correction = row(rows, humanCorrectionCommitA2);
		assertThat(correction.classification().provenance()).isEqualTo("HUMAN");
		assertThat(correction.classification().status()).isEqualTo("CONFIRMED");
		assertThat(correction.authoritative()).isTrue();
		assertThat(correction.classification().sourceClassificationId()).isEqualTo(correctedOriginalCommitA2.getId());
		// The authoritative answer is the lecturer's corrected target, not the superseded AI one.
		assertThat(correction.classification().targetType()).isEqualTo("EXPECTED_DELIVERABLE");
		assertThat(correction.classification().targetId()).isEqualTo(deliverableA.getId());
		assertThat(correction.classification().targetCode()).isEqualTo("D1");
	}

	@Test
	void rowsCarryProjectTeamArtifactAndAcademicContextWithoutPerRowLookups() {
		List<LecturerCourseAcademicClassificationResponse> rows = list(null, null, null, null, null, null).items();

		LecturerCourseAcademicClassificationResponse task = row(rows, proposedTaskA1);
		assertThat(task.projectName()).isEqualTo("Alpha Project");
		assertThat(task.teamName()).isEqualTo("Alpha");
		assertThat(task.taskExternalKey()).isEqualTo("SAGA-1");
		assertThat(task.taskTitle()).isEqualTo("Login screen");
		assertThat(task.commitSha()).isNull();
		assertThat(task.commitMessage()).isNull();
		assertThat(task.classification().artifactId()).isEqualTo(taskA1.getId());
		assertThat(task.classification().targetId()).isEqualTo(phaseA.getId());
		assertThat(task.classification().targetCode()).isEqualTo("P1");
		assertThat(task.classification().targetName()).isEqualTo("Inception");
		assertThat(task.classification().syllabusVersionId()).isEqualTo(courseA.getSyllabusVersion().getId());

		LecturerCourseAcademicClassificationResponse commit = row(rows, confirmedCommitA1);
		assertThat(commit.commitSha()).isEqualTo("a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1a1");
		assertThat(commit.commitMessage()).isEqualTo("feat: login");
		assertThat(commit.taskExternalKey()).isNull();
		assertThat(commit.taskTitle()).isNull();
		assertThat(commit.classification().targetName()).isEqualTo("SRS document");
		assertThat(commit.teamName()).isEqualTo("Alpha");
	}

	@Test
	void projectWithoutTeamStillAppearsAndArtifactOfAnotherProjectIsNotDisplayed() {
		List<LecturerCourseAcademicClassificationResponse> rows = list(null, null, null, null, null, null).items();

		LecturerCourseAcademicClassificationResponse foreignTask = row(rows, foreignTaskInA3);
		assertThat(foreignTask.projectName()).isEqualTo("Gamma Project");
		assertThat(foreignTask.teamId()).isNull();
		assertThat(foreignTask.teamName()).isNull();
		assertThat(foreignTask.taskExternalKey()).isNull();
		assertThat(foreignTask.taskTitle()).isNull();

		LecturerCourseAcademicClassificationResponse foreignCommit = row(rows, foreignCommitInA3);
		assertThat(foreignCommit.commitSha()).isNull();
		assertThat(foreignCommit.commitMessage()).isNull();
	}

	@Test
	void paginationIsDeterministicNewestFirstWithoutDuplicatesOrGaps() {
		LecturerCourseAcademicClassificationPageResponse all = list(null, null, null, null, 0, 200);
		List<UUID> paged = new ArrayList<>();
		for (int page = 0; page < 4; page++) {
			LecturerCourseAcademicClassificationPageResponse slice = list(null, null, null, null, page, 2);
			assertThat(slice.page()).isEqualTo(page);
			assertThat(slice.size()).isEqualTo(2);
			assertThat(slice.total()).isEqualTo(7);
			paged.addAll(ids(slice));
		}

		assertThat(paged).containsExactlyElementsOf(ids(all));
		assertThat(new HashSet<>(paged)).hasSize(7);
		assertThat(all.items()).extracting(row -> row.classification().createdAt())
				.isSortedAccordingTo(Comparator.reverseOrder());
		assertThat(ids(all).get(0)).isEqualTo(foreignCommitInA3.getId());
		assertThat(ids(all).get(6)).isEqualTo(proposedTaskA1.getId());
	}

	@Test
	void equalCreatedAtIsBrokenByIdDescending() {
		LocalDateTime tie = LocalDateTime.of(2026, 9, 5, 12, 0);
		fx.createdAt(proposedTaskA1, tie);
		fx.createdAt(confirmedCommitA1, tie);
		em.flush();
		em.clear();

		List<UUID> teamA1Rows = ids(list(null, null, null, teamA1.getId(), null, null));

		List<UUID> expected = new ArrayList<>(List.of(proposedTaskA1.getId(), confirmedCommitA1.getId()));
		expected.sort(Comparator.comparing(UUID::toString).reversed());
		assertThat(teamA1Rows).containsExactlyElementsOf(expected);
	}

	@Test
	void defaultPagingAndOutOfRangeValuesFollowTheExistingListConvention() {
		LecturerCourseAcademicClassificationPageResponse defaults = list(null, null, null, null, null, null);
		assertThat(defaults.page()).isZero();
		assertThat(defaults.size()).isEqualTo(50);

		assertRequestInvalid(-1, 10);
		assertRequestInvalid(0, 0);
		assertRequestInvalid(0, 201);
		assertThat(list(null, null, null, null, 0, 200).size()).isEqualTo(200);
	}

	@Test
	void courseWithoutClassificationsReturnsAnEmptyPage() {
		UserAccount lecturerC = fx.account(AccountRole.LECTURER);
		LecturerProfile profileC = fx.lecturer(lecturerC);
		Course courseC = fx.course(profileC);
		fx.project(courseC, "Empty Project");
		em.flush();
		em.clear();

		LecturerCourseAcademicClassificationPageResponse page =
				service.list(lecturerC, courseC.getId(), null, null, null, null, null, null);

		assertThat(page.items()).isEmpty();
		assertThat(page.total()).isZero();
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

	private void assertForbidden(UserAccount actor, UUID courseId) {
		assertThatThrownBy(() -> service.list(actor, courseId, null, null, null, null, null, null))
				.isInstanceOfSatisfying(AcademicException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
				});
	}

	private void assertRequestInvalid(int page, int size) {
		assertThatThrownBy(() -> list(null, null, null, null, page, size))
				.isInstanceOfSatisfying(AcademicException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(AcademicErrorCode.REQUEST_INVALID);
					assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
				});
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

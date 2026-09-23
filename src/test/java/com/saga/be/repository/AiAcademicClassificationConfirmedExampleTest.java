package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.ai.AiAcademicClassification;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.project.Project;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * Real H2, real JPQL. Confirmed-example retrieval ({@code findConfirmedExamples}) backs the
 * "controlled learning" evidence fed to future academic-classification prompts, so it must be
 * impossible for it to leak an AI proposal the lecturer never reviewed (PROPOSED), one the
 * lecturer explicitly rejected (REJECTED), or the stale pre-correction row a lecturer CORRECT
 * action superseded (the CORRECTED/AI original -- only its HUMAN replacement is authoritative).
 * Proves the exact same "AI+CONFIRMED or HUMAN" set as the pre-existing {@code findAuthoritative},
 * plus that the lookup is correctly scoped by syllabus version and target type.
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
class AiAcademicClassificationConfirmedExampleTest {

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
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository academicClasses;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private SubjectSyllabusVersionRepository syllabusVersions;
	@Autowired
	private AiAnalysisRunRepository analysisRuns;
	@Autowired
	private AiAcademicClassificationRepository classifications;

	private Project project;
	private SubjectSyllabusVersion pinnedVersion;

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));
		project = projects.save(project(course));
		pinnedVersion = syllabusVersions.save(syllabusVersion(subject, "v1"));
	}

	@Test
	void onlyConfirmedAiAndHumanRowsAreReturnedNeverProposedRejectedOrTheCorrectedOriginal() {
		AiAcademicClassification proposed =
				classification(AiAcademicClassificationStatus.PROPOSED, AiAcademicProvenance.AI);
		AiAcademicClassification confirmedAi =
				classification(AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI);
		AiAcademicClassification rejected =
				classification(AiAcademicClassificationStatus.REJECTED, AiAcademicProvenance.AI);
		AiAcademicClassification correctedOriginal =
				classification(AiAcademicClassificationStatus.CORRECTED, AiAcademicProvenance.AI);
		AiAcademicClassification humanCorrection =
				classification(AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.HUMAN);
		humanCorrection.setSourceClassification(correctedOriginal);
		classifications.save(proposed);
		classifications.save(confirmedAi);
		classifications.save(rejected);
		classifications.save(correctedOriginal);
		classifications.save(humanCorrection);

		List<AiAcademicClassification> examples = classifications.findConfirmedExamples(
				pinnedVersion.getId(), AiAcademicTargetType.PHASE, PageRequest.of(0, 10));

		assertThat(examples).extracting(AiAcademicClassification::getId)
				.containsExactlyInAnyOrder(confirmedAi.getId(), humanCorrection.getId());
		assertThat(examples).extracting(AiAcademicClassification::getId)
				.doesNotContain(proposed.getId(), rejected.getId(), correctedOriginal.getId());
	}

	@Test
	void humanRowsQualifyRegardlessOfTheirOwnStatusColumn() {
		// provenance=HUMAN alone makes a row authoritative -- status on a HUMAN row is not what
		// gates it (only an AI row's status is checked against CONFIRMED).
		AiAcademicClassification humanRejectedStatus =
				classification(AiAcademicClassificationStatus.REJECTED, AiAcademicProvenance.HUMAN);
		classifications.save(humanRejectedStatus);

		List<AiAcademicClassification> examples = classifications.findConfirmedExamples(
				pinnedVersion.getId(), AiAcademicTargetType.PHASE, PageRequest.of(0, 10));

		assertThat(examples).extracting(AiAcademicClassification::getId)
				.containsExactly(humanRejectedStatus.getId());
	}

	@Test
	void resultsAreScopedToTheRequestedSyllabusVersionAndTargetType() {
		AiAcademicClassification inScope =
				classification(AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI);
		AiAcademicClassification wrongTargetType = classification(
				AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI,
				AiAcademicTargetType.EXPECTED_DELIVERABLE);
		SubjectSyllabusVersion otherVersion = syllabusVersions.save(syllabusVersion(
				subjects.findById(pinnedVersion.getSubjectId()).orElseThrow(), "v2"));
		AiAcademicClassification wrongVersion =
				classification(otherVersion, AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI,
						AiAcademicTargetType.PHASE);
		classifications.save(inScope);
		classifications.save(wrongTargetType);
		classifications.save(wrongVersion);

		List<AiAcademicClassification> examples = classifications.findConfirmedExamples(
				pinnedVersion.getId(), AiAcademicTargetType.PHASE, PageRequest.of(0, 10));

		assertThat(examples).extracting(AiAcademicClassification::getId)
				.containsExactly(inScope.getId());
	}

	@Test
	void pageableCapsTheReturnedExampleCount() {
		for (int i = 0; i < 5; i++) {
			classifications.save(classification(AiAcademicClassificationStatus.CONFIRMED, AiAcademicProvenance.AI));
		}

		List<AiAcademicClassification> examples = classifications.findConfirmedExamples(
				pinnedVersion.getId(), AiAcademicTargetType.PHASE, PageRequest.of(0, 3));

		assertThat(examples).hasSize(3);
	}

	private AiAcademicClassification classification(
			AiAcademicClassificationStatus status, AiAcademicProvenance provenance) {
		return classification(pinnedVersion, status, provenance, AiAcademicTargetType.PHASE);
	}

	private AiAcademicClassification classification(
			AiAcademicClassificationStatus status,
			AiAcademicProvenance provenance,
			AiAcademicTargetType targetType) {
		return classification(pinnedVersion, status, provenance, targetType);
	}

	private AiAcademicClassification classification(
			SubjectSyllabusVersion version,
			AiAcademicClassificationStatus status,
			AiAcademicProvenance provenance,
			AiAcademicTargetType targetType) {
		AiAnalysisRun run = analysisRuns.save(analysisRun());
		AiAcademicClassification classification = new AiAcademicClassification();
		classification.setProject(project);
		classification.setAnalysisRun(run);
		classification.setArtifactType(AiArtifactType.COMMIT);
		classification.setArtifactId(UUID.randomUUID());
		classification.setArtifactRevision("rev-" + UUID.randomUUID());
		classification.setSyllabusVersion(version);
		classification.setTargetType(targetType);
		classification.setConfidence(0.9);
		classification.setStatus(status);
		classification.setProvenance(provenance);
		return classification;
	}

	private AiAnalysisRun analysisRun() {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project);
		run.setArtifactType(AiArtifactType.COMMIT);
		run.setArtifactId(UUID.randomUUID());
		run.setArtifactRevision("rev-" + UUID.randomUUID());
		run.setAnalysisType(AiAnalysisType.ACADEMIC_CLASSIFICATION);
		run.setStatus(AiAnalysisStatus.COMPLETED);
		run.setEvidenceHash("hash-" + UUID.randomUUID());
		run.setPolicyVersion("policy-v1");
		run.setPromptVersion("academic-classification-v1");
		run.setSchemaVersion("schema-v1");
		run.setProviderConfigHash("cfg-hash");
		run.setIdempotencyKey("idem-" + UUID.randomUUID());
		return run;
	}

	private static Semester semester() {
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Test Semester");
		return semester;
	}

	private static AcademicClass academicClass(Semester semester) {
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + (int) (Math.random() * 100000));
		academicClass.setName("Test Class");
		return academicClass;
	}

	private static Subject subject() {
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		return subject;
	}

	private static Course course(AcademicClass academicClass, Subject subject, Semester semester) {
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		return course;
	}

	private static Project project(Course course) {
		Project project = new Project();
		project.setName("Project");
		project.setCourse(course);
		return project;
	}

	private static SubjectSyllabusVersion syllabusVersion(Subject subject, String versionLabel) {
		SubjectSyllabusVersion version = new SubjectSyllabusVersion();
		version.setSubject(subject);
		version.setVersionLabel(versionLabel);
		version.setStatus(SyllabusStatus.PUBLISHED);
		return version;
	}
}

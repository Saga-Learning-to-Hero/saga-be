package com.saga.be.service.ai;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.ai.AiAcademicClassification;
import com.saga.be.entity.ai.AiAnalysisRun;
import com.saga.be.entity.enums.AiAnalysisStatus;
import com.saga.be.entity.enums.AiAnalysisType;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.AiAcademicClassificationStatus;
import com.saga.be.entity.enums.AiAcademicProvenance;
import com.saga.be.entity.enums.AiAcademicTargetType;
import com.saga.be.entity.enums.AiArtifactType;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;

/** Real-entity seeding for course-level Academic Classification read tests (H2, ddl create-drop). */
final class CourseAcademicClassificationFixture {

	private final EntityManager em;

	CourseAcademicClassificationFixture(EntityManager em) {
		this.em = em;
	}

	UserAccount account(AccountRole role) {
		UserAccount account = new UserAccount();
		account.setEmail(role.name().toLowerCase() + "-" + UUID.randomUUID() + "@fe.edu.vn");
		account.setFullName(role.name());
		account.setAccountRole(role);
		account.setAccountStatus(AccountStatus.ACTIVE);
		em.persist(account);
		return account;
	}

	LecturerProfile lecturer(UserAccount account) {
		LecturerProfile profile = new LecturerProfile();
		profile.setUserAccount(account);
		em.persist(profile);
		return profile;
	}

	Course course(LecturerProfile instructor) {
		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Semester semester = new Semester();
		semester.setCode("FA" + suffix);
		semester.setName("Fall");
		em.persist(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + suffix);
		academicClass.setName("SE" + suffix);
		em.persist(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + suffix);
		subject.setName("Software Project");
		subject.setStatus(SubjectStatus.ACTIVE);
		em.persist(subject);
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("1.0");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		em.persist(syllabus);
		Course course = new Course();
		course.setName("SWP " + suffix);
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		course.setInstructor(instructor);
		em.persist(course);
		return course;
	}

	SyllabusPhase phase(SubjectSyllabusVersion syllabus, String code, String name) {
		SyllabusPhase phase = new SyllabusPhase();
		phase.setSyllabusVersionId(syllabus.getId());
		phase.setCode(code);
		phase.setName(name);
		phase.setOrderIndex(1);
		em.persist(phase);
		return phase;
	}

	SyllabusExpectedDeliverable deliverable(
			SubjectSyllabusVersion syllabus, SyllabusPhase phase, String code, String name) {
		SyllabusExpectedDeliverable deliverable = new SyllabusExpectedDeliverable();
		deliverable.setSyllabusVersionId(syllabus.getId());
		deliverable.setPhaseId(phase.getId());
		deliverable.setCode(code);
		deliverable.setName(name);
		deliverable.setOrderIndex(1);
		em.persist(deliverable);
		return deliverable;
	}

	Project project(Course course, String name) {
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		em.persist(project);
		return project;
	}

	Team team(Course course, Project project, int teamNo, String name) {
		Team team = new Team();
		team.setCourse(course);
		team.setProject(project);
		team.setTeamNo(teamNo);
		team.setName(name);
		em.persist(team);
		return team;
	}

	Task task(Project project, String externalKey, String title) {
		JiraIntegration integration = new JiraIntegration();
		integration.setProject(project);
		integration.setCloudId("cloud-" + UUID.randomUUID());
		integration.setJiraProjectId("10000");
		integration.setProjectKey("SAGA");
		integration.setConnectionStatus(IntegrationStatus.ACTIVE);
		integration.setConsecutiveFailures(0);
		em.persist(integration);
		Task task = new Task();
		task.setProject(project);
		task.setJiraIntegration(integration);
		task.setTitle(title);
		task.setStatus(TaskStatus.TODO);
		task.setExternalKey(externalKey);
		em.persist(task);
		return task;
	}

	GitCommit commit(Project project, String sha, String message) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(System.nanoTime());
		repo.setOwnerLogin("org");
		repo.setName("demo");
		repo.setFullName("org/demo-" + UUID.randomUUID());
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		em.persist(repo);
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage(message);
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		em.persist(commit);
		return commit;
	}

	AiAcademicClassification classification(
			Project project,
			AiArtifactType artifactType,
			UUID artifactId,
			SubjectSyllabusVersion syllabus,
			AiAcademicTargetType targetType,
			SyllabusPhase phase,
			SyllabusExpectedDeliverable deliverable,
			AiAcademicClassificationStatus status,
			AiAcademicProvenance provenance,
			AiAcademicClassification source) {
		return classification(project, artifactType, artifactId, syllabus, targetType, phase, deliverable,
				status, provenance, source, null);
	}

	/** Real MySQL requires analysis_run_id NOT NULL; a HUMAN correction reuses its proposal's run. */
	AiAcademicClassification classification(
			Project project,
			AiArtifactType artifactType,
			UUID artifactId,
			SubjectSyllabusVersion syllabus,
			AiAcademicTargetType targetType,
			SyllabusPhase phase,
			SyllabusExpectedDeliverable deliverable,
			AiAcademicClassificationStatus status,
			AiAcademicProvenance provenance,
			AiAcademicClassification source,
			AiAnalysisRun run) {
		AiAcademicClassification classification = new AiAcademicClassification();
		classification.setAnalysisRun(run);
		classification.setProject(project);
		classification.setArtifactType(artifactType);
		classification.setArtifactId(artifactId);
		classification.setArtifactRevision("rev-" + UUID.randomUUID());
		classification.setSyllabusVersion(syllabus);
		classification.setTargetType(targetType);
		classification.setPhase(phase);
		classification.setDeliverable(deliverable);
		classification.setConfidence(provenance == AiAcademicProvenance.HUMAN ? 1d : 0.8);
		classification.setAiSummary("summary");
		classification.setStatus(status);
		classification.setProvenance(provenance);
		classification.setSourceClassification(source);
		if (status != AiAcademicClassificationStatus.PROPOSED) {
			classification.setReviewedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
		}
		em.persist(classification);
		return classification;
	}

	AiAnalysisRun analysisRun(Project project, AiArtifactType artifactType, UUID artifactId) {
		AiAnalysisRun run = new AiAnalysisRun();
		run.setProject(project);
		run.setArtifactType(artifactType);
		run.setArtifactId(artifactId);
		run.setArtifactRevision("rev-" + UUID.randomUUID());
		run.setAnalysisType(AiAnalysisType.ACADEMIC_CLASSIFICATION);
		run.setStatus(AiAnalysisStatus.COMPLETED);
		run.setEvidenceHash("hash-" + UUID.randomUUID());
		run.setPolicyVersion("policy-v1");
		run.setPromptVersion("academic-classification-v1");
		run.setSchemaVersion("schema-v1");
		run.setProviderConfigHash("cfg-hash");
		run.setIdempotencyKey("idem-" + UUID.randomUUID());
		em.persist(run);
		return run;
	}

	/** @CreationTimestamp overrides any preset value, so pin created_at after the insert. */
	void createdAt(AiAcademicClassification classification, LocalDateTime createdAt) {
		em.flush();
		em.createNativeQuery("update ai_academic_classification set created_at = ?1 where id = ?2")
				.setParameter(1, createdAt)
				.setParameter(2, classification.getId().toString())
				.executeUpdate();
	}
}

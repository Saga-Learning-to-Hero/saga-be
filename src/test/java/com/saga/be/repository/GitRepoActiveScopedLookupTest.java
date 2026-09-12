package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import java.util.Optional;
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

/**
 * Real H2, real JPQL. Before V15, {@code findByProviderAndRepositoryId} was safe to return a
 * singular {@code Optional} only because the DB guaranteed at most one {@code git_repo} row could
 * ever exist for a given (provider, repositoryId) pair. V15 intentionally allows multiple REVOKED
 * rows to share a physical repository across projects, so a status-agnostic singular lookup over
 * those columns risks {@code IncorrectResultSizeDataAccessException} the moment two or more rows
 * match. This proves the ACTIVE-scoped replacement ({@code
 * findByConnectionStatusAndProviderAndRepositoryId}) stays safely singular regardless of how many
 * REVOKED rows share the repository, because {@code uk_git_repo_active_provider_repository} (V15)
 * still guarantees at most one ACTIVE row. It also proves (item G) that two different projects'
 * GitCommit rows for the identical SHA coexist without collision, since GitCommit uniqueness is
 * scoped by {@code repo_id}, and each project's {@code git_repo} row has its own id.
 *
 * <p>H2's create-drop schema here has no unique index at all on (provider, repository_id) --
 * {@link GitRepo} intentionally does not map V15's generated columns -- so this test can freely
 * seed multiple REVOKED rows on the same repository to exercise the query engine's cardinality
 * handling. The actual DB-level uniqueness enforcement is real-MySQL-only and verified separately.
 *
 * <p>Deliberately placed in {@code com.saga.be.repository}, matching {@code
 * JiraIntegrationActiveScopedLookupTest}, to avoid the nested {@code @SpringBootConfiguration
 * TxSlice} being auto-detected by a sibling plain {@code @SpringBootTest}.
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
class GitRepoActiveScopedLookupTest {

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
	private GitRepoRepository gitRepos;
	@Autowired
	private GitCommitRepository gitCommits;

	private static final long REPOSITORY_ID = 999_000_111L;

	private Project projectA;
	private Project projectB;
	private Project projectC;

	@BeforeEach
	void setUp() {
		Semester semester = semesters.save(semester());
		AcademicClass academicClass = academicClasses.save(academicClass(semester));
		Subject subject = subjects.save(subject());
		Course course = courses.save(course(academicClass, subject, semester));

		projectA = projects.save(project(course, "Project A"));
		projectB = projects.save(project(course, "Project B"));
		projectC = projects.save(project(course, "Project C"));
	}

	@Test
	void multipleRevokedRowsCoexistOnTheSameRepositoryWithoutAnyConstraintViolation() {
		assertThatCode(() -> {
					gitRepos.save(revokedRepo(projectA));
					gitRepos.save(revokedRepo(projectB));
				})
				.doesNotThrowAnyException();
	}

	@Test
	void activeScopedLookupFindsTheSoleActiveOwnerAmongMultipleRevokedRows() {
		gitRepos.save(revokedRepo(projectA));
		gitRepos.save(revokedRepo(projectB));
		GitRepo activeC = gitRepos.save(activeRepo(projectC));

		assertThatCode(() -> gitRepos.findByConnectionStatusAndProviderAndRepositoryId(
						IntegrationStatus.ACTIVE, GitProvider.GITHUB, REPOSITORY_ID))
				.doesNotThrowAnyException();

		Optional<GitRepo> found = gitRepos.findByConnectionStatusAndProviderAndRepositoryId(
				IntegrationStatus.ACTIVE, GitProvider.GITHUB, REPOSITORY_ID);
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(activeC.getId());
		assertThat(found.get().getProject().getId()).isEqualTo(projectC.getId());
	}

	@Test
	void webhookRoutingLookupFindsOnlyTheActiveOwnerAmongMultipleRevokedRows() {
		// The exact query ProviderWebhookProjectionService.projectGithub uses to route an incoming
		// GitHub push -- List-returning and ACTIVE-scoped already, so it stays safe (0 or 1 result
		// in practice, guaranteed by uk_git_repo_active_provider_repository) regardless of how many
		// REVOKED rows share this physical repository.
		gitRepos.save(revokedRepo(projectA));
		gitRepos.save(revokedRepo(projectB));
		GitRepo activeC = gitRepos.save(activeRepo(projectC));

		assertThatCode(() -> gitRepos.findFetchedActiveByProviderAndRepositoryId(
						GitProvider.GITHUB, REPOSITORY_ID, IntegrationStatus.ACTIVE))
				.doesNotThrowAnyException();

		java.util.List<GitRepo> routed = gitRepos.findFetchedActiveByProviderAndRepositoryId(
				GitProvider.GITHUB, REPOSITORY_ID, IntegrationStatus.ACTIVE);
		assertThat(routed).hasSize(1);
		assertThat(routed.getFirst().getId()).isEqualTo(activeC.getId());
		assertThat(routed.getFirst().getProject().getId()).isEqualTo(projectC.getId());
	}

	@Test
	void sourceBecomesAvailableAfterActiveOwnerDisconnectsAndCanBeClaimedByAnotherRow() {
		GitRepo activeC = gitRepos.save(activeRepo(projectC));
		assertThat(gitRepos.findByConnectionStatusAndProviderAndRepositoryId(
						IntegrationStatus.ACTIVE, GitProvider.GITHUB, REPOSITORY_ID))
				.isPresent();

		activeC.setConnectionStatus(IntegrationStatus.REVOKED);
		gitRepos.save(activeC);

		assertThat(gitRepos.findByConnectionStatusAndProviderAndRepositoryId(
						IntegrationStatus.ACTIVE, GitProvider.GITHUB, REPOSITORY_ID))
				.isEmpty();

		GitRepo activeA = gitRepos.save(activeRepo(projectA));

		Optional<GitRepo> found = gitRepos.findByConnectionStatusAndProviderAndRepositoryId(
				IntegrationStatus.ACTIVE, GitProvider.GITHUB, REPOSITORY_ID);
		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(activeA.getId());
	}

	@Test
	void plainSaveDoesNotSurfaceAUniqueConflictImmediatelyButFlushDoes() {
		// Demonstrates the exact risk fixed in ProjectIntegrationService.persistSelectedRepos:
		// plain JpaRepository.save() does not force the INSERT to execute immediately, so a
		// genuine unique violation can surface only later, at flush/commit time -- outside a
		// try/catch that assumed save() itself was synchronous. Uses
		// uk_git_repo_project_provider_repository (an ordinary, H2-mapped constraint) as the
		// vehicle; uk_git_repo_active_provider_repository itself is a real-MySQL-only
		// generated-column mechanism, verified separately (see scripts/verify_v15_mysql.sh).
		gitRepos.saveAndFlush(revokedRepo(projectA));
		GitRepo duplicateForSameProject = revokedRepo(projectA); // same (project, provider, repositoryId)

		assertThatCode(() -> gitRepos.save(duplicateForSameProject)).doesNotThrowAnyException();
		assertThatThrownBy(gitRepos::flush)
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void saveAndFlushSurfacesTheUniqueConflictImmediately() {
		gitRepos.saveAndFlush(revokedRepo(projectA));
		GitRepo duplicateForSameProject = revokedRepo(projectA);

		assertThatThrownBy(() -> gitRepos.saveAndFlush(duplicateForSameProject))
				.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
	}

	@Test
	void projectScopedLookupFindsOwnRowRegardlessOfStatusForSameProjectReconnect() {
		GitRepo ownRevoked = gitRepos.save(revokedRepo(projectA));

		Optional<GitRepo> found = gitRepos.findByProject_IdAndProviderAndRepositoryId(
				projectA.getId(), GitProvider.GITHUB, REPOSITORY_ID);

		assertThat(found).isPresent();
		assertThat(found.get().getId()).isEqualTo(ownRevoked.getId());
	}

	@Test
	void identicalCommitShaCoexistsIndependentlyAcrossTwoProjectsGitRepoRows() {
		GitRepo repoA = gitRepos.save(revokedRepo(projectA));
		GitRepo repoB = gitRepos.save(activeRepo(projectB));

		GitCommit commitUnderA = gitCommits.save(commit(repoA, "abc123"));
		GitCommit commitUnderB = gitCommits.save(commit(repoB, "abc123"));

		assertThat(commitUnderA.getId()).isNotEqualTo(commitUnderB.getId());
		assertThat(gitCommits.findByRepo_IdAndShaHash(repoA.getId(), "abc123")).isPresent();
		assertThat(gitCommits.findByRepo_IdAndShaHash(repoB.getId(), "abc123")).isPresent();
	}

	private static GitRepo revokedRepo(Project project) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(REPOSITORY_ID);
		repo.setFullName("saga/repo-" + UUID.randomUUID().toString().substring(0, 6));
		repo.setConnectionStatus(IntegrationStatus.REVOKED);
		repo.setConsecutiveFailures(0);
		return repo;
	}

	private static GitRepo activeRepo(Project project) {
		GitRepo repo = revokedRepo(project);
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		return repo;
	}

	private static GitCommit commit(GitRepo repo, String sha) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		return commit;
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

	private static Project project(Course course, String name) {
		Project project = new Project();
		project.setName(name);
		project.setCourse(course);
		return project;
	}
}

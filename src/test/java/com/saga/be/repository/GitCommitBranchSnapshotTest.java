package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitCommitBranch;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.project.Project;
import com.saga.be.service.projection.GitCommitBranchSnapshotService;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists exact Commit ↔ Branch reachability and proves snapshot replacement, uniqueness,
 * cascade, and GitRepo isolation. Nested TxSlice lives here so {@code @SpringBootTest} in other
 * packages does not pick it up.
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
class GitCommitBranchSnapshotTest {

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
	private PlatformTransactionManager transactionManager;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private AcademicClassRepository classes;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private ProjectRepository projects;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private GitCommitBranchRepository branches;

	private TransactionTemplate tx;
	private GitCommitBranchSnapshotService snapshots;
	private GitRepo repoA;
	private GitRepo repoB;
	private GitCommit commitA;
	private GitCommit commitB;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		snapshots = new GitCommitBranchSnapshotService(repos, commits, branches);
		tx.executeWithoutResult(status -> seed());
	}

	@Test
	void threeBranches_oneGitCommitAndThreeMemberships() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("develop", "release/v1", "main")));
		List<GitCommitBranch> rows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(rows).extracting(GitCommitBranch::getBranchName)
				.containsExactlyInAnyOrder("develop", "main", "release/v1");
		List<GitCommit> stored = tx.execute(status -> commits.findByRepo_IdAndShaHashIn(repoA.getId(), List.of(commitA.getShaHash())));
		assertThat(stored).hasSize(1);
	}

	@Test
	void successfulReplace_removesDeletedBranchAndStaleForcePushMembership() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("develop", "main", "feature/old")));
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main")));
		List<GitCommitBranch> rows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(rows).extracting(GitCommitBranch::getBranchName).containsExactly("main");
	}

	@Test
	void newBranch_isAddedOnNextSuccessfulSnapshot() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main")));
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main", "feature/x")));
		List<GitCommitBranch> rows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(rows).extracting(GitCommitBranch::getBranchName).containsExactlyInAnyOrder("feature/x", "main");
	}

	@Test
	void missingCanonicalCommit_doesNotReplacePreviousSnapshot() {
		LocalDateTime t1 = LocalDateTime.of(2026, 9, 1, 10, 0);
		tx.executeWithoutResult(status -> snapshots.replaceSnapshot(
				repos.findById(repoA.getId()).orElseThrow(), Map.of(commitA.getShaHash(), Set.of("main")), t1));
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> snapshots.replaceSnapshot(
						repos.findById(repoA.getId()).orElseThrow(),
						Map.of("ffffffffffffffffffffffffffff0001", Set.of("develop")),
						LocalDateTime.of(2026, 9, 14, 12, 0))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("canonical GitCommit rows missing");
		GitRepo stored = tx.execute(status -> repos.findById(repoA.getId()).orElseThrow());
		assertThat(stored.getBranchMembershipSyncedAt()).isEqualTo(t1);
		List<GitCommitBranch> rows =
				tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(rows).extracting(GitCommitBranch::getBranchName).containsExactly("main");
	}

	@Test
	void successfulReplace_updatesResolvedAt() {
		LocalDateTime resolved = LocalDateTime.of(2026, 9, 14, 10, 0);
		tx.executeWithoutResult(status -> snapshots.replaceSnapshot(repos.findById(repoA.getId()).orElseThrow(), Map.of(), resolved));
		LocalDateTime stored = tx.execute(status -> repos.findById(repoA.getId()).orElseThrow().getBranchMembershipSyncedAt());
		assertThat(stored).isEqualTo(resolved);
	}

	@Test
	void otherProjectsGitRepo_doesNotShareMembershipRows() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main")));
		replace(repoB, Map.of(commitB.getShaHash(), Set.of("develop")));
		List<GitCommitBranch> aRows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		List<GitCommitBranch> bRows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitB.getId())));
		assertThat(aRows).extracting(GitCommitBranch::getBranchName).containsExactly("main");
		assertThat(bRows).extracting(GitCommitBranch::getBranchName).containsExactly("develop");
	}

	@Test
	void uniqueness_rejectsDuplicateCommitAndBranch() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main")));
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			GitCommitBranch dup = new GitCommitBranch();
			dup.setCommit(commits.findById(commitA.getId()).orElseThrow());
			dup.setBranchName("main");
			branches.saveAndFlush(dup);
		})).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void caseSensitiveBranchNames_areDistinct() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("develop", "Develop")));
		List<GitCommitBranch> caseRows = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(caseRows).extracting(GitCommitBranch::getBranchName).containsExactlyInAnyOrder("Develop", "develop");
	}

	@Test
	void deletingGitCommit_cascadesMemberships() {
		replace(repoA, Map.of(commitA.getShaHash(), Set.of("main", "develop")));
		tx.executeWithoutResult(status -> commits.deleteById(commitA.getId()));
		List<GitCommitBranch> leftover = tx.execute(status -> branches.findByCommit_IdInOrderByBranchNameAsc(List.of(commitA.getId())));
		assertThat(leftover).isEmpty();
	}

	private void replace(GitRepo repo, Map<String, Set<String>> memberships) {
		Map<String, Set<String>> copy = new LinkedHashMap<>();
		memberships.forEach((sha, names) -> copy.put(sha, new LinkedHashSet<>(names)));
		tx.executeWithoutResult(status -> snapshots.replaceSnapshot(
				repos.findById(repo.getId()).orElseThrow(), copy, LocalDateTime.of(2026, 9, 14, 12, 0)));
	}

	private void seed() {
		Semester semester = new Semester();
		semester.setCode("FA" + UUID.randomUUID().toString().substring(0, 8));
		semester.setName("Fall");
		semester = semesters.save(semester);

		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("SE");
		academicClass = classes.save(academicClass);

		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);

		Course course = new Course();
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course = courses.save(course);

		Project projectA = new Project();
		projectA.setName("A");
		projectA.setCourse(course);
		projectA = projects.save(projectA);

		Project projectB = new Project();
		projectB.setName("B");
		projectB.setCourse(course);
		projectB = projects.save(projectB);

		repoA = gitRepo(projectA, 1001L, "org/a");
		repoB = gitRepo(projectB, 1001L, "org/a");
		commitA = gitCommit(repoA, "deadbeefcafefeed0001");
		commitB = gitCommit(repoB, "deadbeefcafefeed0001");
	}

	private GitRepo gitRepo(Project project, long repositoryId, String fullName) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(repositoryId);
		repo.setFullName(fullName);
		repo.setName("a");
		repo.setOwnerLogin("org");
		repo.setConnectionStatus(IntegrationStatus.ACTIVE);
		repo.setConsecutiveFailures(0);
		return repos.save(repo);
	}

	private GitCommit gitCommit(GitRepo repo, String sha) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setShaHash(sha);
		commit.setMessage("msg");
		commit.setHeadRef("main");
		commit.setCommittedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		return commits.save(commit);
	}
}

package com.saga.be.service.student;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.student.dashboard.StudentDashboardAlertResponse;
import com.saga.be.dto.student.dashboard.StudentDashboardResponse;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.Priority;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.assessment.PeerReview;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.JiraIntegration;
import com.saga.be.entity.jira.Sprint;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.JiraIntegrationRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.SprintRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
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
import org.springframework.context.annotation.Bean;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StudentDashboardPersistTest {

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
	static class TxSlice {
		@Bean
		StudentDashboardService studentDashboardService(
				CourseEnrollmentRepository enrollments,
				TeamMemberRepository members,
				JiraIntegrationRepository jiraIntegrations,
				GitRepoRepository repos,
				SprintRepository sprints,
				TaskRepository tasks,
				com.saga.be.repository.GitCommitRepository commits,
				com.saga.be.repository.TaskGitCommitLinkRepository commitLinks,
				com.saga.be.repository.PeerReviewRepository peerReviews) {
			return new StudentDashboardService(
					enrollments,
					members,
					jiraIntegrations,
					repos,
					sprints,
					tasks,
					commits,
					commitLinks,
					peerReviews,
					java.time.Clock.fixed(java.time.Instant.parse("2026-09-20T12:00:00Z"), java.time.ZoneOffset.UTC));
		}
	}

	@Autowired
	private StudentDashboardService service;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private EntityManager entityManager;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private SemesterRepository semesters;
	@Autowired
	private AcademicClassRepository classes;
	@Autowired
	private SubjectRepository subjects;
	@Autowired
	private CourseRepository courses;
	@Autowired
	private CourseEnrollmentRepository enrollments;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private com.saga.be.repository.ProjectRepository projects;
	@Autowired
	private JiraIntegrationRepository jiraIntegrations;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private SprintRepository sprints;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private com.saga.be.repository.GitCommitRepository commits;
	@Autowired
	private com.saga.be.repository.TaskGitCommitLinkRepository commitLinks;
	@Autowired
	private com.saga.be.repository.PeerReviewRepository peerReviews;

	private TransactionTemplate tx;
	private Fixture fixture;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		fixture = seed();
	}

	@Test
	void memberAndLeaderSeeDashboard() {
		StudentDashboardResponse member = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(member.student().teamRole()).isEqualTo("MEMBER");
		assertThat(member.team().membersCount()).isEqualTo(2);
		assertThat(member.team().projectId()).isEqualTo(fixture.projectId);
		assertThat(member.myMetrics().tasks().totalAssigned()).isZero();
		assertThat(member.myMetrics().tasks().completionPercent()).isNull();
		assertThat(member.myMetrics().commits().traceabilityPercent()).isNull();
		assertThat(member.myActiveTasks()).isEmpty();
		assertThat(member.recentCommits()).isEmpty();
		assertThat(member.weeklyCommits()).hasSize(3);
		assertThat(member.weeklyCommits().get(0).startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 31));
		assertThat(member.weeklyCommits()).allMatch(row -> row.commits() == 0);
		assertThat(member.actionableAlerts()).isEmpty();

		StudentDashboardResponse leader = tx.execute(status -> service.get(fixture.leaderId, fixture.courseId));
		assertThat(leader.student().teamRole()).isEqualTo("LEADER");
		assertThat(leader.course().subjectCode()).startsWith("SWP");
	}

	@Test
	void mentorRoleIsReturned() {
		tx.executeWithoutResult(status -> {
			TeamMember row = members.findByCourseEnrollment_Id(
							enrollments
									.findByStudentProfile_IdAndCourse_Id(
											students.findByUserAccount_Id(fixture.memberId).orElseThrow().getId(),
											fixture.courseId)
									.orElseThrow()
									.getId())
					.orElseThrow();
			row.setRoleInTeam(RoleInTeam.MENTOR);
			members.save(row);
		});
		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.student().teamRole()).isEqualTo("MENTOR");
	}

	@Test
	void withdrawnCompletedForeignAndDeletedCourseAreForbidden() {
		assertForbidden(fixture.withdrawnId, fixture.courseId);
		assertForbidden(fixture.completedId, fixture.courseId);
		assertForbidden(fixture.memberId, fixture.otherCourseId);
		assertForbidden(fixture.memberId, UUID.randomUUID());
		tx.executeWithoutResult(status -> {
			Course course = courses.findById(fixture.courseId).orElseThrow();
			course.setDeletedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
			courses.save(course);
		});
		assertForbidden(fixture.memberId, fixture.courseId);
	}

	@Test
	void noTeamAndTeamWithoutProjectAre200() {
		StudentDashboardResponse unassigned = tx.execute(status -> service.get(fixture.unassignedId, fixture.courseId));
		assertThat(unassigned.team()).isNull();
		assertThat(unassigned.integrations()).isNull();
		assertThat(unassigned.currentSprint()).isNull();
		assertThat(unassigned.myMetrics()).isNull();
		assertThat(unassigned.myActiveTasks()).isEmpty();
		assertThat(unassigned.recentCommits()).isEmpty();
		assertThat(unassigned.weeklyCommits()).isEmpty();
		assertThat(unassigned.actionableAlerts()).isEmpty();

		StudentDashboardResponse noProject = tx.execute(status -> service.get(fixture.noProjectMemberId, fixture.noProjectCourseId));
		assertThat(noProject.team().projectId()).isNull();
		assertThat(noProject.team().projectName()).isNull();
		assertThat(noProject.integrations()).isNull();
		assertThat(noProject.currentSprint()).isNull();
		assertThat(noProject.myMetrics()).isNull();
		assertThat(noProject.myActiveTasks()).isEmpty();
		assertThat(noProject.recentCommits()).isEmpty();
		assertThat(noProject.weeklyCommits()).isEmpty();
		assertThat(noProject.actionableAlerts()).isEmpty();
	}

	@Test
	void jiraAndGithubStatusesMatchLiveActiveRules() {
		StudentDashboardResponse empty = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(empty.integrations().jira().connected()).isFalse();
		assertThat(empty.integrations().jira().status()).isNull();
		assertThat(empty.integrations().github().connected()).isFalse();
		assertThat(empty.integrations().github().repositoryCount()).isZero();
		assertThat(empty.integrations().github().status()).isNull();

		tx.executeWithoutResult(status -> {
			JiraIntegration jira = jira(projects.findById(fixture.projectId).orElseThrow(), IntegrationStatus.ACTIVE);
			jira.setLastSuccessfulSyncAt(LocalDateTime.of(2026, 9, 1, 8, 0));
			jira.setLastSyncedAt(LocalDateTime.of(2026, 9, 2, 8, 0));
			jiraIntegrations.save(jira);
			repos.save(repo(projects.findById(fixture.projectId).orElseThrow(), 1L, "org/one", IntegrationStatus.ACTIVE));
			repos.save(repo(projects.findById(fixture.projectId).orElseThrow(), 2L, "org/two", IntegrationStatus.ACTIVE));
			repos.save(repo(projects.findById(fixture.projectId).orElseThrow(), 3L, "org/old", IntegrationStatus.REVOKED));
		});
		StudentDashboardResponse live = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(live.integrations().jira().connected()).isTrue();
		assertThat(live.integrations().jira().status()).isEqualTo("ACTIVE");
		assertThat(live.integrations().jira().lastSyncedAt()).isEqualTo(LocalDateTime.of(2026, 9, 1, 8, 0));
		assertThat(live.integrations().github().connected()).isTrue();
		assertThat(live.integrations().github().repositoryCount()).isEqualTo(2);
		assertThat(live.integrations().github().status()).isEqualTo("ACTIVE");

		tx.executeWithoutResult(status -> {
			jiraIntegrations.findByProject_Id(fixture.projectId).orElseThrow().setConnectionStatus(IntegrationStatus.REVOKED);
			for (GitRepo repo : repos.findByProject_Id(fixture.projectId)) {
				repo.setConnectionStatus(IntegrationStatus.REVOKED);
			}
		});
		StudentDashboardResponse revoked = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(revoked.integrations().jira().connected()).isFalse();
		assertThat(revoked.integrations().jira().status()).isEqualTo("REVOKED");
		assertThat(revoked.integrations().github().connected()).isFalse();
		assertThat(revoked.integrations().github().repositoryCount()).isZero();
		assertThat(revoked.integrations().github().status()).isEqualTo("REVOKED");

		tx.executeWithoutResult(status -> {
			jiraIntegrations.findByProject_Id(fixture.projectId).orElseThrow().setConnectionStatus(IntegrationStatus.CONNECTED);
			for (GitRepo repo : repos.findByProject_Id(fixture.projectId)) {
				repo.setConnectionStatus(IntegrationStatus.CONNECTED);
			}
		});
		StudentDashboardResponse legacy = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(legacy.integrations().jira().connected()).isFalse();
		assertThat(legacy.integrations().jira().status()).isEqualTo("CONNECTED");
		assertThat(legacy.integrations().github().connected()).isFalse();
		assertThat(legacy.integrations().github().status()).isEqualTo("CONNECTED");
	}

	@Test
	void githubNonActiveStatusesStayTruthful() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			repos.save(repo(project, 31L, "org/deg", IntegrationStatus.DEGRADED));
		});
		StudentDashboardResponse degraded = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(degraded.integrations().github().connected()).isFalse();
		assertThat(degraded.integrations().github().repositoryCount()).isZero();
		assertThat(degraded.integrations().github().status()).isEqualTo("DEGRADED");

		tx.executeWithoutResult(status -> {
			for (GitRepo repo : repos.findByProject_Id(fixture.projectId)) {
				repo.setConnectionStatus(IntegrationStatus.ERROR);
			}
		});
		StudentDashboardResponse error = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(error.integrations().github().connected()).isFalse();
		assertThat(error.integrations().github().status()).isEqualTo("ERROR");

		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			repos.save(repo(project, 32L, "org/conn", IntegrationStatus.CONNECTED));
		});
		StudentDashboardResponse mixed = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(mixed.integrations().github().connected()).isFalse();
		assertThat(mixed.integrations().github().repositoryCount()).isZero();
		assertThat(mixed.integrations().github().status()).isEqualTo("MIXED");
	}

	@Test
	void currentSprintSelectorAndProgressIncludeSubtasks() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			Sprint closed = sprint(jira, "closed", LocalDateTime.of(2026, 8, 1, 0, 0));
			Sprint future = sprint(jira, "future", LocalDateTime.of(2026, 10, 1, 0, 0));
			Sprint olderActive = sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0));
			Sprint newestActive = sprint(jira, "active", LocalDateTime.of(2026, 9, 10, 0, 0));
			Sprint deletedActive = sprint(jira, "active", LocalDateTime.of(2026, 9, 20, 0, 0));
			deletedActive.setDeletedAt(LocalDateTime.of(2026, 9, 21, 0, 0));
			sprints.save(closed);
			sprints.save(future);
			sprints.save(olderActive);
			sprints.save(newestActive);
			sprints.save(deletedActive);
			Task parent = task(project, newestActive, TaskStatus.DONE, null);
			tasks.save(parent);
			tasks.save(task(project, newestActive, TaskStatus.TODO, parent));
			tasks.save(task(project, newestActive, TaskStatus.DONE, parent));
			Task deleted = task(project, newestActive, TaskStatus.DONE, null);
			deleted.setDeletedAt(LocalDateTime.of(2026, 9, 11, 0, 0));
			tasks.save(deleted);
			tasks.save(task(project, olderActive, TaskStatus.DONE, null));
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.currentSprint()).isNotNull();
		assertThat(response.currentSprint().startDate()).isEqualTo(LocalDateTime.of(2026, 9, 10, 0, 0));
		assertThat(response.currentSprint().totalTasks()).isEqualTo(3);
		assertThat(response.currentSprint().completedTasks()).isEqualTo(2);
		assertThat(response.currentSprint().completionPercent()).isEqualTo((2 * 100.0) / 3);
	}

	@Test
	void closedOnlySprintIsNullAndZeroTasksPercentIsNull() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			sprints.save(sprint(jira, "closed", LocalDateTime.of(2026, 8, 1, 0, 0)));
		});
		StudentDashboardResponse closedOnly = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(closedOnly.currentSprint()).isNull();
		assertThat(closedOnly.actionableAlerts()).isEmpty();

		tx.executeWithoutResult(status -> {
			JiraIntegration jira = jiraIntegrations.findByProject_Id(fixture.projectId).orElseThrow();
			sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
		});
		StudentDashboardResponse empty = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(empty.currentSprint().totalTasks()).isZero();
		assertThat(empty.currentSprint().completionPercent()).isNull();
		assertThat(empty.actionableAlerts()).hasSize(1);
		assertThat(empty.actionableAlerts().getFirst().type()).isEqualTo("PEER_REVIEW_PENDING");
		assertThat(empty.actionableAlerts().getFirst().remainingPeers()).isEqualTo(1);
	}

	@Test
	void queryCountDoesNotGrowWithMembersReposSprintsOrTasks() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			Sprint active = sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			repos.save(repo(project, 11L, "org/a", IntegrationStatus.ACTIVE));
			tasks.save(task(project, active, TaskStatus.DONE, null));
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		long first = stats.getPrepareStatementCount();
		assertThat(first).as("Phase A+B1+B2+D1 empty personal work stays bounded").isEqualTo(15L);

		tx.executeWithoutResult(status -> {
			Course course = courses.findById(fixture.courseId).orElseThrow();
			Team team = teams.findById(fixture.teamId).orElseThrow();
			Project project = projects.findById(fixture.projectId).orElseThrow();
			JiraIntegration jira = jiraIntegrations.findByProject_Id(fixture.projectId).orElseThrow();
			Sprint active = sprints.findActiveByProject_Id(fixture.projectId).getFirst();
			for (int i = 0; i < 8; i++) {
				UserAccount extra = account("extra-" + i + "-" + UUID.randomUUID() + "@gmail.com");
				StudentProfile extraProfile = profile(extra, "SE" + i + UUID.randomUUID().toString().substring(0, 6));
				members.save(memberOf(team, course, enroll(extraProfile, course, EnrollmentStatus.ACTIVE), RoleInTeam.MEMBER));
				repos.save(repo(project, 100L + i, "org/extra-" + i, IntegrationStatus.ACTIVE));
				sprints.save(sprint(jira, "closed", LocalDateTime.of(2026, 7, i + 1, 0, 0)));
				tasks.save(task(project, active, TaskStatus.TODO, null));
			}
			entityManager.flush();
		});
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse grown = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(grown.team().membersCount()).isEqualTo(10);
		assertThat(grown.integrations().github().repositoryCount()).isEqualTo(9);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(first);
	}

	@Test
	void personalTaskMetricsAreProjectWideAndExcludeTeammateAndDeleted() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			Sprint active = sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			Sprint closed = sprints.save(sprint(jira, "closed", LocalDateTime.of(2026, 8, 1, 0, 0)));
			tasks.save(assigned(project, closed, member, TaskStatus.TODO, null, null, null, null, "SAGA-TODO"));
			tasks.save(assigned(project, null, member, TaskStatus.IN_PROGRESS, null, 5, null, null, "SAGA-IP"));
			tasks.save(assigned(project, active, member, TaskStatus.IN_REVIEW, null, 3, null, null, "SAGA-IR"));
			tasks.save(assigned(project, closed, member, TaskStatus.DONE, null, 8, null, null, "SAGA-DONE"));
			tasks.save(assigned(project, active, member, TaskStatus.BLOCKED, null, null, null, null, "SAGA-BLK"));
			tasks.save(assigned(project, active, leader, TaskStatus.TODO, null, 20, null, null, "SAGA-LEAD"));
			Task deleted = assigned(project, active, member, TaskStatus.TODO, null, 9, null, null, "SAGA-DEL");
			deleted.setDeletedAt(LocalDateTime.of(2026, 9, 2, 0, 0));
			tasks.save(deleted);
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.myMetrics().tasks().todo()).isEqualTo(1);
		assertThat(response.myMetrics().tasks().inProgress()).isEqualTo(1);
		assertThat(response.myMetrics().tasks().inReview()).isEqualTo(1);
		assertThat(response.myMetrics().tasks().done()).isEqualTo(1);
		assertThat(response.myMetrics().tasks().blocked()).isEqualTo(1);
		assertThat(response.myMetrics().tasks().totalAssigned()).isEqualTo(5);
		assertThat(response.myMetrics().tasks().completionPercent()).isEqualTo((1 * 100.0) / 5);
		assertThat(response.myMetrics().tasks().totalStoryPoints()).isEqualTo(16);
		assertThat(response.myMetrics().tasks().completedStoryPoints()).isEqualTo(8);
		assertThat(response.currentSprint().totalTasks()).isEqualTo(3);
	}

	@Test
	void personalCommitMetricsUseV23DistinctLinksAndCoalesceTimestamp() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			Course otherCourse = courses.findById(fixture.otherCourseId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			GitRepo repo = repos.save(repo(project, 21L, "org/saga", IntegrationStatus.ACTIVE));
			Project otherProject = new Project();
			otherProject.setCourse(otherCourse);
			otherProject.setName("OTHER");
			otherProject = projects.save(otherProject);
			GitRepo otherRepo = repos.save(repo(otherProject, 22L, "org/other", IntegrationStatus.ACTIVE));

			GitCommit includedNull = persistCommit(repo, member, "aa11111", null, LocalDateTime.of(2026, 9, 1, 8, 0), "null-parent");
			persistCommit(repo, member, "aa00000", 0, LocalDateTime.of(2026, 9, 2, 8, 0), "root");
			GitCommit includedOne = persistCommit(repo, member, "aa00001", 1, null, "normal");
			entityManager.flush();
			entityManager
					.createNativeQuery("update git_commit set created_at = :ts where id = :id")
					.setParameter("ts", LocalDateTime.of(2026, 9, 5, 10, 0))
					.setParameter("id", includedOne.getId().toString())
					.executeUpdate();
			persistCommit(repo, member, "aa00002", 2, LocalDateTime.of(2026, 9, 9, 8, 0), "merge");
			persistCommit(repo, leader, "bb00001", 1, LocalDateTime.of(2026, 9, 8, 8, 0), "teammate");
			persistCommit(repo, null, "cc00001", 1, LocalDateTime.of(2026, 9, 7, 8, 0), "unmapped");
			persistCommit(otherRepo, member, "dd00001", 1, LocalDateTime.of(2026, 9, 6, 8, 0), "other-project");

			Task one = tasks.save(assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-1"));
			Task two = tasks.save(assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-2"));
			Task three = tasks.save(assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-3"));
			Task deleted = assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-DEL");
			deleted.setDeletedAt(LocalDateTime.of(2026, 9, 3, 0, 0));
			tasks.save(deleted);
			link(one, includedNull);
			link(two, includedNull);
			link(three, includedNull);
			link(deleted, includedOne);
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.myMetrics().commits().totalCommits()).isEqualTo(3);
		assertThat(response.myMetrics().commits().linkedCommits()).isEqualTo(1);
		assertThat(response.myMetrics().commits().unlinkedCommits()).isEqualTo(2);
		assertThat(response.myMetrics().commits().traceabilityPercent()).isEqualTo((1 * 100.0) / 3);
		assertThat(response.myMetrics().commits().lastCommittedAt()).isEqualTo(LocalDateTime.of(2026, 9, 5, 10, 0));
		assertThat(response.recentCommits()).hasSize(3);
		assertThat(response.recentCommits().getFirst().message()).isEqualTo("normal");
		assertThat(response.recentCommits().getFirst().committedAt()).isNull();
		assertThat(response.recentCommits().getFirst().shortSha()).isEqualTo("aa00001");
		assertThat(response.recentCommits().get(1).committedAt()).isEqualTo(LocalDateTime.of(2026, 9, 2, 8, 0));
		assertThat(response.weeklyCommits()).hasSize(3);
		assertThat(response.weeklyCommits().get(0).commits()).isEqualTo(2);
		assertThat(response.weeklyCommits().get(1).commits()).isZero();
		assertThat(response.weeklyCommits().get(2).commits()).isZero();
	}

	@Test
	void attentionPreviewUsesMsrV23RuleOrderAndLimit() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			GitRepo repo = repos.save(repo(project, 31L, "org/preview", IntegrationStatus.ACTIVE));
			GitCommit normal = persistCommit(repo, member, "ee00001", 1, LocalDateTime.of(2026, 9, 1, 0, 0), "normal");
			GitCommit merge = persistCommit(repo, member, "ee00002", 2, LocalDateTime.of(2026, 9, 1, 0, 0), "merge");

			Task mergeOnly = assigned(
					project, null, member, TaskStatus.DONE, Priority.LOW, 1, LocalDateTime.of(2026, 9, 2, 0, 0), "[\"saga:code\"]", "SAGA-MERGE");
			tasks.save(mergeOnly);
			link(mergeOnly, merge);

			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.DONE,
					Priority.LOW,
					1,
					LocalDateTime.of(2026, 9, 20, 0, 0),
					"[\"saga:code\"]",
					"SAGA-ANOM"));

			Task evidenced = assigned(
					project, null, member, TaskStatus.DONE, Priority.HIGHEST, 1, LocalDateTime.of(2026, 9, 1, 0, 0), "[\"saga:test\"]", "SAGA-OK");
			tasks.save(evidenced);
			link(evidenced, normal);

			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.HIGH, 1, null, null, "SAGA-ORD"));
			tasks.save(assigned(
					project, null, member, TaskStatus.DONE, Priority.HIGH, 1, null, "[\"saga:document\"]", "SAGA-DOC"));
			tasks.save(assigned(
					project, null, member, TaskStatus.DONE, Priority.HIGH, 1, null, "[\"saga:research\"]", "SAGA-RES"));
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.DONE,
					Priority.HIGHEST,
					1,
					null,
					"[\"saga:code\",\"saga:test\"]",
					"SAGA-AMB"));

			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.IN_PROGRESS,
					Priority.LOWEST,
					1,
					LocalDateTime.of(2026, 9, 1, 0, 0),
					null,
					"SAGA-IP"));
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.TODO,
					Priority.HIGHEST,
					1,
					LocalDateTime.of(2026, 9, 5, 0, 0),
					null,
					"SAGA-HI"));
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.TODO,
					Priority.LOWEST,
					1,
					LocalDateTime.of(2026, 9, 5, 0, 0),
					null,
					"SAGA-LO"));
			for (int i = 0; i < 10; i++) {
				tasks.save(assigned(
						project,
						null,
						member,
						TaskStatus.TODO,
						Priority.LOW,
						1,
						LocalDateTime.of(2026, 10, i + 1, 0, 0),
						null,
						"SAGA-X" + i));
			}
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.myActiveTasks()).hasSize(10);
		assertThat(response.myActiveTasks().get(0).externalKey()).isEqualTo("SAGA-MERGE");
		assertThat(response.myActiveTasks().get(0).hasAnomaly()).isTrue();
		assertThat(response.myActiveTasks().get(0).linkedCommitCount()).isEqualTo(1);
		assertThat(response.myActiveTasks().get(0).evidenceCommitCount()).isZero();
		assertThat(response.myActiveTasks().get(1).externalKey()).isEqualTo("SAGA-ANOM");
		assertThat(response.myActiveTasks().get(1).hasAnomaly()).isTrue();
		assertThat(response.myActiveTasks().get(2).externalKey()).isEqualTo("SAGA-IP");
		assertThat(response.myActiveTasks().get(2).hasAnomaly()).isFalse();
		assertThat(response.myActiveTasks().get(3).externalKey()).isEqualTo("SAGA-HI");
		assertThat(response.myActiveTasks().get(4).externalKey()).isEqualTo("SAGA-LO");
		assertThat(response.myActiveTasks())
				.extracting(row -> row.externalKey())
				.doesNotContain("SAGA-OK", "SAGA-ORD", "SAGA-DOC", "SAGA-RES", "SAGA-AMB");
		assertThat(response.actionableAlerts())
				.extracting(StudentDashboardAlertResponse::type)
				.containsExactly("MSR_ANOMALY", "MSR_ANOMALY");
		assertThat(response.actionableAlerts())
				.extracting(alert -> alert.targetIds().taskId())
				.containsExactly(response.myActiveTasks().get(0).id(), response.myActiveTasks().get(1).id());
		assertThat(response.actionableAlerts().get(0).id()).isEqualTo("MSR:" + response.myActiveTasks().get(0).id());
		assertThat(response.actionableAlerts().get(1).id()).isEqualTo("MSR:" + response.myActiveTasks().get(1).id());
	}

	@Test
	void recentCommitsAreV23CappedBulkKeyedAndShortSha() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			GitRepo repo = repos.save(repo(project, 41L, "org/recent", IntegrationStatus.ACTIVE));
			GitCommit newest = persistCommit(repo, member, "abcdef123456", 1, LocalDateTime.of(2026, 9, 6, 0, 0), "newest");
			persistCommit(repo, member, "short1", 0, LocalDateTime.of(2026, 9, 5, 0, 0), "short");
			persistCommit(repo, member, "unlinkedabcdef", null, LocalDateTime.of(2026, 9, 4, 0, 0), "unlinked");
			GitCommit multi = persistCommit(repo, member, "multikeyabcdef", 1, LocalDateTime.of(2026, 9, 3, 0, 0), "multi");
			persistCommit(repo, member, "olderabcdef01", 1, LocalDateTime.of(2026, 9, 2, 0, 0), "older");
			persistCommit(repo, member, "oldestabcdef2", 1, LocalDateTime.of(2026, 9, 1, 0, 0), "oldest-out");
			persistCommit(repo, member, "mergeabcdef12", 2, LocalDateTime.of(2026, 9, 7, 0, 0), "merge-out");

			Task keyed = tasks.save(assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-12"));
			Task also = tasks.save(assigned(project, null, member, TaskStatus.TODO, null, null, null, null, "SAGA-15"));
			Task noKey = assigned(project, null, member, TaskStatus.TODO, null, null, null, null, null);
			tasks.save(noKey);
			link(keyed, newest);
			link(keyed, multi);
			link(also, multi);
			link(noKey, multi);
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.recentCommits()).hasSize(5);
		assertThat(response.recentCommits().get(0).sha()).isEqualTo("abcdef123456");
		assertThat(response.recentCommits().get(0).shortSha()).isEqualTo("abcdef1");
		assertThat(response.recentCommits().get(0).repositoryName()).isEqualTo("org/recent");
		assertThat(response.recentCommits().get(0).linkedTaskKeys()).containsExactly("SAGA-12");
		assertThat(response.recentCommits().get(1).shortSha()).isEqualTo("short1");
		assertThat(response.recentCommits().get(2).linkedTaskKeys()).isEmpty();
		assertThat(response.recentCommits().get(3).linkedTaskKeys()).containsExactly("SAGA-12", "SAGA-15");
		assertThat(response.recentCommits()).extracting(row -> row.message()).doesNotContain("merge-out", "oldest-out");
	}

	@Test
	void personalWorkQueryCountStaysConstantFromTenToOneHundred() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			GitRepo repo = repos.save(repo(project, 51L, "org/load", IntegrationStatus.ACTIVE));
			for (int i = 0; i < 10; i++) {
				tasks.save(assigned(project, null, member, TaskStatus.TODO, Priority.LOW, 1, null, null, "SAGA-T" + i));
			}
			for (int i = 0; i < 5; i++) {
				persistCommit(repo, member, "load" + i + "aaaaaaa", 1, LocalDateTime.of(2026, 9, i + 1, 0, 0), "c" + i);
			}
			entityManager.flush();
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		long first = stats.getPrepareStatementCount();
		assertThat(first).as("Phase A+B1+B2+D1 with 10 tasks / 5 commits stays bounded").isEqualTo(18L);

		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			GitRepo repo = repos.findAll().stream()
					.filter(row -> "org/load".equals(row.getFullName()))
					.findFirst()
					.orElseThrow();
			for (int i = 10; i < 100; i++) {
				tasks.save(assigned(project, null, member, TaskStatus.TODO, Priority.LOW, 1, null, null, "SAGA-T" + i));
			}
			for (int i = 5; i < 100; i++) {
				persistCommit(repo, member, "load" + i + "bbbbbbb", 1, LocalDateTime.of(2026, 8, 1, 0, 0).plusHours(i), "c" + i);
			}
			entityManager.flush();
		});
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse grown = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(grown.myMetrics().tasks().totalAssigned()).isEqualTo(100);
		assertThat(grown.myMetrics().commits().totalCommits()).isEqualTo(100);
		assertThat(grown.myActiveTasks()).hasSize(10);
		assertThat(grown.recentCommits()).hasSize(5);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(first);
	}

	@Test
	void coarseLabelPrefetchCannotHideLaterTrueAnomaly() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			for (int i = 0; i < 51; i++) {
				tasks.save(assigned(
						project,
						null,
						member,
						TaskStatus.DONE,
						Priority.HIGHEST,
						1,
						LocalDateTime.of(2026, 7, 1, 0, 0).plusDays(i),
						"[\"saga:code\",\"saga:test\"]",
						"SAGA-AMB" + i));
			}
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.DONE,
					Priority.LOWEST,
					1,
					LocalDateTime.of(2026, 12, 1, 0, 0),
					"[\"saga:code\"]",
					"SAGA-REAL"));
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.TODO,
					Priority.HIGHEST,
					1,
					LocalDateTime.of(2026, 6, 1, 0, 0),
					null,
					"SAGA-OPEN"));
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.myActiveTasks().getFirst().externalKey()).isEqualTo("SAGA-REAL");
		assertThat(response.myActiveTasks().getFirst().hasAnomaly()).isTrue();
		assertThat(response.myActiveTasks().get(1).externalKey()).isEqualTo("SAGA-OPEN");
		assertThat(response.myActiveTasks().get(1).hasAnomaly()).isFalse();
	}

	@Test
	void moreThanTenTrueAnomaliesReturnExactTopTen() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			for (int i = 1; i <= 12; i++) {
				tasks.save(assigned(
						project,
						null,
						member,
						TaskStatus.DONE,
						Priority.MEDIUM,
						1,
						LocalDateTime.of(2026, 9, i, 0, 0),
						"[\"saga:test\"]",
						"SAGA-A" + String.format("%02d", i)));
			}
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.TODO,
					Priority.HIGHEST,
					1,
					LocalDateTime.of(2026, 1, 1, 0, 0),
					null,
					"SAGA-OPEN"));
			entityManager.flush();
		});

		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.myActiveTasks()).hasSize(10);
		assertThat(response.myActiveTasks())
				.extracting(row -> row.externalKey())
				.containsExactly(
						"SAGA-A01",
						"SAGA-A02",
						"SAGA-A03",
						"SAGA-A04",
						"SAGA-A05",
						"SAGA-A06",
						"SAGA-A07",
						"SAGA-A08",
						"SAGA-A09",
						"SAGA-A10");
		assertThat(response.myActiveTasks()).allMatch(row -> row.hasAnomaly());
		assertThat(stats.getPrepareStatementCount()).isEqualTo(16L);
		assertThat(response.actionableAlerts()).hasSize(13);
		assertThat(response.actionableAlerts().subList(0, 12))
				.allMatch(alert -> "MSR_ANOMALY".equals(alert.type()));
		assertThat(response.actionableAlerts().get(12).type()).isEqualTo("PEER_REVIEW_PENDING");
	}

	@Test
	void anomalyCandidateQueryCountStaysConstantFromTenToFiveHundred() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			for (int i = 0; i < 10; i++) {
				tasks.save(assigned(
						project,
						null,
						member,
						TaskStatus.DONE,
						Priority.LOW,
						1,
						null,
						"[\"saga:code\",\"saga:test\"]",
						"SAGA-C" + i));
			}
			tasks.save(assigned(project, null, member, TaskStatus.TODO, Priority.LOW, 1, null, null, "SAGA-OPEN"));
			entityManager.flush();
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		long first = stats.getPrepareStatementCount();
		assertThat(first).as("Phase A+B1+B2+D1 with 10 coarse DONE candidates stays bounded").isEqualTo(16L);

		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			for (int i = 10; i < 500; i++) {
				tasks.save(assigned(
						project,
						null,
						member,
						TaskStatus.DONE,
						Priority.LOW,
						1,
						null,
						"[\"saga:code\",\"saga:test\"]",
						"SAGA-C" + i));
			}
			tasks.save(assigned(
					project,
					null,
					member,
					TaskStatus.DONE,
					Priority.HIGH,
					1,
					LocalDateTime.of(2026, 9, 1, 0, 0),
					"[\"saga:code\"]",
					"SAGA-REAL"));
			entityManager.flush();
		});
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse grown = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(grown.myActiveTasks().getFirst().externalKey()).isEqualTo("SAGA-REAL");
		assertThat(grown.myActiveTasks().getFirst().hasAnomaly()).isTrue();
		assertThat(stats.getPrepareStatementCount()).isEqualTo(first);
	}

	@Test
	void weeklyCommitsUseCommittedAtOnlyAndIsoMondayBounds() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			GitRepo repo = repos.save(repo(project, 61L, "org/weekly", IntegrationStatus.ACTIVE));
			Project other = new Project();
			other.setCourse(courses.findById(fixture.otherCourseId).orElseThrow());
			other.setName("OTHER-W");
			other = projects.save(other);
			GitRepo otherRepo = repos.save(repo(other, 62L, "org/weekly-other", IntegrationStatus.ACTIVE));
			persistCommit(repo, member, "w-null", null, LocalDateTime.of(2026, 9, 14, 0, 0), "null-parent");
			persistCommit(repo, member, "w-root", 0, LocalDateTime.of(2026, 8, 31, 0, 0), "root");
			persistCommit(repo, member, "w-one", 1, LocalDateTime.of(2026, 9, 13, 23, 59, 59), "normal");
			persistCommit(repo, member, "w-mon", 1, LocalDateTime.of(2026, 9, 14, 0, 0), "current-monday");
			persistCommit(repo, member, "w-sun", 1, LocalDateTime.of(2026, 9, 20, 12, 0), "current-sunday");
			persistCommit(repo, member, "w-before", 1, LocalDateTime.of(2026, 8, 30, 23, 59, 59), "before-window");
			persistCommit(repo, member, "w-next", 1, LocalDateTime.of(2026, 9, 21, 0, 0), "next-monday");
			persistCommit(repo, member, "w-merge", 2, LocalDateTime.of(2026, 9, 15, 0, 0), "merge");
			persistCommit(repo, member, "w-nocommit", 1, null, "null-committedAt");
			persistCommit(repo, leader, "w-lead", 1, LocalDateTime.of(2026, 9, 15, 0, 0), "teammate");
			persistCommit(repo, null, "w-unmap", 1, LocalDateTime.of(2026, 9, 15, 0, 0), "unmapped");
			persistCommit(otherRepo, member, "w-other", 1, LocalDateTime.of(2026, 9, 15, 0, 0), "other-project");
			entityManager.flush();
		});

		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.weeklyCommits()).hasSize(3);
		assertThat(response.weeklyCommits().get(0).startDate()).isEqualTo(java.time.LocalDate.of(2026, 8, 31));
		assertThat(response.weeklyCommits().get(0).endDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 6));
		assertThat(response.weeklyCommits().get(0).commits()).isEqualTo(1);
		assertThat(response.weeklyCommits().get(1).startDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 7));
		assertThat(response.weeklyCommits().get(1).commits()).isEqualTo(1);
		assertThat(response.weeklyCommits().get(2).startDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 14));
		assertThat(response.weeklyCommits().get(2).endDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 20));
		assertThat(response.weeklyCommits().get(2).commits()).isEqualTo(3);
		assertThat(response.myMetrics().commits().lastCommittedAt()).isNotNull();
	}

	@Test
	void weeklyCommitsAreThreeZerosWhenOnlyMergesOrNullCommittedAt() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			GitRepo repo = repos.save(repo(project, 71L, "org/weekly-empty", IntegrationStatus.ACTIVE));
			persistCommit(repo, member, "m1", 2, LocalDateTime.of(2026, 9, 15, 0, 0), "merge");
			persistCommit(repo, member, "n1", 1, null, "null-at");
			entityManager.flush();
		});
		StudentDashboardResponse response = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(response.weeklyCommits()).hasSize(3);
		assertThat(response.weeklyCommits()).allMatch(row -> row.commits() == 0);
	}

	@Test
	void d1MsrAlertsFollowB1RuleAndStayConsistentWithPreview() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			GitRepo repo = repos.save(repo(project, 81L, "org/d1-msr", IntegrationStatus.ACTIVE));
			GitCommit normal = persistCommit(repo, member, "d1norm01", 1, LocalDateTime.of(2026, 9, 1, 0, 0), "normal");
			GitCommit merge = persistCommit(repo, member, "d1merg01", 2, LocalDateTime.of(2026, 9, 1, 0, 0), "merge");

			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.HIGH, 1, LocalDateTime.of(2026, 9, 1, 0, 0), "[\"saga:code\"]", "SAGA-CODE"));
			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.MEDIUM, 1, LocalDateTime.of(2026, 9, 2, 0, 0), "[\"saga:test\"]", "SAGA-TEST"));

			Task evidenced = assigned(project, null, member, TaskStatus.DONE, Priority.HIGHEST, 1, LocalDateTime.of(2026, 8, 1, 0, 0), "[\"saga:code\"]", "SAGA-OK");
			tasks.save(evidenced);
			link(evidenced, normal);

			Task mergeOnly = assigned(project, null, member, TaskStatus.DONE, Priority.LOW, 1, LocalDateTime.of(2026, 9, 3, 0, 0), "[\"saga:code\"]", "SAGA-MERGE");
			tasks.save(mergeOnly);
			link(mergeOnly, merge);

			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.HIGH, 1, null, "[\"saga:document\"]", "SAGA-DOC"));
			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.HIGH, 1, null, "[\"saga:research\"]", "SAGA-RES"));
			tasks.save(assigned(project, null, member, TaskStatus.DONE, Priority.HIGHEST, 1, null, "[\"saga:code\",\"saga:test\"]", "SAGA-AMB"));
			tasks.save(assigned(project, null, leader, TaskStatus.DONE, Priority.HIGHEST, 1, LocalDateTime.of(2026, 8, 1, 0, 0), "[\"saga:code\"]", "SAGA-LEAD"));
			Task deleted = assigned(project, null, member, TaskStatus.DONE, Priority.HIGHEST, 1, LocalDateTime.of(2026, 8, 2, 0, 0), "[\"saga:test\"]", "SAGA-DEL");
			deleted.setDeletedAt(LocalDateTime.of(2026, 9, 4, 0, 0));
			tasks.save(deleted);
			tasks.save(assigned(project, null, member, TaskStatus.TODO, Priority.LOW, 1, LocalDateTime.of(2026, 10, 1, 0, 0), null, "SAGA-OPEN"));
			entityManager.flush();
		});

		StudentDashboardResponse first = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(first.actionableAlerts())
				.extracting(StudentDashboardAlertResponse::type)
				.containsExactly("MSR_ANOMALY", "MSR_ANOMALY", "MSR_ANOMALY");
		assertThat(first.actionableAlerts())
				.extracting(alert -> first.myActiveTasks().stream()
						.filter(task -> task.id().equals(alert.targetIds().taskId()))
						.findFirst()
						.orElseThrow()
						.externalKey())
				.containsExactly("SAGA-CODE", "SAGA-TEST", "SAGA-MERGE");
		assertThat(first.actionableAlerts())
				.allMatch(alert -> alert.id().equals("MSR:" + alert.targetIds().taskId()));
		assertThat(first.myActiveTasks().stream().filter(row -> row.hasAnomaly()).map(row -> "MSR:" + row.id()).toList())
				.containsExactlyElementsOf(first.actionableAlerts().stream().map(StudentDashboardAlertResponse::id).toList());
		assertThat(first.myActiveTasks().stream().filter(row -> !row.hasAnomaly()).map(row -> row.externalKey()).toList())
				.contains("SAGA-OPEN");
		assertThat(first.actionableAlerts())
				.extracting(alert -> alert.targetIds().taskId())
				.doesNotContainAnyElementsOf(
						first.myActiveTasks().stream()
								.filter(row -> !row.hasAnomaly())
								.map(row -> row.id())
								.toList());

		StudentDashboardResponse second = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(second.actionableAlerts())
				.extracting(StudentDashboardAlertResponse::id)
				.containsExactlyElementsOf(
						first.actionableAlerts().stream().map(StudentDashboardAlertResponse::id).toList());
	}

	@Test
	void d1PeerReviewPendingUsesCurrentSprintCandidatesAndSubmissions() {
		java.util.concurrent.atomic.AtomicReference<UUID> currentId = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<UUID> extra1Id = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<UUID> extra2Id = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<UUID> extra3Id = new java.util.concurrent.atomic.AtomicReference<>();
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			Course course = courses.findById(fixture.courseId).orElseThrow();
			Team team = teams.findById(fixture.teamId).orElseThrow();
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			Sprint old = sprints.save(sprint(jira, "closed", LocalDateTime.of(2026, 8, 1, 0, 0)));
			Sprint olderActive = sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			Sprint current = sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 10, 0, 0)));
			currentId.set(current.getId());

			StudentProfile extra1 = addActiveTeammate(course, team, "d1p1");
			StudentProfile extra2 = addActiveTeammate(course, team, "d1p2");
			StudentProfile extra3 = addActiveTeammate(course, team, "d1p3");
			extra1Id.set(extra1.getId());
			extra2Id.set(extra2.getId());
			extra3Id.set(extra3.getId());
			addWithdrawnTeammate(course, team, "d1w");

			peerReviews.save(review(old, member, leader, 5));
			peerReviews.save(review(old, member, extra1, 4));
			peerReviews.save(review(olderActive, member, extra2, 3));
			peerReviews.save(review(current, leader, member, 5));
			entityManager.flush();
		});

		StudentDashboardResponse noneSubmitted = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(noneSubmitted.currentSprint().id()).isEqualTo(currentId.get());
		assertThat(noneSubmitted.actionableAlerts()).hasSize(1);
		assertThat(noneSubmitted.actionableAlerts().getFirst().id()).isEqualTo("PEER_REVIEW_PENDING:" + currentId.get());
		assertThat(noneSubmitted.actionableAlerts().getFirst().remainingPeers()).isEqualTo(4);
		assertThat(noneSubmitted.actionableAlerts().getFirst().message()).contains("4 teammate reviews");
		assertThat(noneSubmitted.actionableAlerts().getFirst().message()).doesNotContain("deadline", "overdue", "open");

		StudentDashboardResponse sameIds = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(sameIds.actionableAlerts().getFirst().id()).isEqualTo(noneSubmitted.actionableAlerts().getFirst().id());

		tx.executeWithoutResult(status -> {
			StudentProfile member = profileOf(fixture.memberId);
			StudentProfile leader = profileOf(fixture.leaderId);
			Sprint current = sprints.findById(currentId.get()).orElseThrow();
			peerReviews.save(review(current, member, leader, 5));
			peerReviews.save(review(current, member, students.findById(extra1Id.get()).orElseThrow(), 4));
			entityManager.flush();
		});
		StudentDashboardResponse twoLeft = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(twoLeft.actionableAlerts()).hasSize(1);
		assertThat(twoLeft.actionableAlerts().getFirst().remainingPeers()).isEqualTo(2);

		tx.executeWithoutResult(status -> {
			StudentProfile member = profileOf(fixture.memberId);
			Sprint current = sprints.findById(currentId.get()).orElseThrow();
			peerReviews.save(review(current, member, students.findById(extra2Id.get()).orElseThrow(), 3));
			peerReviews.save(review(current, member, students.findById(extra3Id.get()).orElseThrow(), 3));
			entityManager.flush();
		});
		StudentDashboardResponse done = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(done.actionableAlerts()).isEmpty();
	}

	@Test
	void d1PeerAlertQueryCountDoesNotScaleWithTeammatesOrReviews() {
		tx.executeWithoutResult(status -> {
			Project project = projects.findById(fixture.projectId).orElseThrow();
			Course course = courses.findById(fixture.courseId).orElseThrow();
			Team team = teams.findById(fixture.teamId).orElseThrow();
			JiraIntegration jira = jiraIntegrations.save(jira(project, IntegrationStatus.ACTIVE));
			sprints.save(sprint(jira, "active", LocalDateTime.of(2026, 9, 1, 0, 0)));
			addActiveTeammate(course, team, "q2");
			entityManager.flush();
		});
		Statistics stats = statistics();
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse first = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		long firstCount = stats.getPrepareStatementCount();
		assertThat(first.actionableAlerts()).hasSize(1);
		assertThat(first.actionableAlerts().getFirst().remainingPeers()).isEqualTo(2);
		assertThat(firstCount).as("D1 with current sprint and 3 candidates stays bounded").isEqualTo(15L);

		tx.executeWithoutResult(status -> {
			Course course = courses.findById(fixture.courseId).orElseThrow();
			Team team = teams.findById(fixture.teamId).orElseThrow();
			Sprint current = sprints.findActiveByProject_Id(fixture.projectId).getFirst();
			StudentProfile member = profileOf(fixture.memberId);
			for (int i = 0; i < 8; i++) {
				StudentProfile extra = addActiveTeammate(course, team, "q10" + i);
				if (i < 3) {
					peerReviews.save(review(current, member, extra, 4));
				}
			}
			entityManager.flush();
		});
		tx.executeWithoutResult(status -> entityManager.clear());
		stats.clear();
		StudentDashboardResponse grown = tx.execute(status -> service.get(fixture.memberId, fixture.courseId));
		assertThat(grown.team().membersCount()).isEqualTo(11);
		assertThat(grown.actionableAlerts().getFirst().remainingPeers()).isEqualTo(7);
		assertThat(stats.getPrepareStatementCount()).isEqualTo(firstCount);
	}

	@Test
	void academicZoneClockMovesCurrentIsoWeek() {
		StudentDashboardService ict = new StudentDashboardService(
				enrollments,
				members,
				jiraIntegrations,
				repos,
				sprints,
				tasks,
				commits,
				commitLinks,
				peerReviews,
				java.time.Clock.fixed(java.time.Instant.parse("2026-09-20T17:00:00Z"), java.time.ZoneId.of("Asia/Ho_Chi_Minh")));
		StudentDashboardResponse response = tx.execute(status -> ict.get(fixture.memberId, fixture.courseId));
		assertThat(response.weeklyCommits().get(2).startDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 21));
		assertThat(response.weeklyCommits().get(2).endDate()).isEqualTo(java.time.LocalDate.of(2026, 9, 27));
	}

	private StudentProfile addActiveTeammate(Course course, Team team, String tag) {
		UserAccount account = account("peer-" + tag + "-" + UUID.randomUUID() + "@gmail.com");
		StudentProfile extra = profile(account, "ST" + tag + UUID.randomUUID().toString().substring(0, 6));
		members.save(memberOf(team, course, enroll(extra, course, EnrollmentStatus.ACTIVE), RoleInTeam.MEMBER));
		return extra;
	}

	private StudentProfile addWithdrawnTeammate(Course course, Team team, String tag) {
		UserAccount account = account("wd-" + tag + "-" + UUID.randomUUID() + "@gmail.com");
		StudentProfile extra = profile(account, "SW" + tag + UUID.randomUUID().toString().substring(0, 6));
		members.save(memberOf(team, course, enroll(extra, course, EnrollmentStatus.WITHDRAWN), RoleInTeam.MEMBER));
		return extra;
	}

	private static PeerReview review(Sprint sprint, StudentProfile reviewer, StudentProfile reviewee, int stars) {
		PeerReview row = new PeerReview();
		row.setSprint(sprint);
		row.setReviewerStudent(reviewer);
		row.setRevieweeStudent(reviewee);
		row.setStarRating(stars);
		return row;
	}

	private void assertForbidden(UUID userId, UUID courseId) {
		assertThatThrownBy(() -> tx.execute(status -> service.get(userId, courseId)))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.STUDENT_COURSE_FORBIDDEN);
	}

	private Fixture seed() {
		return tx.execute(status -> {
			String suffix = UUID.randomUUID().toString().substring(0, 8);
			Semester semester = new Semester();
			semester.setCode("FA" + suffix);
			semester.setName("Fall 2026");
			semester = semesters.save(semester);

			AcademicClass academicClass = new AcademicClass();
			academicClass.setSemester(semester);
			academicClass.setClassCode("SE" + suffix);
			academicClass.setName("SE" + suffix);
			academicClass = classes.save(academicClass);

			Subject subject = subject("SWP" + suffix);
			SubjectSyllabusVersion syllabus = syllabus(subject);
			Course course = course("Main", subject, academicClass, semester, syllabus);

			Subject otherSubject = subject("SWD" + suffix);
			Course otherCourse = course("Other", otherSubject, academicClass, semester, syllabus(otherSubject));

			Subject noProjectSubject = subject("SWE" + suffix);
			Course noProjectCourse = course("NoProject", noProjectSubject, academicClass, semester, syllabus(noProjectSubject));

			UserAccount leader = account("leader-" + suffix + "@gmail.com");
			UserAccount member = account("member-" + suffix + "@gmail.com");
			UserAccount withdrawn = account("withdrawn-" + suffix + "@gmail.com");
			UserAccount completed = account("completed-" + suffix + "@gmail.com");
			UserAccount unassigned = account("unassigned-" + suffix + "@gmail.com");
			UserAccount noProjectMember = account("noproj-" + suffix + "@gmail.com");

			StudentProfile leaderProfile = profile(leader, "SE1" + suffix);
			StudentProfile memberProfile = profile(member, "SE2" + suffix);
			StudentProfile withdrawnProfile = profile(withdrawn, "SE3" + suffix);
			StudentProfile completedProfile = profile(completed, "SE4" + suffix);
			StudentProfile unassignedProfile = profile(unassigned, "SE5" + suffix);
			StudentProfile noProjectProfile = profile(noProjectMember, "SE6" + suffix);

			enroll(withdrawnProfile, course, EnrollmentStatus.WITHDRAWN);
			enroll(completedProfile, course, EnrollmentStatus.COMPLETED);
			enroll(unassignedProfile, course, EnrollmentStatus.ACTIVE);

			Project project = new Project();
			project.setCourse(course);
			project.setName("SAGA");
			project = projects.save(project);

			Team team = new Team();
			team.setCourse(course);
			team.setProject(project);
			team.setTeamNo(1);
			team.setName("Alpha");
			team = teams.save(team);
			members.save(memberOf(team, course, enroll(leaderProfile, course, EnrollmentStatus.ACTIVE), RoleInTeam.LEADER));
			members.save(memberOf(team, course, enroll(memberProfile, course, EnrollmentStatus.ACTIVE), RoleInTeam.MEMBER));

			Team noProjectTeam = new Team();
			noProjectTeam.setCourse(noProjectCourse);
			noProjectTeam.setTeamNo(1);
			noProjectTeam.setName("Bare");
			noProjectTeam = teams.save(noProjectTeam);
			members.save(memberOf(
					noProjectTeam,
					noProjectCourse,
					enroll(noProjectProfile, noProjectCourse, EnrollmentStatus.ACTIVE),
					RoleInTeam.MEMBER));

			entityManager.flush();
			return new Fixture(
					leader.getId(),
					member.getId(),
					withdrawn.getId(),
					completed.getId(),
					unassigned.getId(),
					noProjectMember.getId(),
					course.getId(),
					otherCourse.getId(),
					noProjectCourse.getId(),
					team.getId(),
					project.getId());
		});
	}

	private UserAccount account(String email) {
		UserAccount account = new UserAccount();
		account.setEmail(email);
		account.setFullName(email);
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return users.save(account);
	}

	private StudentProfile profile(UserAccount account, String code) {
		StudentProfile profile = new StudentProfile();
		profile.setUserAccount(account);
		profile.setStudentCode(code);
		profile.setVersion(0L);
		return students.save(profile);
	}

	private Subject subject(String code) {
		Subject subject = new Subject();
		subject.setSubjectCode(code);
		subject.setName("Software Project");
		subject.setStatus(SubjectStatus.ACTIVE);
		return subjects.save(subject);
	}

	private SubjectSyllabusVersion syllabus(Subject subject) {
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("1.0");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(syllabus);
		return syllabus;
	}

	private Course course(
			String name, Subject subject, AcademicClass academicClass, Semester semester, SubjectSyllabusVersion syllabus) {
		Course course = new Course();
		course.setName(name);
		course.setCourseCode(subject.getSubjectCode());
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		return courses.save(course);
	}

	private CourseEnrollment enroll(StudentProfile profile, Course course, EnrollmentStatus status) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(status);
		enrollment.setEnrolledAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		return enrollments.save(enrollment);
	}

	private static TeamMember memberOf(Team team, Course course, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		return member;
	}

	private static JiraIntegration jira(Project project, IntegrationStatus status) {
		JiraIntegration jira = new JiraIntegration();
		jira.setProject(project);
		jira.setName("board");
		jira.setCloudId("cloud-1");
		jira.setJiraProjectId("10000");
		jira.setProjectKey("SAGA");
		jira.setConnectionStatus(status);
		jira.setConsecutiveFailures(0);
		jira.setVersion(0L);
		return jira;
	}

	private static GitRepo repo(Project project, long repositoryId, String fullName, IntegrationStatus status) {
		GitRepo repo = new GitRepo();
		repo.setProject(project);
		repo.setName(fullName.substring(fullName.indexOf('/') + 1));
		repo.setProvider(GitProvider.GITHUB);
		repo.setRepositoryId(repositoryId);
		repo.setOwnerLogin("org");
		repo.setFullName(fullName);
		repo.setConnectionStatus(status);
		repo.setConsecutiveFailures(0);
		repo.setLastSyncedAt(LocalDateTime.of(2026, 9, 1, 0, 0));
		repo.setVersion(0L);
		return repo;
	}

	private static Sprint sprint(JiraIntegration jira, String state, LocalDateTime start) {
		Sprint sprint = new Sprint();
		sprint.setJiraIntegration(jira);
		sprint.setExternalSprintId(state + "-" + start.toLocalDate());
		sprint.setName(state + " " + start.toLocalDate());
		sprint.setState(state);
		sprint.setStartDate(start);
		return sprint;
	}

	private static Task task(Project project, Sprint sprint, TaskStatus status, Task parent) {
		Task task = new Task();
		task.setProject(project);
		task.setSprint(sprint);
		task.setStatus(status);
		task.setTitle(status.name());
		task.setExternalId(UUID.randomUUID().toString());
		task.setParentTask(parent);
		return task;
	}

	private StudentProfile profileOf(UUID userId) {
		return students.findByUserAccount_Id(userId).orElseThrow();
	}

	private static Task assigned(
			Project project,
			Sprint sprint,
			StudentProfile assignee,
			TaskStatus status,
			Priority priority,
			Integer storyPoint,
			LocalDateTime due,
			String labelsJson,
			String externalKey) {
		Task task = task(project, sprint, status, null);
		task.setAssigneeStudent(assignee);
		task.setPriority(priority);
		task.setStoryPoint(storyPoint);
		task.setDueDate(due);
		task.setLabelsJson(labelsJson);
		task.setExternalKey(externalKey);
		if (externalKey != null) {
			task.setTitle(externalKey);
		}
		return task;
	}

	private GitCommit persistCommit(
			GitRepo repo, StudentProfile author, String sha, Integer parentCount, LocalDateTime committedAt, String message) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repo);
		commit.setAuthorStudent(author);
		commit.setShaHash(sha);
		commit.setParentCount(parentCount);
		commit.setCommittedAt(committedAt);
		commit.setMessage(message);
		return commits.save(commit);
	}

	private TaskGitCommitLink link(Task task, GitCommit commit) {
		TaskGitCommitLink row = new TaskGitCommitLink();
		row.setTask(task);
		row.setGitCommit(commit);
		row.setLinkSource(TraceLinkSource.COMMIT_MESSAGE);
		return commitLinks.save(row);
	}

	private Statistics statistics() {
		return entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
	}

	private record Fixture(
			UUID leaderId,
			UUID memberId,
			UUID withdrawnId,
			UUID completedId,
			UUID unassignedId,
			UUID noProjectMemberId,
			UUID courseId,
			UUID otherCourseId,
			UUID noProjectCourseId,
			UUID teamId,
			UUID projectId) {}
}

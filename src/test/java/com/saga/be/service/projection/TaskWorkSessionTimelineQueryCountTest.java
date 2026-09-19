package com.saga.be.service.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.saga.be.dto.project.TaskWorkSessionTimelineResponse;
import com.saga.be.entity.account.LecturerProfile;
import com.saga.be.entity.account.StudentProfile;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.CourseEnrollment;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.attribution.TaskWorkSession;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.enums.EnrollmentStatus;
import com.saga.be.entity.enums.GitProvider;
import com.saga.be.entity.enums.IntegrationStatus;
import com.saga.be.entity.enums.RoleInTeam;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.entity.enums.TaskStatus;
import com.saga.be.entity.enums.TraceLinkSource;
import com.saga.be.entity.enums.WorkSessionStatus;
import com.saga.be.entity.github.GitCommit;
import com.saga.be.entity.github.GitRepo;
import com.saga.be.entity.jira.Task;
import com.saga.be.entity.project.Project;
import com.saga.be.entity.project.Team;
import com.saga.be.entity.project.TeamMember;
import com.saga.be.entity.traceability.TaskGitCommitLink;
import com.saga.be.exception.AcademicErrorCode;
import com.saga.be.exception.AcademicException;
import com.saga.be.exception.IntegrationException;
import com.saga.be.integration.IntegrationErrorCode;
import com.saga.be.repository.AcademicClassRepository;
import com.saga.be.repository.CourseEnrollmentRepository;
import com.saga.be.repository.CourseRepository;
import com.saga.be.repository.GitCommitRepository;
import com.saga.be.repository.GitRepoRepository;
import com.saga.be.repository.LecturerProfileRepository;
import com.saga.be.repository.ProjectRepository;
import com.saga.be.repository.SemesterRepository;
import com.saga.be.repository.StudentProfileRepository;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.TaskGitCommitLinkRepository;
import com.saga.be.repository.TaskRepository;
import com.saga.be.repository.TaskWorkSessionRepository;
import com.saga.be.repository.TeamMemberRepository;
import com.saga.be.repository.TeamRepository;
import com.saga.be.repository.UserAccountRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.List;
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
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * GET /projects/{id}/tasks/{id}/work-session-timeline — auth, V23 commits, session paging, and
 * statement-count stability independent of session/commit cardinality.
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
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TaskWorkSessionTimelineQueryCountTest {

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
	private EntityManager entityManager;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private UserAccountRepository users;
	@Autowired
	private StudentProfileRepository students;
	@Autowired
	private LecturerProfileRepository lecturers;
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
	private ProjectRepository projects;
	@Autowired
	private TeamRepository teams;
	@Autowired
	private TeamMemberRepository members;
	@Autowired
	private GitRepoRepository repos;
	@Autowired
	private GitCommitRepository commits;
	@Autowired
	private TaskRepository tasks;
	@Autowired
	private TaskGitCommitLinkRepository links;
	@Autowired
	private TaskWorkSessionRepository sessions;

	private TransactionTemplate tx;
	private TaskWorkSessionTimelineService service;
	private UserAccount student;
	private UserAccount teammate;
	private UserAccount lecturerUser;
	private UserAccount adminUser;
	private StudentProfile studentProfile;
	private StudentProfile teammateProfile;
	private Project project;
	private Course course;
	private GitRepo repo;
	private GitRepo repoB;
	private Task task;
	private Task otherProjectTask;

	@BeforeEach
	void setUp() {
		tx = new TransactionTemplate(transactionManager);
		ProjectDataAuthorization authorization = new ProjectDataAuthorization(users, members, projects);
		service = new TaskWorkSessionTimelineService(authorization, tasks, sessions, links, students);
		tx.executeWithoutResult(status -> seedGraph());
	}

	@Test
	void emptyTimeline_queryCountBounded() {
		Measured empty = measure(student.getId(), 0, 20, 0, 50);
		assertThat(empty.response.workSessions().sessionCount()).isZero();
		assertThat(empty.response.workSessions().totalElapsedSeconds()).isZero();
		assertThat(empty.response.workSessions().openSessions()).isEmpty();
		assertThat(empty.response.workSessions().sessions()).isEmpty();
		assertThat(empty.response.commits().items()).isEmpty();
		assertThat(empty.queries).as("empty: auth+task+bounds+open+sessionPage+commitPage").isEqualTo(7L);
	}

	@Test
	void queryCountStableAcrossSessionAndCommitCardinality() {
		seedSessions(1, false);
		seedLinkedCommits(1, 1);
		Measured one = measure(student.getId(), 0, 20, 0, 50);
		assertThat(one.response.workSessions().sessions()).hasSize(1);
		assertThat(one.response.commits().items()).hasSize(1);
		assertThat(one.queries).as("short pages: auth+task+bounds+open+idPage+fetch+profiles+linkPage+linkFetch").isEqualTo(10L);

		seedSessions(49, false);
		seedLinkedCommits(49, 1);
		Measured fiftyShort = measure(student.getId(), 0, 100, 0, 200);
		assertThat(fiftyShort.response.workSessions().sessionCount()).isEqualTo(50);
		assertThat(fiftyShort.response.workSessions().sessions()).hasSize(50);
		assertThat(fiftyShort.response.commits().items()).hasSize(50);
		assertThat(fiftyShort.queries)
				.as("50-row short pages (size 100/200) do not add per-row queries")
				.isEqualTo(one.queries);

		Measured fullPages = measure(student.getId(), 0, 20, 0, 50);
		assertThat(fullPages.response.workSessions().sessions()).hasSize(20);
		assertThat(fullPages.response.commits().items()).hasSize(50);
		assertThat(fullPages.queries)
				.as("full pages add Spring Data count queries only, not per row")
				.isEqualTo(one.queries + 2);
	}

	@Test
	void auth_memberLeaderLecturerAllowed_adminWithdrawnForeignDenied() {
		assertThat(measure(student.getId(), 0, 20, 0, 50).response.task().id()).isEqualTo(task.getId());

		tx.executeWithoutResult(status -> {
			TeamMember leader = members.findAll().stream()
					.filter(m -> m.getCourseEnrollment().getStudentProfile().getId().equals(studentProfile.getId()))
					.findFirst()
					.orElseThrow();
			leader.setRoleInTeam(RoleInTeam.LEADER);
			members.save(leader);
		});
		assertThat(measure(student.getId(), 0, 20, 0, 50).response.task().id()).isEqualTo(task.getId());

		assertThat(measure(lecturerUser.getId(), 0, 20, 0, 50).response.task().id()).isEqualTo(task.getId());

		assertThatThrownBy(() -> measure(adminUser.getId(), 0, 20, 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.ACCESS_DENIED);

		UserAccount outsider = tx.execute(status -> {
			UserAccount u = new UserAccount();
			u.setEmail("out-" + UUID.randomUUID() + "@fe.edu.vn");
			u.setFullName("Out");
			u.setAccountRole(AccountRole.STUDENT);
			u.setAccountStatus(AccountStatus.ACTIVE);
			return users.save(u);
		});
		assertThatThrownBy(() -> measure(outsider.getId(), 0, 20, 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);

		UserAccount unassignedLecturer = tx.execute(status -> {
			UserAccount u = new UserAccount();
			u.setEmail("lec2-" + UUID.randomUUID() + "@fe.edu.vn");
			u.setFullName("Other Lec");
			u.setAccountRole(AccountRole.LECTURER);
			u.setAccountStatus(AccountStatus.ACTIVE);
			u = users.save(u);
			LecturerProfile lp = new LecturerProfile();
			lp.setUserAccount(u);
			lecturers.save(lp);
			return u;
		});
		assertThatThrownBy(() -> measure(unassignedLecturer.getId(), 0, 20, 0, 50))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.LECTURER_COURSE_FORBIDDEN);

		tx.executeWithoutResult(status -> {
			CourseEnrollment enrollment = enrollments.findAll().stream()
					.filter(e -> e.getStudentProfile().getId().equals(teammateProfile.getId()))
					.findFirst()
					.orElseThrow();
			enrollment.setEnrollmentStatus(EnrollmentStatus.WITHDRAWN);
			enrollments.save(enrollment);
		});
		assertThatThrownBy(() -> measure(teammate.getId(), 0, 20, 0, 50))
				.isInstanceOf(IntegrationException.class)
				.extracting(ex -> ((IntegrationException) ex).getCode())
				.isEqualTo(IntegrationErrorCode.INTEGRATION_FORBIDDEN);
	}

	@Test
	void softDeletedAndForeignTask_notFound() {
		UUID deletedId = tx.execute(status -> {
			Task soft = new Task();
			soft.setProject(project);
			soft.setTitle("Gone");
			soft.setStatus(TaskStatus.TODO);
			soft.setExternalKey("SAGA-GONE");
			soft.setDeletedAt(LocalDateTime.now());
			return tasks.save(soft).getId();
		});
		assertThatThrownBy(() -> measure(student.getId(), project.getId(), deletedId, 0, 20, 0, 50))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);

		assertThatThrownBy(
						() -> measure(student.getId(), project.getId(), otherProjectTask.getId(), 0, 20, 0, 50))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.PROJECT_NOT_FOUND);
	}

	@Test
	void sessions_multiUserLegacyOpenTotalsPagingAndDirtyElapsed() {
		LocalDateTime t0 = LocalDateTime.of(2026, 9, 14, 10, 0, 0);
		tx.executeWithoutResult(status -> {
			persistSession(student, t0.minusHours(3), t0.minusHours(2), WorkSessionStatus.STOPPED);
			persistSession(teammate, t0.minusHours(1), null, WorkSessionStatus.OPEN);
			persistSession(student, t0.minusMinutes(30), null, WorkSessionStatus.OPEN);
			persistSession(student, t0.minusMinutes(20), null, WorkSessionStatus.OPEN);
			// Dirty OPEN + endedAt: status authoritative for open list; elapsed uses endedAt.
			TaskWorkSession dirtyOpen = persistSession(student, t0.minusMinutes(10), t0.minusMinutes(5), WorkSessionStatus.OPEN);
			assertThat(dirtyOpen.getEndedAt()).isNotNull();
			// Dirty STOPPED + null endedAt: elapsed uses captured now (>= 0).
			persistSession(teammate, t0.minusMinutes(2), null, WorkSessionStatus.STOPPED);
			// Negative clamp: ended before start.
			persistSession(student, t0, t0.minusMinutes(1), WorkSessionStatus.STOPPED);
		});

		TaskWorkSessionTimelineResponse view = measure(student.getId(), 0, 3, 0, 50).response;
		assertThat(view.workSessions().sessionCount()).isEqualTo(7);
		assertThat(view.workSessions().totalElements()).isEqualTo(7);
		assertThat(view.workSessions().openSessions()).hasSize(4);
		assertThat(view.workSessions().openSessions())
				.extracting(TaskWorkSessionTimelineResponse.SessionItem::status)
				.containsOnly("OPEN");
		assertThat(view.workSessions().openSessions().get(0).startedAt())
				.isBefore(view.workSessions().openSessions().get(1).startedAt());
		assertThat(view.workSessions().sessions()).hasSize(3);
		assertThat(view.workSessions().sessions().get(0).startedAt())
				.isAfterOrEqualTo(view.workSessions().sessions().get(1).startedAt());
		assertThat(view.workSessions().totalElapsedSeconds()).isGreaterThanOrEqualTo(3600L);

		TaskWorkSessionTimelineResponse.SessionItem dirty = view.workSessions().openSessions().stream()
				.filter(s -> s.endedAt() != null)
				.findFirst()
				.orElseThrow();
		assertThat(dirty.status()).isEqualTo("OPEN");
		assertThat(dirty.elapsedSeconds()).isEqualTo(300L);

		TaskWorkSessionTimelineResponse page1 = measure(student.getId(), 1, 3, 0, 50).response;
		assertThat(page1.workSessions().page()).isEqualTo(1);
		assertThat(page1.workSessions().sessions()).hasSize(3);
		assertThat(page1.workSessions().sessionCount()).isEqualTo(7);
		assertThat(page1.workSessions().totalElapsedSeconds())
				.isEqualTo(view.workSessions().totalElapsedSeconds());
		assertThat(page1.workSessions().openSessions()).hasSize(4);

		TaskWorkSessionTimelineResponse.SessionItem teammateOpen = view.workSessions().openSessions().stream()
				.filter(s -> teammate.getId().equals(s.userId()))
				.findFirst()
				.orElseThrow();
		assertThat(teammateOpen.studentId()).isEqualTo(teammateProfile.getId());
		assertThat(teammateOpen.studentCode()).isEqualTo(teammateProfile.getStudentCode());
		assertThat(teammateOpen.fullName()).isEqualTo("Teammate");
	}

	@Test
	void actorWithoutStudentProfile_staysNull() {
		UserAccount bare = tx.execute(status -> {
			UserAccount u = new UserAccount();
			u.setEmail("bare-" + UUID.randomUUID() + "@fe.edu.vn");
			u.setFullName("Bare User");
			u.setAccountRole(AccountRole.STUDENT);
			u.setAccountStatus(AccountStatus.ACTIVE);
			u = users.save(u);
			persistSession(u, LocalDateTime.of(2026, 9, 14, 9, 0), LocalDateTime.of(2026, 9, 14, 9, 30), WorkSessionStatus.STOPPED);
			return u;
		});
		// Membership still required for reader; use student caller to read bare's session on the task.
		TaskWorkSessionTimelineResponse view = measure(student.getId(), 0, 20, 0, 50).response;
		TaskWorkSessionTimelineResponse.SessionItem bareRow = view.workSessions().sessions().stream()
				.filter(s -> bare.getId().equals(s.userId()))
				.findFirst()
				.orElseThrow();
		assertThat(bareRow.studentId()).isNull();
		assertThat(bareRow.studentCode()).isNull();
		assertThat(bareRow.fullName()).isEqualTo("Bare User");
	}

	@Test
	void commits_v23FilterAuthorReposLinkMetaAndOrdering() {
		tx.executeWithoutResult(status -> {
			GitCommit normal = persistCommit("n1", 1, LocalDateTime.of(2026, 6, 2, 12, 0), "org/demo", repo);
			GitCommit root = persistCommit("r0", 0, LocalDateTime.of(2026, 6, 2, 11, 0), "org/demo", repo);
			GitCommit unknown = persistCommit("u", null, null, "org/other", repoB);
			GitCommit merge = persistCommit("m2", 2, LocalDateTime.of(2026, 6, 2, 13, 0), "org/demo", repo);
			GitCommit unlinked = persistCommit("x", 1, LocalDateTime.of(2026, 6, 2, 14, 0), "org/demo", repo);
			link(task, normal, TraceLinkSource.COMMIT_MESSAGE);
			link(task, root, TraceLinkSource.BRANCH_NAME);
			link(task, unknown, TraceLinkSource.MANUAL);
			link(task, merge, TraceLinkSource.COMMIT_MESSAGE);
			assertThat(unlinked.getId()).isNotNull();
		});

		TaskWorkSessionTimelineResponse view = measure(student.getId(), 0, 20, 0, 50).response;
		assertThat(view.commits().totalElements()).isEqualTo(3);
		assertThat(view.commits().items()).hasSize(3);
		// coalesce(committedAt, createdAt): null committedAt uses insert createdAt (~now) → first.
		assertThat(view.commits().items())
				.extracting(TaskWorkSessionTimelineResponse.CommitItem::sha)
				.containsExactly("u", "n1", "r0");
		assertThat(view.commits().items().get(1).repositoryFullName()).isEqualTo("org/demo");
		assertThat(view.commits().items().get(1).authorStudentId()).isEqualTo(studentProfile.getId());
		assertThat(view.commits().items().get(1).linkSource()).isEqualTo("COMMIT_MESSAGE");
		assertThat(view.commits().items().get(1).linkedAt()).isNotNull();
		assertThat(view.commits().items().get(0).committedAt()).isNull();
		assertThat(view.commits().items().get(0).repositoryFullName()).isEqualTo("org/other");
		assertThat(view.commits().items().get(0).authorStudentId()).isNull();

		TaskWorkSessionTimelineResponse page0 = measure(student.getId(), 0, 20, 0, 2).response;
		assertThat(page0.commits().items()).hasSize(2);
		assertThat(page0.commits().totalElements()).isEqualTo(3);
		assertThat(page0.commits().totalPages()).isEqualTo(2);
		TaskWorkSessionTimelineResponse page1 = measure(student.getId(), 0, 20, 1, 2).response;
		assertThat(page1.commits().items()).hasSize(1);
		assertThat(page1.commits().items().getFirst().sha()).isEqualTo("r0");
	}

	@Test
	void rejectsInvalidPaging() {
		assertThatThrownBy(() -> measure(student.getId(), -1, 20, 0, 50))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThatThrownBy(() -> measure(student.getId(), 0, 101, 0, 50))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
		assertThatThrownBy(() -> measure(student.getId(), 0, 20, 0, 201))
				.isInstanceOf(AcademicException.class)
				.extracting(ex -> ((AcademicException) ex).getCode())
				.isEqualTo(AcademicErrorCode.REQUEST_INVALID);
	}

	@Test
	void dtoHasNoCausalSessionCommitFields() {
		seedSessions(1, true);
		seedLinkedCommits(1, 1);
		TaskWorkSessionTimelineResponse view = measure(student.getId(), 0, 20, 0, 50).response;
		assertThat(view.commits().items().getFirst().getClass().getRecordComponents())
				.extracting(c -> c.getName())
				.doesNotContain("sessionId", "matchingSessionId", "causedBySession", "commitsInSession");
		assertThat(view.workSessions().sessions().getFirst().getClass().getRecordComponents())
				.extracting(c -> c.getName())
				.doesNotContain("role", "contributionScore", "sessionCommitCount");
	}

	@Test
	void equalStartedAt_openAndHistoryOrderedById_pageBoundaryNoDupOrGap() {
		LocalDateTime same = LocalDateTime.of(2026, 9, 14, 12, 0, 0);
		List<UUID> ids = tx.execute(status -> {
			TaskWorkSession a = persistSession(student, same, null, WorkSessionStatus.OPEN);
			TaskWorkSession b = persistSession(teammate, same, null, WorkSessionStatus.OPEN);
			TaskWorkSession c = persistSession(student, same, same.plusMinutes(5), WorkSessionStatus.STOPPED);
			return List.of(a.getId(), b.getId(), c.getId());
		});
		// CHAR(36) id ORDER BY is lexicographic string order (not Java UUID.compareTo).
		java.util.Comparator<UUID> idAsc = java.util.Comparator.comparing(UUID::toString);
		List<UUID> openOnly = ids.subList(0, 2).stream().sorted(idAsc).toList();
		List<UUID> historyDesc = ids.stream().sorted(idAsc.reversed()).toList();

		TaskWorkSessionTimelineResponse all = measure(student.getId(), 0, 20, 0, 50).response;
		assertThat(all.workSessions().openSessions())
				.extracting(TaskWorkSessionTimelineResponse.SessionItem::id)
				.containsExactlyElementsOf(openOnly);
		assertThat(all.workSessions().sessions())
				.extracting(TaskWorkSessionTimelineResponse.SessionItem::id)
				.containsExactlyElementsOf(historyDesc);

		TaskWorkSessionTimelineResponse page0 = measure(student.getId(), 0, 2, 0, 50).response;
		TaskWorkSessionTimelineResponse page1 = measure(student.getId(), 1, 2, 0, 50).response;
		assertThat(page0.workSessions().sessions()).hasSize(2);
		assertThat(page1.workSessions().sessions()).hasSize(1);
		assertThat(page0.workSessions().sessions())
				.extracting(TaskWorkSessionTimelineResponse.SessionItem::id)
				.containsExactly(historyDesc.get(0), historyDesc.get(1));
		assertThat(page1.workSessions().sessions())
				.extracting(TaskWorkSessionTimelineResponse.SessionItem::id)
				.containsExactly(historyDesc.get(2));
		List<UUID> pagedUnion = new java.util.ArrayList<>();
		page0.workSessions().sessions().forEach(s -> pagedUnion.add(s.id()));
		page1.workSessions().sessions().forEach(s -> pagedUnion.add(s.id()));
		assertThat(pagedUnion).containsExactlyElementsOf(historyDesc).doesNotHaveDuplicates();
	}

	@Test
	void equalCommitTimestamps_orderedByCommitIdDesc_pageBoundaryNoDupOrGap() {
		LocalDateTime same = LocalDateTime.of(2026, 6, 2, 15, 0, 0);
		List<UUID> commitIds = tx.execute(status -> {
			GitCommit a = persistCommit("eq-a", 1, same, "org/demo", repo);
			GitCommit b = persistCommit("eq-b", 1, same, "org/demo", repo);
			GitCommit c = persistCommit("eq-c", 1, same, "org/demo", repo);
			for (GitCommit commit : List.of(a, b, c)) {
				entityManager
						.createNativeQuery("update git_commit set created_at = :ts where id = :id")
						.setParameter("ts", same)
						.setParameter("id", commit.getId().toString())
						.executeUpdate();
				link(task, commit, TraceLinkSource.COMMIT_MESSAGE);
			}
			return List.of(a.getId(), b.getId(), c.getId());
		});
		java.util.Comparator<UUID> idAsc = java.util.Comparator.comparing(UUID::toString);
		List<UUID> expectedDesc = commitIds.stream().sorted(idAsc.reversed()).toList();

		TaskWorkSessionTimelineResponse all = measure(student.getId(), 0, 20, 0, 50).response;
		assertThat(all.commits().items())
				.extracting(TaskWorkSessionTimelineResponse.CommitItem::id)
				.containsExactlyElementsOf(expectedDesc);
		assertThat(all.commits().items())
				.extracting(TaskWorkSessionTimelineResponse.CommitItem::committedAt)
				.containsOnly(same);

		TaskWorkSessionTimelineResponse page0 = measure(student.getId(), 0, 20, 0, 2).response;
		TaskWorkSessionTimelineResponse page1 = measure(student.getId(), 0, 20, 1, 2).response;
		assertThat(page0.commits().items()).hasSize(2);
		assertThat(page1.commits().items()).hasSize(1);
		assertThat(page0.commits().items())
				.extracting(TaskWorkSessionTimelineResponse.CommitItem::id)
				.containsExactly(expectedDesc.get(0), expectedDesc.get(1));
		assertThat(page1.commits().items())
				.extracting(TaskWorkSessionTimelineResponse.CommitItem::id)
				.containsExactly(expectedDesc.get(2));
		List<UUID> pagedUnion = new java.util.ArrayList<>();
		page0.commits().items().forEach(i -> pagedUnion.add(i.id()));
		page1.commits().items().forEach(i -> pagedUnion.add(i.id()));
		assertThat(pagedUnion).containsExactlyElementsOf(expectedDesc).doesNotHaveDuplicates();
	}

	private Measured measure(UUID userId, int sessionPage, int sessionSize, int commitPage, int commitSize) {
		return measure(userId, project.getId(), task.getId(), sessionPage, sessionSize, commitPage, commitSize);
	}

	private Measured measure(
			UUID userId,
			UUID projectId,
			UUID taskId,
			int sessionPage,
			int sessionSize,
			int commitPage,
			int commitSize) {
		Statistics stats = statistics();
		stats.clear();
		TaskWorkSessionTimelineResponse response = tx.execute(status -> service.get(
				userId, projectId, taskId, sessionPage, sessionSize, commitPage, commitSize));
		return new Measured(response, stats.getPrepareStatementCount());
	}

	private void seedSessions(int count, boolean open) {
		tx.executeWithoutResult(status -> {
			LocalDateTime base = LocalDateTime.of(2026, 9, 1, 8, 0);
			for (int i = 0; i < count; i++) {
				LocalDateTime start = base.plusMinutes(i);
				if (open) {
					persistSession(student, start, null, WorkSessionStatus.OPEN);
				} else {
					persistSession(student, start, start.plusMinutes(15), WorkSessionStatus.STOPPED);
				}
			}
		});
	}

	private void seedLinkedCommits(int count, Integer parentCount) {
		tx.executeWithoutResult(status -> {
			LocalDateTime base = LocalDateTime.of(2026, 6, 1, 10, 0);
			for (int i = 0; i < count; i++) {
				GitCommit commit =
						persistCommit("sha-" + UUID.randomUUID(), parentCount, base.plusMinutes(i), "org/demo", repo);
				link(task, commit, TraceLinkSource.COMMIT_MESSAGE);
			}
		});
	}

	private TaskWorkSession persistSession(
			UserAccount user, LocalDateTime startedAt, LocalDateTime endedAt, WorkSessionStatus status) {
		TaskWorkSession session = new TaskWorkSession();
		session.setTask(tasks.findById(task.getId()).orElseThrow());
		session.setUser(users.findById(user.getId()).orElseThrow());
		session.setProject(projects.findById(project.getId()).orElseThrow());
		session.setStartedAt(startedAt);
		session.setEndedAt(endedAt);
		session.setStatus(status);
		return sessions.save(session);
	}

	private GitCommit persistCommit(
			String sha, Integer parentCount, LocalDateTime committedAt, String fullName, GitRepo target) {
		GitCommit commit = new GitCommit();
		commit.setRepo(repos.findById(target.getId()).orElseThrow());
		if ("org/demo".equals(fullName)) {
			commit.setAuthorStudent(students.findById(studentProfile.getId()).orElseThrow());
		}
		commit.setShaHash(sha);
		commit.setMessage("msg " + sha);
		commit.setParentCount(parentCount);
		commit.setCommittedAt(committedAt);
		return commits.save(commit);
	}

	private void link(Task owner, GitCommit commit, TraceLinkSource source) {
		TaskGitCommitLink row = new TaskGitCommitLink();
		row.setTask(tasks.findById(owner.getId()).orElseThrow());
		row.setGitCommit(commits.findById(commit.getId()).orElseThrow());
		row.setLinkSource(source);
		links.save(row);
	}

	private void seedGraph() {
		student = saveUser("student", AccountRole.STUDENT, "Student One");
		teammate = saveUser("mate", AccountRole.STUDENT, "Teammate");
		lecturerUser = saveUser("lec", AccountRole.LECTURER, "Lecturer");
		adminUser = saveUser("admin", AccountRole.ADMIN, "Admin");

		studentProfile = saveStudent(student, "SE100001");
		teammateProfile = saveStudent(teammate, "SE100002");
		LecturerProfile lecturerProfile = new LecturerProfile();
		lecturerProfile.setUserAccount(lecturerUser);
		lecturers.save(lecturerProfile);

		String suffix = UUID.randomUUID().toString().substring(0, 8);
		Semester semester = new Semester();
		semester.setCode("FA" + suffix);
		semester.setName("Fall");
		semester = semesters.save(semester);

		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + suffix);
		academicClass.setName("SE" + suffix);
		academicClass = classes.save(academicClass);

		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + suffix);
		subject.setName("Software Project");
		subject.setStatus(SubjectStatus.ACTIVE);
		subject = subjects.save(subject);

		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("1.0");
		syllabus.setStatus(SyllabusStatus.PUBLISHED);
		entityManager.persist(syllabus);

		course = new Course();
		course.setName("SWP");
		course.setSubject(subject);
		course.setAcademicClass(academicClass);
		course.setSemester(semester);
		course.setSyllabusVersion(syllabus);
		course.setInstructor(lecturerProfile);
		course = courses.save(course);

		CourseEnrollment enrollment = enroll(studentProfile, course);
		CourseEnrollment mateEnrollment = enroll(teammateProfile, course);

		project = new Project();
		project.setName("Saga");
		project.setCourse(course);
		project.setCreatedBy(student);
		project = projects.save(project);

		Team team = new Team();
		team.setCourse(course);
		team.setProject(project);
		team.setTeamNo(1);
		team.setName("Alpha");
		team = teams.save(team);

		saveMember(team, course, enrollment, RoleInTeam.MEMBER);
		saveMember(team, course, mateEnrollment, RoleInTeam.MEMBER);

		repo = saveRepo("org", "demo", "org/demo");
		repoB = saveRepo("org", "other", "org/other");

		task = new Task();
		task.setProject(project);
		task.setTitle("Login");
		task.setStatus(TaskStatus.TODO);
		task.setExternalKey("SAGA-1");
		task = tasks.save(task);

		Project other = new Project();
		other.setName("Other");
		other.setCourse(course);
		other.setCreatedBy(student);
		other = projects.save(other);
		otherProjectTask = new Task();
		otherProjectTask.setProject(other);
		otherProjectTask.setTitle("Foreign");
		otherProjectTask.setStatus(TaskStatus.TODO);
		otherProjectTask.setExternalKey("OTH-1");
		otherProjectTask = tasks.save(otherProjectTask);
	}

	private UserAccount saveUser(String prefix, AccountRole role, String name) {
		UserAccount u = new UserAccount();
		u.setEmail(prefix + "-" + UUID.randomUUID() + "@fe.edu.vn");
		u.setFullName(name);
		u.setAccountRole(role);
		u.setAccountStatus(AccountStatus.ACTIVE);
		return users.save(u);
	}

	private StudentProfile saveStudent(UserAccount user, String code) {
		StudentProfile p = new StudentProfile();
		p.setUserAccount(user);
		p.setStudentCode(code + UUID.randomUUID().toString().substring(0, 4));
		p.setVersion(0L);
		return students.save(p);
	}

	private CourseEnrollment enroll(StudentProfile profile, Course course) {
		CourseEnrollment enrollment = new CourseEnrollment();
		enrollment.setStudentProfile(profile);
		enrollment.setCourse(course);
		enrollment.setEnrollmentStatus(EnrollmentStatus.ACTIVE);
		enrollment.setEnrolledAt(LocalDateTime.now());
		return enrollments.save(enrollment);
	}

	private void saveMember(Team team, Course course, CourseEnrollment enrollment, RoleInTeam role) {
		TeamMember member = new TeamMember();
		member.setTeam(team);
		member.setCourse(course);
		member.setCourseEnrollment(enrollment);
		member.setRoleInTeam(role);
		members.save(member);
	}

	private GitRepo saveRepo(String owner, String name, String fullName) {
		GitRepo r = new GitRepo();
		r.setProject(project);
		r.setProvider(GitProvider.GITHUB);
		r.setRepositoryId(System.nanoTime());
		r.setOwnerLogin(owner);
		r.setName(name);
		r.setFullName(fullName);
		r.setConnectionStatus(IntegrationStatus.ACTIVE);
		r.setConsecutiveFailures(0);
		return repos.save(r);
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private record Measured(TaskWorkSessionTimelineResponse response, long queries) {}
}

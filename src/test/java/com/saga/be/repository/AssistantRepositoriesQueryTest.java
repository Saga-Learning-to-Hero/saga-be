package com.saga.be.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.entity.academic.AcademicClass;
import com.saga.be.entity.academic.Course;
import com.saga.be.entity.academic.Semester;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.account.UserAccount;
import com.saga.be.entity.assistant.AssistantConversation;
import com.saga.be.entity.assistant.AssistantMessage;
import com.saga.be.entity.enums.AccountRole;
import com.saga.be.entity.enums.AccountStatus;
import com.saga.be.entity.project.Project;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/** Real H2, real JPQL for the project assistant: ownership, ordering and the daily question count. */
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
class AssistantRepositoriesQueryTest {

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

	@Autowired private UserAccountRepository users;
	@Autowired private SubjectRepository subjects;
	@Autowired private AcademicClassRepository academicClasses;
	@Autowired private SemesterRepository semesters;
	@Autowired private CourseRepository courses;
	@Autowired private ProjectRepository projects;
	@Autowired private AssistantConversationRepository conversations;
	@Autowired private AssistantMessageRepository messages;
	@Autowired private TestEntityManager entityManager;

	private UserAccount me;
	private UserAccount other;
	private Project project;
	private Project otherProject;

	@BeforeEach
	void setUp() {
		me = users.save(account());
		other = users.save(account());
		Semester semester = new Semester();
		semester.setCode("SEM-" + UUID.randomUUID());
		semester.setName("Semester");
		semester = semesters.save(semester);
		AcademicClass academicClass = new AcademicClass();
		academicClass.setSemester(semester);
		academicClass.setClassCode("SE" + UUID.randomUUID().toString().substring(0, 6));
		academicClass.setName("Class");
		academicClass = academicClasses.save(academicClass);
		Subject subject = new Subject();
		subject.setSubjectCode("SWP" + UUID.randomUUID().toString().substring(0, 8));
		subject.setName("Software Project");
		subject = subjects.save(subject);
		Course course = new Course();
		course.setName("Course");
		course.setAcademicClass(academicClass);
		course.setSubject(subject);
		course.setSemester(semester);
		course = courses.save(course);
		project = project(course, "Smart Library");
		otherProject = project(course, "Other");
	}

	@Test
	void aConversationIsOnlyFoundByItsOwnerInItsProject() {
		AssistantConversation mine = conversations.save(conversation(me, project, null));

		assertThat(conversations.findOwned(mine.getId(), project.getId(), me.getId())).isPresent();
		assertThat(conversations.findOwned(mine.getId(), project.getId(), other.getId())).isEmpty();
		assertThat(conversations.findOwned(mine.getId(), otherProject.getId(), me.getId())).isEmpty();
	}

	@Test
	void conversationsAreListedMostRecentlyUsedFirstAndOnlyTheOwners() {
		AssistantConversation older = conversations.save(conversation(me, project, LocalDateTime.of(2026, 10, 1, 9, 0)));
		AssistantConversation newer = conversations.save(conversation(me, project, LocalDateTime.of(2026, 10, 3, 9, 0)));
		conversations.save(conversation(other, project, LocalDateTime.of(2026, 10, 4, 9, 0)));
		conversations.save(conversation(me, otherProject, LocalDateTime.of(2026, 10, 4, 9, 0)));

		assertThat(conversations.findOwnedByProject(project.getId(), me.getId()))
				.extracting(AssistantConversation::getId)
				.containsExactly(newer.getId(), older.getId());
	}

	@Test
	void messagesComeOldestFirstAndQuestionsAreCountedPerUserSince() {
		AssistantConversation mine = conversations.save(conversation(me, project, null));
		AssistantConversation mineElsewhere = conversations.save(conversation(me, otherProject, null));
		AssistantConversation theirs = conversations.save(conversation(other, project, null));
		AssistantMessage first = messages.save(message(mine, AssistantMessage.Role.USER, "Câu một"));
		AssistantMessage reply = messages.save(message(mine, AssistantMessage.Role.ASSISTANT, "Trả lời"));
		AssistantMessage old = messages.save(message(mineElsewhere, AssistantMessage.Role.USER, "Hôm qua"));
		messages.save(message(mineElsewhere, AssistantMessage.Role.USER, "Ở dự án khác"));
		messages.save(message(theirs, AssistantMessage.Role.USER, "Của người khác"));
		entityManager.flush();
		backdate(first, LocalDateTime.of(2026, 10, 3, 8, 0));
		backdate(reply, LocalDateTime.of(2026, 10, 3, 8, 1));
		backdate(old, LocalDateTime.of(2026, 10, 1, 8, 0));
		entityManager.clear();

		assertThat(messages.findByConversation(mine.getId()))
				.extracting(AssistantMessage::getContent)
				.containsExactly("Câu một", "Trả lời");
		// my questions in every project since the cut-off; answers and other people's questions do not count
		assertThat(messages.countQuestionsSince(me.getId(), LocalDateTime.of(2026, 10, 2, 8, 0))).isEqualTo(2);
		assertThat(messages.countQuestionsSince(me.getId(), LocalDateTime.of(2026, 9, 30, 0, 0))).isEqualTo(3);
		assertThat(messages.countQuestionsSince(other.getId(), LocalDateTime.of(2026, 9, 30, 0, 0))).isEqualTo(1);
	}

	@Test
	void feedbackTargetsOnlyTheOwnersAnswers() {
		AssistantConversation mine = conversations.save(conversation(me, project, null));
		AssistantMessage question = messages.save(message(mine, AssistantMessage.Role.USER, "Hỏi"));
		AssistantMessage answer = messages.save(message(mine, AssistantMessage.Role.ASSISTANT, "Đáp"));
		answer.setCitationsJson("[{\"kind\":\"TASK\"}]");
		answer.setVerified(true);
		answer.setRemovedCitationCount(0);
		messages.save(answer);
		entityManager.flush();
		entityManager.clear();

		assertThat(messages.findOwnedAnswer(answer.getId(), project.getId(), me.getId()))
				.hasValueSatisfying(found -> {
					assertThat(found.getVerified()).isTrue();
					assertThat(found.getCitationsJson()).contains("TASK");
				});
		assertThat(messages.findOwnedAnswer(question.getId(), project.getId(), me.getId())).isEmpty();
		assertThat(messages.findOwnedAnswer(answer.getId(), project.getId(), other.getId())).isEmpty();
		assertThat(messages.findOwnedAnswer(answer.getId(), otherProject.getId(), me.getId())).isEmpty();
	}

	// ------------------------------------------------------------------ fixtures

	private void backdate(AssistantMessage message, LocalDateTime at) {
		entityManager.getEntityManager()
				.createNativeQuery("update assistant_message set created_at = :at where id = :id")
				.setParameter("at", at)
				.setParameter("id", message.getId().toString())
				.executeUpdate();
	}

	private Project project(Course course, String name) {
		Project row = new Project();
		row.setName(name);
		row.setCourse(course);
		return projects.save(row);
	}

	private static AssistantConversation conversation(UserAccount owner, Project project, LocalDateTime lastMessageAt) {
		AssistantConversation conversation = new AssistantConversation();
		conversation.setUserAccount(owner);
		conversation.setProject(project);
		conversation.setLastMessageAt(lastMessageAt);
		return conversation;
	}

	private static AssistantMessage message(AssistantConversation conversation, AssistantMessage.Role role, String content) {
		AssistantMessage message = new AssistantMessage();
		message.setConversation(conversation);
		message.setRole(role);
		message.setContent(content);
		return message;
	}

	private static UserAccount account() {
		UserAccount account = new UserAccount();
		account.setEmail("student-" + UUID.randomUUID() + "@fpt.edu.vn");
		account.setFullName("Student");
		account.setAccountRole(AccountRole.STUDENT);
		account.setAccountStatus(AccountStatus.ACTIVE);
		return account;
	}
}

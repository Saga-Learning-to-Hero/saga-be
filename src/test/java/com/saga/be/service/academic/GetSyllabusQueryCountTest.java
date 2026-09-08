package com.saga.be.service.academic;

import static org.assertj.core.api.Assertions.assertThat;

import com.saga.be.dto.academic.SyllabusDetailResponse;
import com.saga.be.entity.academic.Subject;
import com.saga.be.entity.academic.SubjectSyllabusVersion;
import com.saga.be.entity.academic.SyllabusDeliverableLearningOutcome;
import com.saga.be.entity.academic.SyllabusExpectedActivity;
import com.saga.be.entity.academic.SyllabusExpectedDeliverable;
import com.saga.be.entity.academic.SyllabusLearningOutcome;
import com.saga.be.entity.academic.SyllabusLearningUnit;
import com.saga.be.entity.academic.SyllabusLearningUnitOutcome;
import com.saga.be.entity.academic.SyllabusPhase;
import com.saga.be.entity.academic.SyllabusPhaseLearningOutcome;
import com.saga.be.entity.enums.SubjectStatus;
import com.saga.be.entity.enums.SyllabusStatus;
import com.saga.be.repository.SubjectRepository;
import com.saga.be.repository.SubjectSyllabusVersionRepository;
import com.saga.be.repository.SyllabusDeliverableLearningOutcomeRepository;
import com.saga.be.repository.SyllabusExpectedActivityRepository;
import com.saga.be.repository.SyllabusExpectedDeliverableRepository;
import com.saga.be.repository.SyllabusLearningOutcomeRepository;
import com.saga.be.repository.SyllabusLearningUnitOutcomeRepository;
import com.saga.be.repository.SyllabusLearningUnitRepository;
import com.saga.be.repository.SyllabusPhaseLearningOutcomeRepository;
import com.saga.be.repository.SyllabusPhaseRepository;
import com.saga.be.service.audit.AuditService;
import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * Proves getSyllabus uses a fixed multi-query plan (JOIN FETCH on associations), not
 * child-count N+1. Does not measure production RTT.
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
@Transactional
@Import(GetSyllabusQueryCountTest.CatalogBeans.class)
class GetSyllabusQueryCountTest {

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

	@org.springframework.boot.test.context.TestConfiguration
	static class CatalogBeans {
		@org.springframework.context.annotation.Bean
		JpaAcademicCatalogStore jpaAcademicCatalogStore(
				SubjectRepository subjects,
				SubjectSyllabusVersionRepository syllabi,
				SyllabusLearningOutcomeRepository outcomes,
				SyllabusLearningUnitRepository learningUnits,
				SyllabusPhaseRepository phases,
				SyllabusExpectedActivityRepository activities,
				SyllabusExpectedDeliverableRepository deliverables,
				SyllabusPhaseLearningOutcomeRepository phaseOutcomeLinks,
				SyllabusDeliverableLearningOutcomeRepository deliverableOutcomeLinks,
				SyllabusLearningUnitOutcomeRepository unitOutcomeLinks,
				EntityManager entityManager) {
			return new JpaAcademicCatalogStore(
					subjects,
					syllabi,
					outcomes,
					learningUnits,
					phases,
					activities,
					deliverables,
					phaseOutcomeLinks,
					deliverableOutcomeLinks,
					unitOutcomeLinks,
					entityManager);
		}
	}

	@Autowired
	private JpaAcademicCatalogStore store;
	@Autowired
	private EntityManager entityManager;

	@Test
	void getSyllabusQueryCountDoesNotGrowWithChildCardinality() {
		AcademicCatalogService service = new AcademicCatalogService(store, Mockito.mock(AuditService.class));
		Prepared small = persistSyllabus(1, 1, 1, 1, 1);
		entityManager.flush();
		entityManager.clear();

		Statistics stats = statistics();
		stats.clear();
		SyllabusDetailResponse first = service.getSyllabus(small.subjectId(), small.syllabusId());
		assertThat(first.phases()).hasSize(1);
		long queriesSmall = stats.getPrepareStatementCount();
		assertThat(queriesSmall).as("fixed plan for small syllabus").isEqualTo(10L);

		Prepared large = persistSyllabus(5, 5, 3, 6, 6);
		entityManager.flush();
		entityManager.clear();
		stats.clear();
		SyllabusDetailResponse second = service.getSyllabus(large.subjectId(), large.syllabusId());
		assertThat(second.learningOutcomes()).hasSize(5);
		assertThat(second.phases()).hasSize(3);
		assertThat(stats.getPrepareStatementCount())
				.as("same fixed query count for larger syllabus")
				.isEqualTo(queriesSmall);
	}

	private Prepared persistSyllabus(int outcomes, int units, int phases, int activities, int deliverables) {
		Subject subject = new Subject();
		subject.setSubjectCode("SUB-" + System.nanoTime());
		subject.setName("Subject");
		subject.setStatus(SubjectStatus.ACTIVE);
		entityManager.persist(subject);

		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("v-" + System.nanoTime());
		syllabus.setStatus(SyllabusStatus.DRAFT);
		syllabus.setTitleEnglish("Title");
		entityManager.persist(syllabus);

		java.util.List<SyllabusLearningOutcome> outcomeRows = new java.util.ArrayList<>();
		for (int i = 0; i < outcomes; i++) {
			SyllabusLearningOutcome o = new SyllabusLearningOutcome();
			o.setSyllabusVersion(syllabus);
			o.setCode("LO" + i);
			o.setName("Outcome " + i);
			o.setOrderIndex(i + 1);
			entityManager.persist(o);
			outcomeRows.add(o);
		}
		java.util.List<SyllabusLearningUnit> unitRows = new java.util.ArrayList<>();
		for (int i = 0; i < units; i++) {
			SyllabusLearningUnit u = new SyllabusLearningUnit();
			u.setSyllabusVersion(syllabus);
			u.setCode("U" + i);
			u.setName("Unit " + i);
			u.setOrderIndex(i + 1);
			entityManager.persist(u);
			unitRows.add(u);
		}
		java.util.List<SyllabusPhase> phaseRows = new java.util.ArrayList<>();
		for (int i = 0; i < phases; i++) {
			SyllabusPhase p = new SyllabusPhase();
			p.setSyllabusVersion(syllabus);
			p.setCode("P" + i);
			p.setName("Phase " + i);
			p.setOrderIndex(i + 1);
			entityManager.persist(p);
			phaseRows.add(p);
		}
		for (int i = 0; i < activities; i++) {
			SyllabusExpectedActivity a = new SyllabusExpectedActivity();
			a.setSyllabusVersion(syllabus);
			a.setPhase(phaseRows.get(i % phaseRows.size()));
			a.setCode("A" + i);
			a.setName("Activity " + i);
			a.setOrderIndex(i + 1);
			entityManager.persist(a);
		}
		java.util.List<SyllabusExpectedDeliverable> deliverableRows = new java.util.ArrayList<>();
		for (int i = 0; i < deliverables; i++) {
			SyllabusExpectedDeliverable d = new SyllabusExpectedDeliverable();
			d.setSyllabusVersion(syllabus);
			d.setPhase(phaseRows.get(i % phaseRows.size()));
			d.setCode("D" + i);
			d.setName("Deliverable " + i);
			d.setOrderIndex(i + 1);
			entityManager.persist(d);
			deliverableRows.add(d);
		}
		if (!outcomeRows.isEmpty() && !unitRows.isEmpty()) {
			SyllabusLearningUnitOutcome unitLink = new SyllabusLearningUnitOutcome();
			unitLink.setSyllabusVersion(syllabus);
			unitLink.setLearningUnit(unitRows.getFirst());
			unitLink.setLearningOutcome(outcomeRows.getFirst());
			entityManager.persist(unitLink);
		}
		if (!outcomeRows.isEmpty() && !phaseRows.isEmpty()) {
			SyllabusPhaseLearningOutcome phaseLink = new SyllabusPhaseLearningOutcome();
			phaseLink.setSyllabusVersion(syllabus);
			phaseLink.setPhase(phaseRows.getFirst());
			phaseLink.setLearningOutcome(outcomeRows.getFirst());
			entityManager.persist(phaseLink);
		}
		if (!outcomeRows.isEmpty() && !deliverableRows.isEmpty()) {
			SyllabusDeliverableLearningOutcome delLink = new SyllabusDeliverableLearningOutcome();
			delLink.setSyllabusVersion(syllabus);
			delLink.setDeliverable(deliverableRows.getFirst());
			delLink.setLearningOutcome(outcomeRows.getFirst());
			entityManager.persist(delLink);
		}
		return new Prepared(subject.getId(), syllabus.getId());
	}

	private Statistics statistics() {
		SessionFactory sessionFactory = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class);
		Statistics stats = sessionFactory.getStatistics();
		stats.setStatisticsEnabled(true);
		return stats;
	}

	private record Prepared(java.util.UUID subjectId, java.util.UUID syllabusId) {}
}

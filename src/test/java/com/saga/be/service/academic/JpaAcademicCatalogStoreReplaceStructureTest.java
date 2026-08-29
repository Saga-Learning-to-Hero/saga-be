package com.saga.be.service.academic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.saga.be.entity.BaseEntity;
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
import java.util.List;
import java.util.UUID;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.JpaRepository;

class JpaAcademicCatalogStoreReplaceStructureTest {

	private StandardServiceRegistry registry;
	private SessionFactory sessionFactory;
	private Session session;
	private Transaction transaction;

	private SyllabusLearningOutcomeRepository outcomes;
	private SyllabusLearningUnitRepository learningUnits;
	private SyllabusPhaseRepository phases;
	private SyllabusExpectedActivityRepository activities;
	private SyllabusExpectedDeliverableRepository deliverables;
	private SyllabusPhaseLearningOutcomeRepository phaseOutcomeLinks;
	private SyllabusDeliverableLearningOutcomeRepository deliverableOutcomeLinks;
	private SyllabusLearningUnitOutcomeRepository unitOutcomeLinks;
	private JpaAcademicCatalogStore store;

	@BeforeEach
	void setUp() {
		registry = new StandardServiceRegistryBuilder()
				.applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.applySetting("hibernate.connection.driver_class", "org.h2.Driver")
				.applySetting(
						"hibernate.connection.url",
						"jdbc:h2:mem:saga_structure_" + UUID.randomUUID()
								+ ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
				.applySetting("hibernate.connection.username", "sa")
				.applySetting("hibernate.connection.password", "")
				.applySetting("hibernate.hbm2ddl.auto", "create-drop")
				.build();
		sessionFactory = new MetadataSources(registry)
				.addAnnotatedClass(BaseEntity.class)
				.addAnnotatedClass(Subject.class)
				.addAnnotatedClass(SubjectSyllabusVersion.class)
				.addAnnotatedClass(SyllabusLearningOutcome.class)
				.addAnnotatedClass(SyllabusLearningUnit.class)
				.addAnnotatedClass(SyllabusPhase.class)
				.addAnnotatedClass(SyllabusExpectedActivity.class)
				.addAnnotatedClass(SyllabusExpectedDeliverable.class)
				.addAnnotatedClass(SyllabusLearningUnitOutcome.class)
				.addAnnotatedClass(SyllabusPhaseLearningOutcome.class)
				.addAnnotatedClass(SyllabusDeliverableLearningOutcome.class)
				.buildMetadata()
				.buildSessionFactory();
		session = sessionFactory.openSession();
		transaction = session.beginTransaction();
		outcomes = mergeIfSaved(mock(SyllabusLearningOutcomeRepository.class));
		learningUnits = mergeIfSaved(mock(SyllabusLearningUnitRepository.class));
		phases = mergeIfSaved(mock(SyllabusPhaseRepository.class));
		activities = mergeIfSaved(mock(SyllabusExpectedActivityRepository.class));
		deliverables = mergeIfSaved(mock(SyllabusExpectedDeliverableRepository.class));
		phaseOutcomeLinks = mergeIfSaved(mock(SyllabusPhaseLearningOutcomeRepository.class));
		deliverableOutcomeLinks = mergeIfSaved(mock(SyllabusDeliverableLearningOutcomeRepository.class));
		unitOutcomeLinks = mergeIfSaved(mock(SyllabusLearningUnitOutcomeRepository.class));
		store = new JpaAcademicCatalogStore(
				mock(SubjectRepository.class),
				mock(SubjectSyllabusVersionRepository.class),
				outcomes,
				learningUnits,
				phases,
				activities,
				deliverables,
				phaseOutcomeLinks,
				deliverableOutcomeLinks,
				unitOutcomeLinks,
				session);
	}

	@AfterEach
	void tearDown() {
		if (transaction != null && transaction.isActive()) {
			transaction.rollback();
		}
		if (session != null) {
			session.close();
		}
		if (sessionFactory != null) {
			sessionFactory.close();
		}
		if (registry != null) {
			StandardServiceRegistryBuilder.destroy(registry);
		}
	}

	@Test
	void replaceStructureInsertsApplicationAssignedUuidsInsteadOfMerging() {
		SubjectSyllabusVersion syllabus = seedDraftSyllabus();
		SyllabusLearningOutcome outcome = outcome(syllabus, "LO1", "Analyze software requirements", 1);
		SyllabusLearningUnit unit = unit(syllabus, "REQUIREMENTS", "Software Requirements", 1);
		SyllabusPhase phase = phase(syllabus, "REQUIREMENT", "Requirement", 1);
		SyllabusExpectedActivity activity = activity(syllabus, phase, "REQ_ANALYSIS", "Analyze requirements", 1);
		SyllabusExpectedDeliverable deliverable =
				deliverable(syllabus, phase, "SRS", "Software Requirements Specification", 1);
		SyllabusLearningUnitOutcome unitLink = unitLink(syllabus, unit, outcome);
		SyllabusPhaseLearningOutcome phaseLink = phaseLink(syllabus, phase, outcome);
		SyllabusDeliverableLearningOutcome deliverableLink = deliverableLink(syllabus, deliverable, outcome);

		store.replaceStructure(
				syllabus.getId(),
				List.of(outcome),
				List.of(unit),
				List.of(phase),
				List.of(activity),
				List.of(deliverable),
				List.of(phaseLink),
				List.of(deliverableLink),
				List.of(unitLink));

		session.clear();
		assertNotNull(session.find(SyllabusLearningOutcome.class, outcome.getId()));
		assertNotNull(session.find(SyllabusLearningUnit.class, unit.getId()));
		assertNotNull(session.find(SyllabusPhase.class, phase.getId()));
		assertNotNull(session.find(SyllabusExpectedActivity.class, activity.getId()));
		assertNotNull(session.find(SyllabusExpectedDeliverable.class, deliverable.getId()));
		assertNotNull(session.find(SyllabusLearningUnitOutcome.class, unitLink.getId()));
		assertNotNull(session.find(SyllabusPhaseLearningOutcome.class, phaseLink.getId()));
		assertNotNull(session.find(SyllabusDeliverableLearningOutcome.class, deliverableLink.getId()));
		assertEquals("LO1", session.find(SyllabusLearningOutcome.class, outcome.getId()).getCode());
		assertEquals(phase.getId(), session.find(SyllabusExpectedActivity.class, activity.getId()).getPhaseId());
		assertEquals(outcome.getId(), session.find(SyllabusLearningUnitOutcome.class, unitLink.getId()).getLearningOutcomeId());
		verify(outcomes, never()).saveAll(any());
		verify(learningUnits, never()).saveAll(any());
		verify(phases, never()).saveAll(any());
		verify(activities, never()).saveAll(any());
		verify(deliverables, never()).saveAll(any());
		verify(phaseOutcomeLinks, never()).saveAll(any());
		verify(deliverableOutcomeLinks, never()).saveAll(any());
		verify(unitOutcomeLinks, never()).saveAll(any());
	}

	private SubjectSyllabusVersion seedDraftSyllabus() {
		Subject subject = new Subject();
		subject.setId(UUID.randomUUID());
		subject.setSubjectCode("SWP391");
		subject.setName("Software Development Project");
		subject.setStatus(SubjectStatus.ACTIVE);
		session.persist(subject);
		SubjectSyllabusVersion syllabus = new SubjectSyllabusVersion();
		syllabus.setId(UUID.randomUUID());
		syllabus.setSubject(subject);
		syllabus.setVersionLabel("2026-v1");
		syllabus.setStatus(SyllabusStatus.DRAFT);
		session.persist(syllabus);
		session.flush();
		return syllabus;
	}

	private static SyllabusLearningOutcome outcome(
			SubjectSyllabusVersion syllabus, String code, String name, int order) {
		SyllabusLearningOutcome row = new SyllabusLearningOutcome();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setCode(code);
		row.setName(name);
		row.setOrderIndex(order);
		return row;
	}

	private static SyllabusLearningUnit unit(SubjectSyllabusVersion syllabus, String code, String name, int order) {
		SyllabusLearningUnit row = new SyllabusLearningUnit();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setCode(code);
		row.setName(name);
		row.setOrderIndex(order);
		return row;
	}

	private static SyllabusPhase phase(SubjectSyllabusVersion syllabus, String code, String name, int order) {
		SyllabusPhase row = new SyllabusPhase();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setCode(code);
		row.setName(name);
		row.setOrderIndex(order);
		return row;
	}

	private static SyllabusExpectedActivity activity(
			SubjectSyllabusVersion syllabus, SyllabusPhase phase, String code, String name, int order) {
		SyllabusExpectedActivity row = new SyllabusExpectedActivity();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setPhase(phase);
		row.setCode(code);
		row.setName(name);
		row.setOrderIndex(order);
		return row;
	}

	private static SyllabusExpectedDeliverable deliverable(
			SubjectSyllabusVersion syllabus, SyllabusPhase phase, String code, String name, int order) {
		SyllabusExpectedDeliverable row = new SyllabusExpectedDeliverable();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setPhase(phase);
		row.setCode(code);
		row.setName(name);
		row.setOrderIndex(order);
		return row;
	}

	private static SyllabusLearningUnitOutcome unitLink(
			SubjectSyllabusVersion syllabus, SyllabusLearningUnit unit, SyllabusLearningOutcome outcome) {
		SyllabusLearningUnitOutcome row = new SyllabusLearningUnitOutcome();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setLearningUnit(unit);
		row.setLearningOutcome(outcome);
		return row;
	}

	private static SyllabusPhaseLearningOutcome phaseLink(
			SubjectSyllabusVersion syllabus, SyllabusPhase phase, SyllabusLearningOutcome outcome) {
		SyllabusPhaseLearningOutcome row = new SyllabusPhaseLearningOutcome();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setPhase(phase);
		row.setLearningOutcome(outcome);
		return row;
	}

	private static SyllabusDeliverableLearningOutcome deliverableLink(
			SubjectSyllabusVersion syllabus,
			SyllabusExpectedDeliverable deliverable,
			SyllabusLearningOutcome outcome) {
		SyllabusDeliverableLearningOutcome row = new SyllabusDeliverableLearningOutcome();
		row.setId(UUID.randomUUID());
		row.setSyllabusVersion(syllabus);
		row.setDeliverable(deliverable);
		row.setLearningOutcome(outcome);
		return row;
	}

	@SuppressWarnings("unchecked")
	private <T, R extends JpaRepository<T, UUID>> R mergeIfSaved(R repository) {
		org.mockito.Mockito.when(repository.saveAll(any())).thenAnswer(invocation -> {
			Iterable<T> entities = invocation.getArgument(0);
			for (T entity : entities) {
				session.merge(entity);
			}
			session.flush();
			return entities instanceof List<?> list ? list : List.of();
		});
		return repository;
	}
}

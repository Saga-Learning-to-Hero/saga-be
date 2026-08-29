package com.saga.be.service.academic;

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
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
@Profile("!test")
public class JpaAcademicCatalogStore implements AcademicCatalogStore {

	private final SubjectRepository subjects;
	private final SubjectSyllabusVersionRepository syllabi;
	private final SyllabusLearningOutcomeRepository outcomes;
	private final SyllabusLearningUnitRepository learningUnits;
	private final SyllabusPhaseRepository phases;
	private final SyllabusExpectedActivityRepository activities;
	private final SyllabusExpectedDeliverableRepository deliverables;
	private final SyllabusPhaseLearningOutcomeRepository phaseOutcomeLinks;
	private final SyllabusDeliverableLearningOutcomeRepository deliverableOutcomeLinks;
	private final SyllabusLearningUnitOutcomeRepository unitOutcomeLinks;
	private final EntityManager entityManager;

	public JpaAcademicCatalogStore(
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
		this.subjects = subjects;
		this.syllabi = syllabi;
		this.outcomes = outcomes;
		this.learningUnits = learningUnits;
		this.phases = phases;
		this.activities = activities;
		this.deliverables = deliverables;
		this.phaseOutcomeLinks = phaseOutcomeLinks;
		this.deliverableOutcomeLinks = deliverableOutcomeLinks;
		this.unitOutcomeLinks = unitOutcomeLinks;
		this.entityManager = entityManager;
	}

	@Override
	public Optional<Subject> findSubjectById(UUID id) {
		return subjects.findById(id);
	}

	@Override
	public Optional<Subject> findSubjectByCode(String code) {
		return subjects.findBySubjectCode(code);
	}

	@Override
	public boolean existsSubjectCode(String code) {
		return subjects.existsBySubjectCode(code);
	}

	@Override
	public List<Subject> listSubjects(String code, SubjectStatus status, String search) {
		String q = StringUtils.hasText(search) ? search.trim() : null;
		return subjects.search(code, status, q);
	}

	@Override
	public Subject saveSubject(Subject subject) {
		return subjects.save(subject);
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabusById(UUID id) {
		return syllabi.findById(id);
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabus(UUID subjectId, UUID syllabusId) {
		return syllabi.findByIdAndSubject_Id(syllabusId, subjectId);
	}

	@Override
	public List<SubjectSyllabusVersion> listSyllabi(UUID subjectId) {
		return syllabi.findBySubject_IdOrderByCreatedAtDesc(subjectId);
	}

	@Override
	public boolean versionLabelTaken(UUID subjectId, String versionLabel, UUID excludeSyllabusId) {
		if (excludeSyllabusId == null) {
			return syllabi.existsBySubject_IdAndVersionLabelIgnoreCase(subjectId, versionLabel);
		}
		return syllabi.existsBySubject_IdAndVersionLabelIgnoreCaseAndIdNot(subjectId, versionLabel, excludeSyllabusId);
	}

	@Override
	public boolean externalIdTaken(UUID subjectId, String externalSyllabusId, UUID excludeSyllabusId) {
		if (excludeSyllabusId == null) {
			return syllabi.existsBySubject_IdAndExternalSyllabusId(subjectId, externalSyllabusId);
		}
		return syllabi.existsBySubject_IdAndExternalSyllabusIdAndIdNot(
				subjectId, externalSyllabusId, excludeSyllabusId);
	}

	@Override
	public SubjectSyllabusVersion saveSyllabus(SubjectSyllabusVersion syllabus) {
		return syllabi.save(syllabus);
	}

	@Override
	public List<SyllabusLearningOutcome> listOutcomes(UUID syllabusId) {
		return outcomes.findBySyllabusVersion_IdOrderByOrderIndexAsc(syllabusId);
	}

	@Override
	public List<SyllabusLearningUnit> listLearningUnits(UUID syllabusId) {
		return learningUnits.findBySyllabusVersion_IdOrderByOrderIndexAsc(syllabusId);
	}

	@Override
	public List<SyllabusPhase> listPhases(UUID syllabusId) {
		return phases.findBySyllabusVersion_IdOrderByOrderIndexAsc(syllabusId);
	}

	@Override
	public List<SyllabusExpectedActivity> listActivities(UUID syllabusId) {
		return activities.findBySyllabusVersion_IdOrderByOrderIndexAsc(syllabusId);
	}

	@Override
	public List<SyllabusExpectedDeliverable> listDeliverables(UUID syllabusId) {
		return deliverables.findBySyllabusVersion_IdOrderByOrderIndexAsc(syllabusId);
	}

	@Override
	public List<SyllabusPhaseLearningOutcome> listPhaseOutcomeLinks(UUID syllabusId) {
		return phaseOutcomeLinks.findBySyllabusVersion_Id(syllabusId);
	}

	@Override
	public List<SyllabusDeliverableLearningOutcome> listDeliverableOutcomeLinks(UUID syllabusId) {
		return deliverableOutcomeLinks.findBySyllabusVersion_Id(syllabusId);
	}

	@Override
	public List<SyllabusLearningUnitOutcome> listUnitOutcomeLinks(UUID syllabusId) {
		return unitOutcomeLinks.findBySyllabusVersion_Id(syllabusId);
	}

	@Override
	@Transactional
	public void replaceStructure(
			UUID syllabusId,
			List<SyllabusLearningOutcome> nextOutcomes,
			List<SyllabusLearningUnit> nextUnits,
			List<SyllabusPhase> nextPhases,
			List<SyllabusExpectedActivity> nextActivities,
			List<SyllabusExpectedDeliverable> nextDeliverables,
			List<SyllabusPhaseLearningOutcome> nextPhaseLinks,
			List<SyllabusDeliverableLearningOutcome> nextDeliverableLinks,
			List<SyllabusLearningUnitOutcome> nextUnitLinks) {
		AcademicStructureIntegrity.requireGraph(syllabusId, nextActivities, nextDeliverables, nextPhaseLinks, nextDeliverableLinks, nextUnitLinks);
		unitOutcomeLinks.deleteBySyllabusVersion_Id(syllabusId);
		phaseOutcomeLinks.deleteBySyllabusVersion_Id(syllabusId);
		deliverableOutcomeLinks.deleteBySyllabusVersion_Id(syllabusId);
		activities.deleteBySyllabusVersion_Id(syllabusId);
		deliverables.deleteBySyllabusVersion_Id(syllabusId);
		learningUnits.deleteBySyllabusVersion_Id(syllabusId);
		phases.deleteBySyllabusVersion_Id(syllabusId);
		outcomes.deleteBySyllabusVersion_Id(syllabusId);
		entityManager.flush();
		// persist(), not saveAll(): a non-null @GeneratedValue UUID is treated as detached
		// and EntityManager.merge() throws StaleObjectStateException when the row is new.
		persistNew(nextOutcomes);
		persistNew(nextUnits);
		persistNew(nextPhases);
		entityManager.flush();
		for (SyllabusExpectedActivity activity : nextActivities) {
			activity.setPhase(activity.getPhase());
		}
		for (SyllabusExpectedDeliverable deliverable : nextDeliverables) {
			deliverable.setPhase(deliverable.getPhase());
		}
		persistNew(nextActivities);
		persistNew(nextDeliverables);
		entityManager.flush();
		for (SyllabusLearningUnitOutcome link : nextUnitLinks) {
			link.setLearningUnit(link.getLearningUnit());
			link.setLearningOutcome(link.getLearningOutcome());
		}
		for (SyllabusPhaseLearningOutcome link : nextPhaseLinks) {
			link.setPhase(link.getPhase());
			link.setLearningOutcome(link.getLearningOutcome());
		}
		for (SyllabusDeliverableLearningOutcome link : nextDeliverableLinks) {
			link.setDeliverable(link.getDeliverable());
			link.setLearningOutcome(link.getLearningOutcome());
		}
		persistNew(nextUnitLinks);
		persistNew(nextPhaseLinks);
		persistNew(nextDeliverableLinks);
		entityManager.flush();
	}

	private void persistNew(List<?> entities) {
		if (entities == null || entities.isEmpty()) {
			return;
		}
		for (Object entity : entities) {
			entityManager.persist(entity);
		}
	}
}

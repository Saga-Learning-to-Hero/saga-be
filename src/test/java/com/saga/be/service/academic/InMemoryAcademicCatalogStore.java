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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.util.StringUtils;

final class InMemoryAcademicCatalogStore implements AcademicCatalogStore {

	final Map<UUID, Subject> subjects = new ConcurrentHashMap<>();
	final Map<UUID, SubjectSyllabusVersion> syllabi = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusLearningOutcome>> outcomes = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusLearningUnit>> learningUnits = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusPhase>> phases = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusExpectedActivity>> activities = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusExpectedDeliverable>> deliverables = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusPhaseLearningOutcome>> phaseLinks = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusDeliverableLearningOutcome>> deliverableLinks = new ConcurrentHashMap<>();
	final Map<UUID, List<SyllabusLearningUnitOutcome>> unitLinks = new ConcurrentHashMap<>();

	@Override
	public Optional<Subject> findSubjectById(UUID id) {
		return Optional.ofNullable(subjects.get(id));
	}

	@Override
	public Optional<Subject> findSubjectByCode(String code) {
		return subjects.values().stream().filter(s -> s.getSubjectCode().equals(code)).findFirst();
	}

	@Override
	public boolean existsSubjectCode(String code) {
		return findSubjectByCode(code).isPresent();
	}

	@Override
	public List<Subject> listSubjects(String code, SubjectStatus status, String search) {
		return subjects.values().stream()
				.filter(s -> code == null || code.equals(s.getSubjectCode()))
				.filter(s -> status == null || status == s.getStatus())
				.filter(s -> !StringUtils.hasText(search)
						|| containsIgnoreCase(s.getSubjectCode(), search)
						|| containsIgnoreCase(s.getName(), search)
						|| containsIgnoreCase(s.getNameVietnamese(), search))
				.sorted(Comparator.comparing(Subject::getSubjectCode))
				.toList();
	}

	@Override
	public Subject saveSubject(Subject subject) {
		if (subject.getId() == null) {
			subject.setId(UUID.randomUUID());
		}
		subjects.put(subject.getId(), subject);
		return subject;
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabusById(UUID id) {
		return Optional.ofNullable(syllabi.get(id));
	}

	@Override
	public Optional<SubjectSyllabusVersion> findSyllabus(UUID subjectId, UUID syllabusId) {
		return findSyllabusById(syllabusId)
				.filter(s -> s.getSubject() != null && subjectId.equals(s.getSubject().getId()));
	}

	@Override
	public List<SubjectSyllabusVersion> listSyllabi(UUID subjectId) {
		return syllabi.values().stream()
				.filter(s -> s.getSubject() != null && subjectId.equals(s.getSubject().getId()))
				.sorted(Comparator.comparing(SubjectSyllabusVersion::getVersionLabel))
				.toList();
	}

	@Override
	public boolean versionLabelTaken(UUID subjectId, String versionLabel, UUID excludeSyllabusId) {
		return syllabi.values().stream()
				.filter(s -> s.getSubject() != null && subjectId.equals(s.getSubject().getId()))
				.filter(s -> excludeSyllabusId == null || !excludeSyllabusId.equals(s.getId()))
				.anyMatch(s -> s.getVersionLabel().equalsIgnoreCase(versionLabel));
	}

	@Override
	public boolean externalIdTaken(UUID subjectId, String externalSyllabusId, UUID excludeSyllabusId) {
		return syllabi.values().stream()
				.filter(s -> s.getSubject() != null && subjectId.equals(s.getSubject().getId()))
				.filter(s -> excludeSyllabusId == null || !excludeSyllabusId.equals(s.getId()))
				.anyMatch(s -> externalSyllabusId.equals(s.getExternalSyllabusId()));
	}

	@Override
	public SubjectSyllabusVersion saveSyllabus(SubjectSyllabusVersion syllabus) {
		if (syllabus.getId() == null) {
			syllabus.setId(UUID.randomUUID());
		}
		syllabi.put(syllabus.getId(), syllabus);
		return syllabus;
	}

	@Override
	public List<SyllabusLearningOutcome> listOutcomes(UUID syllabusId) {
		return sortedCopy(outcomes.getOrDefault(syllabusId, List.of()), SyllabusLearningOutcome::getOrderIndex);
	}

	@Override
	public List<SyllabusLearningUnit> listLearningUnits(UUID syllabusId) {
		return sortedCopy(learningUnits.getOrDefault(syllabusId, List.of()), SyllabusLearningUnit::getOrderIndex);
	}

	@Override
	public List<SyllabusPhase> listPhases(UUID syllabusId) {
		return sortedCopy(phases.getOrDefault(syllabusId, List.of()), SyllabusPhase::getOrderIndex);
	}

	@Override
	public List<SyllabusExpectedActivity> listActivities(UUID syllabusId) {
		return sortedCopy(activities.getOrDefault(syllabusId, List.of()), SyllabusExpectedActivity::getOrderIndex);
	}

	@Override
	public List<SyllabusExpectedDeliverable> listDeliverables(UUID syllabusId) {
		return sortedCopy(deliverables.getOrDefault(syllabusId, List.of()), SyllabusExpectedDeliverable::getOrderIndex);
	}

	@Override
	public List<SyllabusPhaseLearningOutcome> listPhaseOutcomeLinks(UUID syllabusId) {
		return List.copyOf(phaseLinks.getOrDefault(syllabusId, List.of()));
	}

	@Override
	public List<SyllabusDeliverableLearningOutcome> listDeliverableOutcomeLinks(UUID syllabusId) {
		return List.copyOf(deliverableLinks.getOrDefault(syllabusId, List.of()));
	}

	@Override
	public List<SyllabusLearningUnitOutcome> listUnitOutcomeLinks(UUID syllabusId) {
		return List.copyOf(unitLinks.getOrDefault(syllabusId, List.of()));
	}

	@Override
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
		AcademicStructureIntegrity.requireGraph(
				syllabusId, nextActivities, nextDeliverables, nextPhaseLinks, nextDeliverableLinks, nextUnitLinks);
		outcomes.put(syllabusId, new ArrayList<>(nextOutcomes));
		learningUnits.put(syllabusId, new ArrayList<>(nextUnits));
		phases.put(syllabusId, new ArrayList<>(nextPhases));
		activities.put(syllabusId, new ArrayList<>(nextActivities));
		deliverables.put(syllabusId, new ArrayList<>(nextDeliverables));
		phaseLinks.put(syllabusId, new ArrayList<>(nextPhaseLinks));
		deliverableLinks.put(syllabusId, new ArrayList<>(nextDeliverableLinks));
		unitLinks.put(syllabusId, new ArrayList<>(nextUnitLinks));
	}

	private static boolean containsIgnoreCase(String value, String search) {
		return value != null && value.toLowerCase().contains(search.toLowerCase());
	}

	private static <T> List<T> sortedCopy(List<T> values, java.util.function.Function<T, Integer> order) {
		return values.stream().sorted(Comparator.comparing(order)).toList();
	}
}

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AcademicCatalogStore {

	Optional<Subject> findSubjectById(UUID id);

	Optional<Subject> findSubjectByCode(String code);

	boolean existsSubjectCode(String code);

	List<Subject> listSubjects(String code, SubjectStatus status, String search);

	Subject saveSubject(Subject subject);

	Optional<SubjectSyllabusVersion> findSyllabusById(UUID id);

	Optional<SubjectSyllabusVersion> findSyllabus(UUID subjectId, UUID syllabusId);

	List<SubjectSyllabusVersion> listSyllabi(UUID subjectId);

	boolean versionLabelTaken(UUID subjectId, String versionLabel, UUID excludeSyllabusId);

	boolean externalIdTaken(UUID subjectId, String externalSyllabusId, UUID excludeSyllabusId);

	SubjectSyllabusVersion saveSyllabus(SubjectSyllabusVersion syllabus);

	List<SyllabusLearningOutcome> listOutcomes(UUID syllabusId);

	List<SyllabusLearningUnit> listLearningUnits(UUID syllabusId);

	List<SyllabusPhase> listPhases(UUID syllabusId);

	List<SyllabusExpectedActivity> listActivities(UUID syllabusId);

	List<SyllabusExpectedDeliverable> listDeliverables(UUID syllabusId);

	List<SyllabusPhaseLearningOutcome> listPhaseOutcomeLinks(UUID syllabusId);

	List<SyllabusDeliverableLearningOutcome> listDeliverableOutcomeLinks(UUID syllabusId);

	List<SyllabusLearningUnitOutcome> listUnitOutcomeLinks(UUID syllabusId);

	void replaceStructure(
			UUID syllabusId,
			List<SyllabusLearningOutcome> outcomes,
			List<SyllabusLearningUnit> learningUnits,
			List<SyllabusPhase> phases,
			List<SyllabusExpectedActivity> activities,
			List<SyllabusExpectedDeliverable> deliverables,
			List<SyllabusPhaseLearningOutcome> phaseOutcomeLinks,
			List<SyllabusDeliverableLearningOutcome> deliverableOutcomeLinks,
			List<SyllabusLearningUnitOutcome> unitOutcomeLinks);
}
